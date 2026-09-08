import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareSchemaScript } from './schema-script.mjs';

test('named exports preserve arbitrary identifiers and registration side effects', async () => {
    for (const name of ['Schema', 'schema', 'actorSchema']) {
        const source = `export const ${name} = { value: 3 }; registerMvuSchema(${name});`;
        let registered;
        await Function('registerMvuSchema', 'return ' + prepareSchemaScript(source))(value => { registered = value; });
        assert.deepEqual(registered, { value: 3 });
    }
});

test('module-looking text stays intact and comments may separate declaration tokens', async () => {
    const source = `// export const Schema = ignored\nexport /* explanation */ const schema = "export const Schema; import remote";\nexport { schema as alias }; registerMvuSchema(schema);`;
    let registered;
    const prepared = prepareSchemaScript(source);
    await Function('registerMvuSchema', 'return ' + prepared)(value => { registered = value; });
    assert.equal(registered, 'export const Schema; import remote');
    assert.equal(prepared.split('\n').length, source.split('\n').length + 1);
    assert.ok(prepared.includes('// export const Schema = ignored'));
});

test('only the pinned helper import is removed; unsupported modules fail before execution', async () => {
    const source = `import { registerMvuSchema } from 'https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js';\nregisterMvuSchema(3);`;
    let registered;
    await Function('registerMvuSchema', 'return ' + prepareSchemaScript(source))(value => { registered = value; });
    assert.equal(registered, 3);
    for (const unsupported of [
        "import 'https://example.invalid/module.js';",
        "export { schema } from 'https://example.invalid/module.js';",
        'export default {};',
        "function later() { return import('https://example.invalid/module.js'); }",
    ]) assert.throws(() => prepareSchemaScript(unsupported));
});


test('dynamic helper imports preserve await order and local shadowing without network access', async () => {
    const source = `let registerMvuSchema;
        try { ({registerMvuSchema} = await import('https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js')); }
        catch (_) { ({registerMvuSchema} = await import('https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js')); }
        await Promise.resolve(); registerMvuSchema({value: 7});`;
    const values = [];
    await Function('registerMvuSchema', 'return ' + prepareSchemaScript(source))(value => values.push(value));
    assert.deepEqual(values, [{value: 7}]);
    const aliased = `import {registerMvuSchema as install} from 'https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js'; install(4);`;
    await Function('registerMvuSchema', 'return ' + prepareSchemaScript(aliased))(value => values.push(value));
    assert.equal(values[1], 4);
});

test('unknown and computed imports are rejected before any source effect', () => {
    for (const source of [
        `registerMvuSchema(1); await import('https://example.invalid/remote.js');`,
        `const url='https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js'; await import(url);`,
        `function later(){return import('https://example.invalid/remote.js');}`,
    ]) assert.throws(() => prepareSchemaScript(source), /Unmapped card import/);
});
