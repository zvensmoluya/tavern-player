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

// World books live inside the conversation: the snapshot owns the nested entry shape, while
// `worldbook.entries.read` produces the legacy flat shape. Both are synthetic fixtures.
const worldbookFixture = () => [
  { id: 'alpha', name: 'alpha', enabled: true, entries: [
    { uid: 5, player_entry_id: 'alpha:5', book: 'alpha', name: 'Dragon', comment: 'Dragon', enabled: true,
      strategy: { type: 'selective', keys: ['dragon'], keys_secondary: { logic: 'and_any', keys: ['cave'] }, scan_depth: 4 },
      position: { type: 'at_depth', role: 'system', depth: 3, order: 100 },
      probability: 80, recursion: { prevent_incoming: false, prevent_outgoing: true, delay_until: 2 },
      effect: { sticky: 3, cooldown: null, delay: null }, extra: { note: 'fixture' }, content: 'Dragons sleep in caves.' },
    { uid: 9, player_entry_id: 'alpha:9', book: 'alpha', name: 'Rune', comment: 'Rune', enabled: true,
      strategy: { type: 'constant', keys: [], keys_secondary: { logic: 'and_any', keys: [] }, scan_depth: 'same_as_global' },
      position: { type: 'before_character_definition', role: 'system', depth: 4, order: 200 },
      probability: 100, recursion: { prevent_incoming: true, prevent_outgoing: false, delay_until: null },
      effect: { sticky: null, cooldown: 5, delay: 1 }, extra: {}, content: 'A rune glows.' },
  ] },
  { id: 'beta', name: 'beta', enabled: false, entries: [] },
  { id: 'gamma', name: 'gamma', enabled: true, entries: [] },
];
const flatWorldbookFixture = () => [
  { uid: 5, display_index: 0, comment: 'Dragon', enabled: true, type: 'selective', position: 'at_depth_as_system',
    depth: 3, order: 100, probability: 80, keys: ['dragon'], logic: 'and_any', filters: [], scan_depth: 4,
    case_sensitive: 'same_as_global', match_whole_words: 'same_as_global', use_group_scoring: 'same_as_global', automation_id: null,
    exclude_recursion: false, prevent_recursion: true, delay_until_recursion: 2, content: 'Dragons sleep in caves.',
    group: '', group_prioritized: false, group_weight: 100, sticky: 3, cooldown: null, delay: null },
  { uid: 9, display_index: 1, comment: 'Rune', enabled: true, type: 'constant', position: 'before_character_definition',
    depth: null, order: 200, probability: 100, keys: [], logic: 'and_any', filters: [], scan_depth: 'same_as_global',
    case_sensitive: 'same_as_global', match_whole_words: 'same_as_global', use_group_scoring: 'same_as_global', automation_id: null,
    exclude_recursion: true, prevent_recursion: false, delay_until_recursion: null, content: 'A rune glows.',
    group: '', group_prioritized: false, group_weight: 100, sticky: null, cooldown: 5, delay: 1 },
];
const pick = (value, keys) => Object.fromEntries(keys.map(key => [key, value[key]]));
const nestedPayload = entry => entry.strategy || entry.recursion || entry.effect || typeof entry.position === 'object';

