import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from '../src/host.mjs';
import { inspect, imports, cssResources } from '../src/programs.mjs';

test('errorCatched preserves results and reports then rethrows sync and async failures', async () => {
  const notices = [];
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => ({}), notify: (...args) => notices.push(args) });
  let calls = 0;
  const wrapped = host.api.errorCatched((a, b) => { calls++; return a + b; });
  assert.equal(calls, 0);
  assert.equal(wrapped(2, 3), 5);
  assert.equal(calls, 1);
  assert.equal(await host.api.errorCatched(async value => value)(7), 7);
  assert.deepEqual(notices, []);
  const syncError = new Error('sync initialization failed');
  assert.throws(() => host.api.errorCatched(() => { throw syncError; })(), error => error === syncError);
  assert.equal(notices.length, 1);
  assert.equal(notices[0][0], 'error');
  assert.match(notices[0][1], /sync initialization failed/);
  const asyncError = new Error('async initialization failed');
  await assert.rejects(host.api.errorCatched(async () => { throw asyncError; })(), error => error === asyncError);
  assert.equal(notices.length, 2);
  assert.match(notices[1][1], /async initialization failed/);
});

const snapshot = () => ({ revision: 'r0', conversationId: 'c', draft: '', chatVariables: {}, scriptVariables: {}, mvu: null,
  worldbooks: [], messages: [
    { id: 'm0', turnId: 't0', variantId: 'v0', message_id: 0, role: 'assistant', name: 'Actor', message: 'Opening', is_hidden: false,
      data: {}, extra: {}, swipe_id: 0, swipes: ['Opening', 'Alternative'], swipes_data: [{}, {}], swipes_info: [{}, {}] },
  ] });

test('getAllVariables shallowly merges through the owned floor and tracks candidate changes', () => {
  const initial = snapshot();
  initial.program = { variables: { inherited: 1, nested: { character: true } } };
  initial.chatVariables = { chat: true, nested: { chat: true } };
  initial.messages[0].data = { first: true, nested: { first: true } };
  initial.messages.push({ ...initial.messages[0], turnId: 't1', message_id: 1, data: { future: true } });
  const host = createHost({ initial, actor: { turnId: 't0' }, request: async () => ({}) });
  assert.deepEqual(host.api.getAllVariables(), { inherited: 1, chat: true, first: true, nested: { first: true } });
  const copy = host.api.getAllVariables(); copy.nested.first = false;
  assert.equal(host.api.getAllVariables().nested.first, true);
  initial.messages[0].data = { alternative: true };
  host.receive(initial);
  assert.deepEqual(host.api.getAllVariables(), { inherited: 1, chat: true, nested: { chat: true }, alternative: true });
  initial.messages = [];
  host.receive(initial);
  assert.throws(() => host.api.getAllVariables(), /no longer exists/);
});

test('script aggregation uses imported defaults and pending writes without including message variables', async () => {
  const initial = snapshot();
  initial.program = { variables: { character: true }, sources: [{ id: 's', data: { seed: 2, shared: 'script' } }] };
  initial.chatVariables = { shared: 'chat' };
  initial.messages[0].data = { messageOnly: true };
  const host = createHost({ initial, actor: { scriptId: 's' }, request: async (_, args) => ({ snapshot: { scriptVariables: { s: args.data } } }) });
  assert.deepEqual(host.api.getVariables({ type: 'script' }), { seed: 2, shared: 'script' });
  assert.deepEqual(host.api.getAllVariables(), { character: true, seed: 2, shared: 'chat' });
  host.api.replaceVariables({}, { type: 'script' });
  assert.deepEqual(host.api.getAllVariables(), { character: true, shared: 'chat' });
  await host.flush();
  assert.deepEqual(host.api.getVariables({ type: 'script' }), {});
});

test('variable helpers merge nested objects, replace arrays and preserve durable ordering', async () => {
  const initial = snapshot(), writes = [];
  initial.chatVariables = { actor: { score: 0, keep: true }, items: ['old', 'tail'] };
  const host = createHost({ initial, actor: {}, request: async (_, args) => {
    writes.push(args.data); return { snapshot: { chatVariables: args.data } };
  } });
  assert.deepEqual(host.api.insertOrAssignVariables({ actor: { score: 3 }, items: ['new'] }),
    { actor: { score: 3, keep: true }, items: ['new'] });
  assert.deepEqual(host.api.insertVariables({ actor: { score: 9, added: false }, items: ['ignored', 'tail'] }),
    { actor: { score: 3, keep: true, added: false }, items: ['new'] });
  const deleted = host.api.deleteVariable('actor.score');
  assert.equal(deleted.delete_occurred, true);
  assert.deepEqual(deleted.variables.actor, { keep: true, added: false });
  // Lodash unset returns true even when the path was already absent.
  assert.equal(host.api.deleteVariable('actor.absent').delete_occurred, true);
  await host.flush();
  assert.equal(writes.length, 4);
  assert.deepEqual(writes.at(-1), host.api.getVariables());
});

