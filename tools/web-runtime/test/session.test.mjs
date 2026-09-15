import test from 'node:test';
import assert from 'node:assert/strict';
import { createSession } from '../src/session.mjs';
import { createHost } from '../src/host.mjs';

const initial = () => ({ revision: 'r0', chatVariables: {}, scriptVariables: {}, messages: [], worldbooks: [], mvu: null });

test('draft deltas preserve historical payloads and message deltas isolate incoming objects', () => {
  const session = createSession({ ...initial(), messages: [
    { turnId: 'a', variantId: 'a1', data: { score: 1 } },
    { turnId: 'b', variantId: 'b1', data: { score: 2 } },
  ] });
  const history = session.state.messages, first = history[0];
  session.receiveDelta({ changes: { draft: 'typing', revision: 'r1' } });
  assert.equal(session.state.messages, history);
  assert.equal(session.state.draft, 'typing');
  const updated = { turnId: 'b', variantId: 'b1', data: { score: 3 } };
  session.receiveDelta({ messages: [updated], changes: { revision: 'r2' } });
  updated.data.score = 99;
  assert.equal(session.state.messages[0], first);
  assert.equal(session.state.messages[1].data.score, 3);
  session.state.messages[1].data.score = 100;
  session.stop(new Error('failed'));
  assert.equal(session.state.messages[1].data.score, 3);
});

test('deltas retain pending writes and restore the latest committed state on failure', async () => {
  const { session, a, b } = pair(async () => { throw new Error('disk full'); });
  a.api.replaceVariables({ pending: true });
  session.receiveDelta({ changes: { revision: 'r1', draft: 'new draft', chatVariables: { saved: true } } });
  assert.deepEqual(b.api.getVariables(), { pending: true });
  await assert.rejects(b.flush(), /disk full/);
  assert.deepEqual(b.api.getVariables(), { saved: true });
  assert.equal(session.state.draft, 'new draft');
});

test('candidate and order deltas retire stale owners before replaying pending writes', async () => {
  const state = { ...initial(), messages: [{ turnId: 'turn', variantId: 'old', data: {} }] };
  const session = createSession(state); let calls = 0;
  const host = createHost({ initial: state, actor: { id: 'page', turnId: 'turn', variantId: 'old' }, session,
    request: async () => { calls++; return {}; } });
  host.api.replaceVariables({ old: true });
  session.receiveDelta({ messages: [{ turnId: 'turn', variantId: 'new', data: {} }], changes: { revision: 'r1' } });
  await assert.rejects(host.flush(), /disposed/);
  assert.equal(calls, 0);
  assert.deepEqual(session.state.chatVariables, {});
  session.receiveDelta({ messages: [{ turnId: 'second', variantId: 'v', data: {} }], order: ['second', 'turn'] });
  assert.deepEqual(session.state.messages.map(message => message.turnId), ['second', 'turn']);
  session.receiveDelta({ order: ['turn'] });
  assert.equal(session.state.messages.length, 1);
  assert.throws(() => session.receiveDelta({ order: ['missing'] }), /Missing message/);
  assert.equal(session.state.messages[0].turnId, 'turn');
});
function pair(request = async () => ({})) {
  const state = initial(), session = createSession(state), notices = [];
  const host = id => createHost({ initial: state, actor: { id, scriptId: id }, request, session, notify: (...args) => notices.push(args) });
  return { session, a: host('a'), b: host('b'), notices };
}

test('globals retain functions and identity for waiters, parent windows and late pages', async () => {
  const { session, a, b } = pair(), parent = {}, page = {};
  b.install(parent); b.install(page);
  const waiting = b.api.waitGlobalInitialized('Library');
  const value = { count: 0, increment() { this.count++; } };
  a.api.initializeGlobal('Library', value);
  await waiting;
  assert.equal(page.Library, value); assert.equal(parent.Library, value);
  page.Library.increment(); assert.equal(value.count, 1);
  const late = createHost({ initial: session.state, actor: { id: 'late' }, request: async () => ({}), session });
  const target = {}; late.install(target); assert.equal(target.Library, value);
  a.dispose(); assert.equal(page.Library, undefined);
  b.api.initializeGlobal('Library', { replacement: true }); assert.equal(target.Library.replacement, true);
});

test('event order is session-wide and synchronous callbacks share their argument', async () => {
  const { a, b, notices } = pair(), order = [];
  const first = object => { order.push('a'); object.count++; };
  a.api.eventOn('event', first);
  b.api.eventOn('event', object => { order.push('b'); object.count++; });
  a.api.eventMakeLast('event', first);
  const value = { count: 0 };
  assert.equal(a.api.eventEmitAndWait('event', value), undefined);
  assert.deepEqual(order, ['b', 'a']); assert.equal(value.count, 2);
  a.api.eventMakeFirst('event', first); order.length = 0;
  b.api.eventOn('event', () => { throw new Error('listener failed'); });
  b.api.eventOn('event', () => order.push('after-error'));
  await b.api.eventEmit('event', value);
  assert.deepEqual(order, ['a', 'b', 'after-error']); assert.equal(notices.length, 1);
});