/** Answers world book bridge calls from mutable fixture state, like the native snapshot would. */
function worldbookStub() {
  const calls = [];
  let revision = 0, next_uid = 20, worldbooks = worldbookFixture();
  const flat = { alpha: flatWorldbookFixture() };
  const book = name => worldbooks.find(value => value.name === name);
  const flatOf = name => (flat[name] ??= []);
  const copy = value => JSON.parse(JSON.stringify(value));
  const view = () => ({ revision: 'r' + (++revision), worldbooks: copy(worldbooks) });
  const request = async (method, args) => {
    calls.push({ method, args: copy(args) });
    if (method === 'worldbook.activation') book(args.book).enabled = args.enabled;
    else if (method === 'worldbook.entries.read') return { snapshot: view(), value: copy(flatOf(args.book)) };
    else if (method === 'worldbook.entries.replace') {
      // Both shapes reach this method; the fixture decides by looking at the payload.
      if (args.entries.some(nestedPayload)) book(args.book).entries = args.entries;
      else flat[args.book] = args.entries;
    } else if (method === 'worldbook.entries.update') {
      const entries = flatOf(args.book);
      for (const patch of args.entries) {
        const index = entries.findIndex(entry => entry.uid === patch.uid);
        entries[index] = { ...entries[index], ...patch };
      }
    } else if (method === 'worldbook.entries.create') {
      const nested = args.entries.some(nestedPayload);
      for (const entry of args.entries) {
        const uid = entry.uid ?? next_uid++;
        if (nested) book(args.book).entries.push({ enabled: true, ...entry, uid, book: args.book });
        else flatOf(args.book).push({ ...flatWorldbookFixture()[0], comment: '', content: '', keys: [],
          sticky: null, cooldown: null, delay: null, display_index: flatOf(args.book).length, ...entry, uid });
      }
    } else if (method === 'worldbook.entries.delete') {
      book(args.book).entries = book(args.book).entries.filter(entry => !args.uids.includes(entry.uid));
      flat[args.book] = flatOf(args.book).filter(entry => !args.uids.includes(entry.uid));
    } else if (method === 'worldbook.books.create') {
      const entries = (args.entries ?? []).map(entry => ({ enabled: true, ...entry, uid: entry.uid ?? next_uid++ }));
      const existing = book(args.name);
      if (existing) existing.entries = entries;
      else worldbooks.push({ id: args.name, name: args.name, enabled: false, entries });
    } else if (method === 'worldbook.books.delete') worldbooks = worldbooks.filter(value => value.name !== args.name);
    else if (method !== 'worldbook.books.rebind') throw new Error('Unexpected bridge method: ' + method);
    return { snapshot: view(), value: null };
  };
  return { calls, request, get worldbooks() { return copy(worldbooks); } };
}
const worldbookHost = () => {
  const stub = worldbookStub();
  return { stub, host: createHost({ initial: { ...snapshot(), worldbooks: stub.worldbooks }, actor: {}, request: stub.request }) };
};

test('world book reads expose the nested and the legacy flat shape', async () => {
  const { stub, host } = worldbookHost();
  assert.deepEqual(host.api.getWorldbookNames(), ['alpha', 'beta', 'gamma']);
  assert.deepEqual(host.api.getGlobalWorldbookNames(), []);
  const nested = await host.api.getWorldbook('alpha');
  assert.equal(nested.length, 2);
  assert.deepEqual(pick(nested[0], ['uid', 'name', 'enabled', 'content']),
    { uid: 5, name: 'Dragon', enabled: true, content: 'Dragons sleep in caves.' });
  assert.equal(nested[0].position.type, 'at_depth');
  assert.equal(nested[0].position.role, 'system');
  assert.equal(nested[0].position.depth, 3);
  assert.equal(nested[0].position.order, 100);
  assert.equal(nested[0].effect.sticky, 3);
  assert.equal(nested[0].effect.cooldown, null);
  assert.deepEqual(nested[0].strategy.keys, ['dragon']);
  assert.deepEqual(nested[0].recursion, { prevent_incoming: false, prevent_outgoing: true, delay_until: 2 });
  assert.equal(nested[1].position.type, 'before_character_definition');
  assert.equal('player_entry_id' in nested[0], false);
  assert.deepEqual(stub.calls, []);
  nested[0].content = 'mutated by the caller';
  assert.equal((await host.api.getWorldbook('alpha'))[0].content, 'Dragons sleep in caves.');
  const flat = await host.api.getLorebookEntries('alpha');
  assert.deepEqual(stub.calls, [{ method: 'worldbook.entries.read', args: { book: 'alpha' } }]);
  assert.deepEqual(flat, flatWorldbookFixture());
  assert.equal(flat[0].position, 'at_depth_as_system');
  assert.equal(flat[1].position, 'before_character_definition');
  assert.equal(flat[0].sticky, 3);
  assert.equal(flat[1].sticky, null);
  assert.equal(flat[1].cooldown, 5);
  assert.equal(flat[0].effect, undefined);
  assert.equal(flat[0].automation_id, null);
  assert.deepEqual(flat[0].filters, []);
  await assert.rejects(host.api.getWorldbook('missing'), /does not exist/);
  await assert.rejects(host.api.getLorebookEntries('missing'), /does not exist/);
});

