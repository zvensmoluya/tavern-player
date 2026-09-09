import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from '../src/host.mjs';
import { inspect, imports, cssResources } from '../src/programs.mjs';

const snapshot = () => ({ revision: 'r0', conversationId: 'c', draft: '', chatVariables: {}, scriptVariables: {}, mvu: null,
  worldbooks: [], messages: [
    { id: 'm0', turnId: 't0', variantId: 'v0', message_id: 0, role: 'assistant', name: 'Actor', message: 'Opening', is_hidden: false,
      data: {}, extra: {}, swipe_id: 0, swipes: ['Opening', 'Alternative'], swipes_data: [{}, {}], swipes_info: [{}, {}] },
  ] });

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
  assert.deepEqual(host.api.getChatMessages(5), []);
  assert.deepEqual(host.api.getChatMessages(0, { role: 'user' }), []);
  assert.throws(() => host.api.getChatMessages(0, { unknown: true }), /Unsupported/);
});

test('scope errors are explicit rather than silently becoming chat variables', () => {
  const host = createHost({ initial: snapshot(), actor: {}, request: async () => ({}) });
  assert.throws(() => host.api.getVariables({ type: 'global' }), /Unsupported/);
  assert.throws(() => host.api.replaceVariables({}, { type: 'script' }), /Unavailable/);
  assert.throws(() => host.api.getVariables({ type: 'chat', message_id: 0 }), /Invalid/);
  assert.throws(() => host.api.createChatMessages([]), /Unsupported/);
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
