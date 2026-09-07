import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { ProbeHost } from './host.mjs';

const path = process.argv[2];
if (!path) throw new Error('Usage: npm run probe -- <audited-local-card.png>');
const bytes = await readFile(path);
const sha256 = createHash('sha256').update(bytes).digest('hex');
// This is a local experiment that executes an audited schema script, not a general importer.
const expected = 'fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe';
assert.equal(sha256, expected, 'Only the audited C-04 fixture is accepted');
assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
let card;
for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.subarray(offset + 4, offset + 8).toString('ascii');
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    offset += length + 12;
    if (type === 'tEXt' && data.subarray(0, 6).toString('ascii') === 'chara\0') {
        card = JSON.parse(Buffer.from(data.subarray(6).toString('ascii'), 'base64').toString('utf8')).data;
        break;
    }
}
assert.ok(card);
const scripts = card.extensions.tavern_helper.scripts.filter(script => script.enabled);
const schemaScript = scripts.find(script => script.content.includes('registerMvuSchema'))?.content;
assert.ok(schemaScript);
const fixture = {
    schemaScript,
    entries: card.character_book.entries,
    greetings: [card.first_mes, ...card.alternate_greetings],
};
const host = new ProbeHost(fixture);
await host.initialize();
const initial = host.state(0);
assert.ok(initial?.stat_data);
const schema = initial.stat_data;
const inventoryKey = '物品栏';
const wardrobeKey = Object.keys(schema).find(key => key.endsWith('衣橱'));
const actorKey = Object.keys(schema).find(key => key.endsWith('状态'));
assert.ok(wardrobeKey && actorKey && schema[inventoryKey]);
const op = (operation, path, value) => ({ op: operation, path, ...(operation === 'remove' ? {} : { value }) });
const before = schema[actorKey]['亲密度'];
const first = await host.reply([
    op('replace', '/地点', '市场'),
    op('insert', `/${inventoryKey}/样本物品`, { 数量: '3' }),
    op('delta', `/${inventoryKey}/样本物品/数量`, -1),
    op('insert', `/${wardrobeKey}/样本衣物`, {}),
    op('replace', `/${actorKey}/服装/上衣`, '样本衣物'),
    op('delta', `/${actorKey}/亲密度`, 5),
]);
assert.equal(first.stat_data['地点'], '市场');
assert.deepEqual(first.stat_data[inventoryKey]['样本物品'], { 数量: 2, 描述: '无描述' });
assert.deepEqual(first.stat_data[wardrobeKey]['样本衣物'], { 部位: '上衣', 描述: '无描述' });
assert.equal(first.stat_data[actorKey]['服装']['上衣'], '样本衣物');
assert.equal(first.stat_data[actorKey]['亲密度'], Math.min(before + 5, 400));
assert.ok(host.chat.at(-1).mes.endsWith('<StatusPlaceHolderImpl/>'));
assert.deepEqual(host.state(0), initial);
const second = await host.reply([
    op('remove', `/${inventoryKey}/样本物品`),
    op('remove', `/${wardrobeKey}/样本衣物`),
    op('delta', `/${actorKey}/亲密度`, 1000),
]);
assert.equal(second.stat_data[inventoryKey]['样本物品'], undefined);
assert.equal(second.stat_data[wardrobeKey]['样本衣物'], undefined);
assert.equal(second.stat_data[actorKey]['亲密度'], 400);
await host.regenerate([op('delta', `/${inventoryKey}/样本物品/数量`, 3)]);
assert.equal(host.state().stat_data[inventoryKey]['样本物品'].数量, 5);
host.select(2, 0);
assert.deepEqual(host.state(), second);
const restored = new ProbeHost({ ...fixture, savedChat: JSON.parse(host.save()) });
assert.deepEqual(restored.state(), second);
assert.equal(host.diagnostics.filter(item => ['error', 'warn', 'warning'].includes(item.level)).length, 0);

const report = {
    sample: 'C-04', sha256,
    upstream: JSON.parse(await readFile(new URL('./upstream-lock.json', import.meta.url), 'utf8')),
    checks: [
        'original-initvar-and-schema', 'replace', 'delta', 'insert', 'remove',
        'dynamic-records', 'numeric-coercion', 'record-defaults', 'clamp',
        'automatic-status-placeholder', 'previous-message-unchanged',
        'regenerate-from-previous-message', 'candidate-switch', 'json-restore',
    ],
    hostCalls: [...host.calls].sort(),
    events: [...new Set(host.events)],
    errors: 0,
    scope: 'Node research host; no Android, model request, EJS or native rendering verification',
};
await writeFile(new URL('./build/card-report.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
// Optional local-only JVM/Android fixture. Never included in the main application's assets.
await writeFile(new URL('./build/android-assets/mvu/c04-program.json', import.meta.url), JSON.stringify(fixture) + '\n');
console.log(JSON.stringify({ sample: report.sample, sha256, checks: report.checks, errors: report.errors }, null, 2));