test('character world book bindings only count enabled books', async () => {
  const { stub, host } = worldbookHost();
  const expected = { primary: 'alpha', additional: ['gamma'] };
  assert.deepEqual(host.api.getCharWorldbookNames('current'), expected);
  assert.deepEqual(host.api.getCharWorldbookNames(), expected);
  assert.deepEqual(host.api.getCharLorebooks('current'), expected);
  assert.deepEqual(host.api.getCharLorebooks(), expected);
  assert.throws(() => host.api.getCharWorldbookNames('other'), /Unavailable/);
  assert.throws(() => host.api.getCharLorebooks('other'), /Unavailable/);
  await host.api.setWorldbookEnabled('alpha', false);
  assert.deepEqual(host.api.getCharWorldbookNames('current'), { primary: 'gamma', additional: [] });
  assert.deepEqual(host.api.getWorldbookNames(), ['alpha', 'beta', 'gamma']);
  await host.api.rebindCharWorldbooks('current', { primary: 'beta', additional: ['gamma'] });
  assert.deepEqual(stub.calls.at(-1), { method: 'worldbook.books.rebind', args: { primary: 'beta', additional: ['gamma'] } });
  await host.api.rebindCharWorldbooks('current', { primary: null, additional: [] });
  assert.deepEqual(stub.calls.at(-1).args, { primary: null, additional: [] });
  await assert.rejects(host.api.rebindCharWorldbooks('other', {}), /Unavailable/);
  await assert.rejects(host.api.rebindCharWorldbooks('current', { additional: 'gamma' }), /array of names/);
  await assert.rejects(host.api.rebindCharWorldbooks('current', { primary: 3 }), /string or null/);
});

test('setLorebookEntries sends only the patches it was given and answers with the flat list', async () => {
  const { stub, host } = worldbookHost();
  const entries = await host.api.setLorebookEntries('alpha', [{ uid: 9, comment: 'Rune II', sticky: 2 }]);
  assert.deepEqual(stub.calls.map(call => call.method), ['worldbook.entries.update', 'worldbook.entries.read']);
  assert.deepEqual(stub.calls[0].args, { book: 'alpha', entries: [{ uid: 9, comment: 'Rune II', sticky: 2 }] });
  assert.deepEqual(entries.map(entry => entry.uid), [5, 9]);
  assert.equal(entries[0].comment, 'Dragon');
  assert.equal(entries[1].comment, 'Rune II');
  assert.equal(entries[1].sticky, 2);
  // Fields this profile cannot express are forwarded as-is: the bridge is what rejects them.
  await host.api.setLorebookEntries('alpha', [{ uid: 5, filters: ['x'] }]);
  assert.deepEqual(stub.calls[2],
    { method: 'worldbook.entries.update', args: { book: 'alpha', entries: [{ uid: 5, filters: ['x'] }] } });
  await assert.rejects(host.api.setLorebookEntries('alpha', 'nope'), /must be an array/);
  await assert.rejects(host.api.setLorebookEntries('alpha', [{ comment: 'no uid' }]), /uid/);
});