test('once handles reentry and clear operations only remove the calling owner listeners', async () => {
  const { a, b } = pair(); let once = 0, other = 0;
  a.api.eventOnce('event', () => { once++; b.api.eventEmitAndWait('event'); });
  b.api.eventOn('event', () => other++);
  await a.api.eventEmit('event');
  assert.equal(once, 1); assert.equal(other, 2);
  a.api.eventClearAll(); b.api.eventEmitAndWait('event'); assert.equal(other, 3);
  b.api.eventClearEvent('event'); await a.api.eventEmit('event'); assert.equal(other, 3);
});

test('async emit awaits listeners in order without blocking nested dispatch', async () => {
  const { a, b } = pair(), order = [];
  a.api.eventOn('inner', () => order.push('inner'));
  a.api.eventOn('outer', async () => { await Promise.resolve(); await b.api.eventEmit('inner'); order.push('a'); });
  b.api.eventOn('outer', () => order.push('b'));
  await b.api.eventEmit('outer'); assert.deepEqual(order, ['inner', 'a', 'b']);
});

test('cross-page writes share optimistic reads and one durable revision queue', async () => {
  let saved = initial(), version = 0; const seen = [];
  const { a, b } = pair(async (_, args, revision) => {
    assert.equal(revision, saved.revision); seen.push(args.data.count);
    saved = { ...saved, revision: 'r' + (++version), chatVariables: args.data }; return { snapshot: saved };
  });
  a.api.replaceVariables({ count: 1 });
  b.api.replaceVariables({ count: b.api.getVariables().count + 1 });
  assert.equal(a.api.getVariables().count, 2);
  await b.flush(); assert.deepEqual(seen, [1, 2]); assert.equal(saved.chatVariables.count, 2);
});

test('disposal cancels queued work and waiters without stopping other pages', async () => {
  const calls = [], { a, b } = pair(async (_, args) => { calls.push(args.data); return { snapshot: { chatVariables: args.data } }; });
  const waiting = a.api.waitGlobalInitialized('Never');
  const rejected = assert.rejects(waiting, /disposed/);
  a.api.replaceVariables({ obsolete: true }); a.dispose();
  await rejected;
  assert.throws(() => a.api.getVariables(), /disposed/);
  b.api.replaceVariables({ current: true }); await b.flush();
  assert.deepEqual(calls, [{ current: true }]); assert.deepEqual(b.api.getVariables(), { current: true });
});

test('one storage failure stops all writers and restores the common committed view', async () => {
  const { a, b, notices } = pair(async () => { throw new Error('disk full'); });
  a.api.replaceVariables({ count: 1 }); b.api.replaceVariables({ count: 2 });
  await assert.rejects(b.flush(), /disk full/);
  assert.deepEqual(a.api.getVariables(), {}); assert.deepEqual(b.api.getVariables(), {});
  await assert.rejects(b.api.generate(), /disk full/); assert.equal(notices.length, 2);
});

test('destroying an in-flight page releases the common queue for surviving pages', async () => {
  const state = initial(), session = createSession(state);
  let rejectRequest;
  const a = createHost({ initial: state, actor: { id: 'a' }, session,
    request: () => new Promise((_, reject) => { rejectRequest = reject; }) });
  a.onDispose(() => rejectRequest(new Error('Runtime disposed')));
  a.api.replaceVariables({ abandoned: true }); await Promise.resolve();
  const b = createHost({ initial: state, actor: { id: 'b' }, session,
    request: async (_, args) => ({ snapshot: { chatVariables: args.data } }) });
  b.api.replaceVariables({ surviving: true });
  session.disposeFrame('a'); await b.flush();
  assert.deepEqual(b.api.getVariables(), { surviving: true });
});

test('candidate snapshots invalidate old owners before their queued writes can run', async () => {
  const state = { ...initial(), messages: [{ turnId: 'turn', variantId: 'old', data: {} }] };
  const session = createSession(state); let calls = 0;
  const host = createHost({ initial: state, actor: { id: 'page', turnId: 'turn', variantId: 'old' }, session,
    request: async () => { calls++; return {}; } });
  host.api.replaceVariables({ old: true });
  session.receive({ ...state, messages: [{ turnId: 'turn', variantId: 'new', data: {} }] });
  await assert.rejects(host.flush(), /disposed/);
  assert.equal(calls, 0); assert.deepEqual(session.state.chatVariables, {});
});

test('disposing a page releases dispatch waiting on its unfinished async callback', async () => {
  const { a, b } = pair(); let continued = false;
  a.api.eventOn('slow', () => new Promise(() => {}));
  b.api.eventOn('slow', () => { continued = true; });
  const dispatch = b.api.eventEmit('slow');
  a.dispose(); await dispatch;
  assert.equal(continued, true);
});