test('synchronous writes are visible immediately and durably ordered', async () => {
  let saved = snapshot(), serial = 0; const calls = [];
  const host = createHost({ initial: saved, actor: { scriptId: 'script' }, request: async (method, args, revision) => {
    calls.push([method, revision]); assert.equal(revision, saved.revision);
    saved = { ...saved, revision: 'r' + (++serial), chatVariables: args.data };
    return { snapshot: saved, value: null };
  } });
  host.api.replaceVariables({ counter: 1 });
  assert.equal(host.api.getVariables().counter, 1);
  host.api.replaceVariables({ counter: host.api.getVariables().counter + 1 });
  assert.equal(host.api.getVariables().counter, 2);
  await host.flush();
  assert.equal(saved.chatVariables.counter, 2);
  assert.deepEqual(calls.map(c => c[1]), ['r0', 'r1']);
});

test('failed durable write stops the runtime and restores its committed view', async () => {
  const notices = [];
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => { throw new Error('Disk full'); }, notify: (...args) => notices.push(args) });
  host.api.replaceVariables({ changed: true });
  assert.equal(host.api.getVariables().changed, true);
  await assert.rejects(host.flush(), /Disk full/);
  assert.deepEqual(host.api.getVariables(), {});
  await assert.rejects(host.api.generate({ user_input: 'next' }), /Disk full/);
  assert.equal(notices.length, 1);
});

test('pending view survives a committed update from another frame', async () => {
  let release;
  const host = createHost({ initial: snapshot(), actor: {}, request: () => new Promise(resolve => { release = resolve; }) });
  host.api.replaceVariables({ local: true });
  await Promise.resolve();
  host.receive({ ...snapshot(), revision: 'remote', chatVariables: { remote: true } });
  assert.deepEqual(host.api.getVariables(), { local: true });
  release({ snapshot: { ...snapshot(), revision: 'r2', chatVariables: { local: true } } });
  await host.flush();
});

test('message queries retain candidate shape, ranges and filters', () => {
  const host = createHost({ initial: snapshot(), actor: { turnId: 't0' }, request: async () => ({}) });
  assert.equal(host.api.getChatMessages(-1)[0].message, 'Opening');
  assert.equal(host.api.getChatMessages('0-{{lastMessageId}}').length, 1);
  assert.deepEqual(host.api.getChatMessages(0, { include_swipes: true })[0].swipes, ['Opening', 'Alternative']);
  assert.equal(host.api.getChatMessages(5)[0].message, 'Opening');
  assert.deepEqual(host.api.getChatMessages('invalid'), []);
  assert.deepEqual(host.api.getChatMessages(0, { role: 'user' }), []);
  assert.throws(() => host.api.getChatMessages(0, { unknown: true }), /Unsupported/);
});

test('scope errors are explicit rather than silently becoming chat variables', () => {
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => ({}) });
  assert.throws(() => host.api.getVariables({ type: 'global' }), /Unsupported/);
  assert.throws(() => host.api.replaceVariables({}, { type: 'script' }), /Unavailable/);
  assert.throws(() => host.api.getVariables({ type: 'chat', message_id: 0 }), /Invalid/);
});

test('once and removal apply before reentrant delivery', async () => {
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => ({}) });
  let count = 0;
  host.api.eventOnce('event', async () => { count++; await host.dispatch('event', []); });
  await host.dispatch('event', []); await host.dispatch('event', []);
  assert.equal(count, 1);
  const listener = host.api.eventOn('event', () => count++); listener.stop();
  await host.dispatch('event', []); assert.equal(count, 1);
});

test('only an exact dependency-only loader can be consumed by QuickJS', () => {
  const loader = "import 'https://testingcf.jsdelivr.net/gh/MagicalAstrogy/MagVarUpdate/artifact/bundle.js';";
  assert.equal(inspect(loader), 'mvu-loader');
  assert.throws(() => inspect(loader + 'window.counter++'), /cannot be split/);
  assert.equal(inspect("const source = " + JSON.stringify(loader)), 'browser');
  assert.equal(inspect("import {registerMvuSchema} from 'https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js'; registerMvuSchema(z.object({}));"), 'mvu-schema');
});

test('module import locations exclude comments and preserve dynamic expressions', () => {
  const source = "// import './ignored.js'\nimport value from './a.js'; export {value} from './b.js'; const p = import(name); console.log(import.meta.url);";
  const list = JSON.parse(imports(source));
  assert.equal(list.length, 4);
  assert.deepEqual(list.filter(item => item.value).map(item => item.value), ['./b.js', './a.js']);
  assert.equal(list.find(item => item.dynamic).end - list.find(item => item.dynamic).start, 4);
});