test('updateWorldbookWith runs the updater in the client and replaces the whole nested list', async () => {
  const { stub, host } = worldbookHost();
  const seen = [];
  const result = await host.api.updateWorldbookWith('alpha', async current => {
    seen.push(current.map(entry => entry.uid));
    // The updater works on a copy: nothing reaches the snapshot before the write lands.
    assert.equal(host.state.worldbooks[0].entries[0].content, 'Dragons sleep in caves.');
    current[0].content = 'Rewritten in the browser.';
    current.push({ uid: 30, name: 'Appended' });
    return current;
  }, { render: 'immediate' });
  assert.deepEqual(seen, [[5, 9]]);
  assert.deepEqual(stub.calls.map(call => call.method), ['worldbook.entries.replace']);
  assert.equal(stub.calls[0].args.book, 'alpha');
  assert.deepEqual(stub.calls[0].args.entries.map(entry => entry.uid), [5, 9, 30]);
  assert.equal(stub.calls[0].args.entries[0].content, 'Rewritten in the browser.');
  assert.deepEqual(result.map(entry => entry.uid), [5, 9, 30]);
  assert.equal(result[0].content, 'Rewritten in the browser.');
  await assert.rejects(host.api.updateWorldbookWith('alpha', 'nope'), /Updater must be a function/);
  await assert.rejects(host.api.updateWorldbookWith('missing', current => current), /does not exist/);
  await assert.rejects(host.api.updateWorldbookWith('alpha', current => current, { render: 'eager' }), /render/);
});

test('deleteWorldbookEntries converts predicate matches into their uids', async () => {
  const { stub, host } = worldbookHost();
  const visited = [];
  const result = await host.api.deleteWorldbookEntries('alpha', entry => { visited.push(entry.uid); return entry.name === 'Rune'; });
  assert.deepEqual(visited, [5, 9]);
  assert.deepEqual(stub.calls.at(-1), { method: 'worldbook.entries.delete', args: { book: 'alpha', uids: [9] } });
  assert.deepEqual(result.deleted_entries.map(entry => entry.uid), [9]);
  assert.deepEqual(result.worldbook.map(entry => entry.uid), [5]);
  await host.api.deleteWorldbookEntries('alpha', () => false);
  assert.deepEqual(stub.calls.at(-1).args.uids, []);
  await assert.rejects(host.api.deleteWorldbookEntries('alpha', 'nope'), /Predicate must be a function/);
});

test('createWorldbookEntries reports the entries the bridge appended', async () => {
  const { stub, host } = worldbookHost();
  const payload = [{ name: 'Shadow', strategy: { type: 'selective', keys: ['shadow'] } }, { name: 'Ash' }];
  const created = await host.api.createWorldbookEntries('alpha', payload, { render: 'debounced' });
  assert.deepEqual(stub.calls.map(call => call.method), ['worldbook.entries.create']);
  assert.deepEqual(stub.calls[0].args, { book: 'alpha', entries: payload });
  assert.deepEqual(created.worldbook.map(entry => entry.uid), [5, 9, 20, 21]);
  assert.deepEqual(created.new_entries.map(entry => entry.name), ['Shadow', 'Ash']);
  assert.deepEqual(created.new_entries.map(entry => entry.uid), [20, 21]);
  await assert.rejects(host.api.createWorldbookEntries('alpha', 'nope'), /must be an array/);
});

test('legacy entry creation and deletion answer with the flat result shapes', async () => {
  const { stub, host } = worldbookHost();
  const created = await host.api.createLorebookEntries('alpha',
    [{ comment: 'Extra', content: 'Added.', position: 'at_depth_as_user' }]);
  assert.deepEqual(stub.calls.map(call => call.method), ['worldbook.entries.create', 'worldbook.entries.read']);
  assert.deepEqual(created.new_uids, [20]);
  assert.deepEqual(created.entries.map(entry => entry.uid), [5, 9, 20]);
  assert.equal(created.entries[2].comment, 'Extra');
  assert.equal(created.entries[2].position, 'at_depth_as_user');
  const deleted = await host.api.deleteLorebookEntries('alpha', [5, 4096]);
  assert.equal(deleted.delete_occurred, true);
  assert.deepEqual(deleted.entries.map(entry => entry.uid), [9, 20]);
  assert.deepEqual(stub.calls.slice(2).map(call => call.method), ['worldbook.entries.delete', 'worldbook.entries.read']);
  assert.deepEqual(stub.calls[2].args, { book: 'alpha', uids: [5, 4096] });
  assert.equal((await host.api.deleteLorebookEntries('alpha', [4096])).delete_occurred, false);
  await assert.rejects(host.api.deleteLorebookEntries('alpha', 'nope'), /must be an array/);
  await assert.rejects(host.api.deleteLorebookEntries('alpha', ['5']), /integers/);
});

