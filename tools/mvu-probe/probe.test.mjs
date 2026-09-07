import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { ProbeHost } from './host.mjs';

const fixture = JSON.parse(await readFile(new URL('./fixtures/state-card.json', import.meta.url), 'utf8'));
const op = (operation, path, value) => ({ op: operation, path, ...(operation === 'remove' ? {} : { value }) });

test('upstream initializes every greeting, executes four operations, and applies Zod defaults/transforms', async () => {
    const host = new ProbeHost(fixture);
    await host.initialize();
    assert.equal(host.state().stat_data.actor.outfit.top, 'none');
    host.select(0, 1);
    assert.equal(host.state().stat_data.days, 3);
    host.select(0, 0);
    const initial = host.state();
    const result = await host.reply([
        op('replace', '/location', 'market'),
        op('insert', '/inventory/tea', { quantity: '3' }),
        op('delta', '/inventory/tea/quantity', -1),
        op('insert', '/wardrobe/coat', {}),
        op('replace', '/actor/outfit/top', 'coat'),
        op('delta', '/actor/affinity', 1000),
    ]);
    assert.equal(result.stat_data.location, 'market');
    assert.deepEqual(result.stat_data.inventory.tea, { quantity: 2, description: 'No description' });
    assert.deepEqual(result.stat_data.wardrobe.coat, { slot: 'top', description: 'No description' });
    assert.equal(result.stat_data.actor.outfit.top, 'coat');
    assert.equal(result.stat_data.actor.affinity, 400);
    assert.equal(result.schema, '没有用别管这个'); // Actual upstream MVU Zod sentinel, not a Player schema.
    assert.equal(host.chat.at(-1).mes.endsWith('<StatusPlaceHolderImpl/>'), true);
    assert.deepEqual(host.state(0), initial);
    const removed = await host.reply([op('remove', '/inventory/tea'), op('remove', '/wardrobe/coat')]);
    assert.deepEqual(removed.stat_data.inventory, {});
    assert.deepEqual(removed.stat_data.wardrobe, {});
    assert.ok(host.events.includes('mag_command_parsed_for_zod'));
    assert.equal(host.diagnostics.some(d => ['error', 'warn', 'warning'].includes(d.level)), false);
});

test('regeneration starts from previous message, candidate selection and JSON restore preserve variables', async () => {
    const host = new ProbeHost(fixture);
    await host.initialize();
    await host.reply([op('delta', '/days', 1)]);
    await host.regenerate([op('delta', '/days', 5)]);
    assert.equal(host.state().stat_data.days, 5);
    host.select(1, 0);
    assert.equal(host.state().stat_data.days, 1);
    const restored = new ProbeHost({ ...fixture, savedChat: JSON.parse(host.save()) });
    assert.deepEqual(restored.state(), host.state());
    await restored.reply([op('delta', '/days', 2)]);
    assert.equal(restored.state().stat_data.days, 3);
    assert.equal(restored.state(1).stat_data.days, 1);
});

test('MVU Zod skips invalid commands individually and protects underscore paths', async () => {
    const host = new ProbeHost(fixture);
    await host.initialize();
    const result = await host.reply([
        op('insert', '/wardrobe/bad', { slot: 'invalid' }),
        op('replace', '/_fixed', 'changed'),
        op('delta', '/location', 2),
        op('delta', '/days', 1),
    ]);
    assert.deepEqual(result.stat_data.wardrobe, {});
    assert.equal(result.stat_data._fixed, 'keep');
    assert.equal(result.stat_data.location, 'clinic');
    assert.equal(result.stat_data.days, 1);
    assert.ok(host.diagnostics.some(d => d.level === 'warning'));
});

test('JSON Pointer escaping keeps punctuation in dynamic keys', async () => {
    const host = new ProbeHost(fixture);
    await host.initialize();
    const result = await host.reply([
        op('insert', '/inventory/a.b~1c~0d', {}),
        op('delta', '/inventory/a.b~1c~0d/quantity', 2),
    ]);
    assert.equal(result.stat_data.inventory['a.b/c~d'].quantity, 3);
    assert.equal(Object.keys(result.stat_data.inventory).length, 1);
});