test('queued values do not retain caller-owned objects', async () => {
  let args;
  const host = createHost({ initial: snapshot(), actor: {}, request: async (method, value) => { args = value; return {}; } });
  const value = { score: 1 }; host.api.replaceVariables(value); value.score = 99;
  host.receive(snapshot());
  assert.equal(host.api.getVariables().score, 1);
  await host.flush(); assert.equal(args.data.score, 1);
});
test('CSS parsing preserves comments and quoted parentheses', () => {
  const source = '/* url(fake.png) */ @import "./base.css"; a { background:url("./a(b).png"); content:"url(fake)" }';
  const parsed = JSON.parse(cssResources(source));
  assert.deepEqual(parsed.urls.sort(), ['./a(b).png', './base.css']);
  const rewritten = JSON.parse(cssResources(source, { './a(b).png': 'https://cdn.example/v/a(b).png' })).text;
  assert.ok(rewritten.includes('url("https://cdn.example/v/a(b).png")'));
  assert.ok(rewritten.includes('/* url(fake.png) */'));
});
test('stop methods return synchronous booleans from the current generation view', () => {
  const host = createHost({ initial: { ...snapshot(), generating: true, generation: { id: 'g', status: 'running' } }, actor: {}, request: async () => ({}) });
  assert.equal(host.api.stopGenerationById('other'), false);
  assert.equal(host.api.stopGenerationById('g'), true);
});


test('variable updater preserves synchronous and asynchronous return types', async () => {
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => ({}) });
  const result = host.api.updateVariablesWith(v => ({ ...v, score: 4 }));
  assert.equal(result.score, 4); assert.equal(typeof result.then, 'undefined');
  assert.equal(host.api.getVariables().score, 4);
  const asyncResult = host.api.updateVariablesWith(async v => ({ ...v, score: 5 }));
  assert.equal(typeof asyncResult.then, 'function');
  assert.equal((await asyncResult).score, 5); await host.flush();
});


test('message structure operations share the durable queue and preserve helper option aliases', async () => {
  const calls = [];
  const host = createHost({ initial: snapshot(), actor: {}, request: async (method, args) => { calls.push({ method, args }); return {}; } });
  host.api.replaceVariables({ pending: true });
  await host.api.createChatMessages([{ role: 'user', message: 'choice' }], { insert_at: -1 });
  await host.api.deleteChatMessages([-1, 0]);
  await host.api.rotateChatMessages(0, 1, 2, { refresh: 'all' });
  assert.deepEqual(calls.map(x => x.method), ['variables.replace', 'messages.create', 'messages.delete', 'messages.rotate']);
  assert.equal(calls[1].args.insert_before, -1);
  assert.equal(calls[3].args.refresh, 'all');
});

test('message range clamps and sorts both ends before filtering', () => {
  const initial = snapshot();
  initial.messages.push({ ...initial.messages[0], message_id: 1, role: 'user', message: 'second' });
  const host = createHost({ initial, actor: {}, request: async () => ({}) });
  assert.deepEqual(host.api.getChatMessages('99--99').map(x => x.message), ['Opening', 'second']);
  assert.equal(host.api.getChatMessages('-9-9', { role: 'user' })[0].message, 'second');
  assert.equal(host.api.getChatMessages(-999)[0].message, 'Opening');
});


test('regex updater preserves async callbacks and saves the actual replacement list', async () => {
  const initial = snapshot(), calls = [];
  initial.characterRegexes = [{ id: 'r', enabled: false, replace_string: 'old' }];
  const host = createHost({ initial, actor: {}, request: async (method, args) => {
    calls.push(method); return { snapshot: { characterRegexes: args.regexes } };
  } });
  const updated = await host.api.updateTavernRegexesWith(async rules => {
    assert.equal(rules[0].scope, 'character');
    rules[0].enabled = true; rules[0].replace_string = 'new'; return rules;
  }, { scope: 'character' });
  assert.equal(updated[0].replace_string, 'new');
  assert.equal(host.api.getTavernRegexes({ type: 'character', enable_state: 'enabled' }).length, 1);
  assert.deepEqual(calls, ['regex.replace']);
  assert.throws(() => host.api.getTavernRegexes({ type: 'preset' }), /Unsupported/);
});


test('character variables keep their scope and update aggregation before durable acknowledgement', async () => {
  const initial = snapshot(); initial.program = { variables: { seed: 1 } };
  const host = createHost({ initial, actor: {}, request: async (_, args) => ({ snapshot: { characterVariables: args.data } }) });
  assert.deepEqual(host.api.getVariables({ type: 'character' }), { seed: 1 });
  host.api.replaceVariables({ seed: 2 }, { type: 'character' });
  assert.equal(host.api.getAllVariables().seed, 2);
  assert.deepEqual(host.api.getVariables(), {});
  await host.flush();
  host.api.replaceVariables({}, { type: 'character' });
  await host.flush();
  assert.deepEqual(host.api.getVariables({ type: 'character' }), {});
});