test('updateLorebookEntriesWith replaces the flat list with the updater result', async () => {
  const { stub, host } = worldbookHost();
  const entries = await host.api.updateLorebookEntriesWith('alpha', async current => {
    current[1].comment = 'Rune III';
    return current;
  });
  assert.deepEqual(stub.calls.map(call => call.method), ['worldbook.entries.read', 'worldbook.entries.replace', 'worldbook.entries.read']);
  assert.deepEqual(stub.calls[1].args.entries.map(entry => entry.comment), ['Dragon', 'Rune III']);
  assert.equal(entries[1].comment, 'Rune III');
  await assert.rejects(host.api.updateLorebookEntriesWith('alpha', 'nope'), /Updater must be a function/);
});

test('world book writes report creation, replacement and deletion through the bridge', async () => {
  const { stub, host } = worldbookHost();
  assert.equal(await host.api.createWorldbook('delta'), true);
  assert.deepEqual(stub.calls.at(-1), { method: 'worldbook.books.create', args: { name: 'delta' } });
  const entries = [{ uid: 2, name: 'Entry', strategy: { type: 'constant', keys: [] } }];
  assert.equal(await host.api.createOrReplaceWorldbook('delta', entries, { render: 'immediate' }), false);
  assert.deepEqual(stub.calls.at(-1).args, { name: 'delta', entries });
  assert.equal(await host.api.deleteWorldbook('delta'), true);
  assert.equal(await host.api.deleteWorldbook('delta'), false);
  await host.api.replaceWorldbook('alpha', [{ uid: 5, name: 'Kept', content: 'Replaced.' }], { render: 'none' });
  assert.deepEqual(stub.calls.at(-1), { method: 'worldbook.entries.replace',
    args: { book: 'alpha', entries: [{ uid: 5, name: 'Kept', content: 'Replaced.' }] } });
  await assert.rejects(host.api.replaceWorldbook('missing', []), /does not exist/);
  await assert.rejects(host.api.replaceWorldbook('alpha', 'nope'), /must be an array/);
  await assert.rejects(host.api.createWorldbook('alpha', 'nope'), /must be an array/);
  await assert.rejects(host.api.replaceWorldbook('alpha', [], { render: 'eager' }), /render/);
});

test('capabilities this profile still lacks keep rejecting or stay absent', () => {
  const { host } = worldbookHost();
  assert.throws(() => host.api.rebindGlobalWorldbooks([]), /Unsupported host capability: rebindGlobalWorldbooks/);
  assert.throws(() => host.api.setWorldbook('alpha', []), /Unsupported host capability: setWorldbook/);
  assert.throws(() => host.api.triggerSlash('/help'), /Unsupported host capability: triggerSlash/);
  // Deliberate compatibility gaps: no rejection stub pretends they are decision points.
  for (const name of ['getChatWorldbookName', 'rebindChatWorldbook', 'getOrCreateChatWorldbook', 'getChatLorebook',
    'setChatLorebook', 'getOrCreateChatLorebook', 'getCurrentCharPrimaryLorebook', 'setCurrentCharLorebooks',
    'getLorebookSettings', 'setLorebookSettings']) assert.equal(host.api[name], undefined, name);
});
