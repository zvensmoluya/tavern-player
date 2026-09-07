import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareSchemaScript } from './schema-script.mjs';

test('named exports preserve arbitrary identifiers and registration side effects', () => {
    for (const name of ['Schema', 'schema', 'actorSchema']) {
        const source = `export const ${name} = { value: 3 }; registerMvuSchema(${name});`;
        let registered;
        Function('registerMvuSchema', prepareSchemaScript(source))(value => { registered = value; });
        assert.deepEqual(registered, { value: 3 });
    }
});

test('module-looking text stays intact and comments may separate declaration tokens', () => {
    const source = `// export const Schema = ignored\nexport /* explanation */ const schema = "export const Schema; import remote";\nexport { schema as alias }; registerMvuSchema(schema);`;
    let registered;
    const prepared = prepareSchemaScript(source);
    Function('registerMvuSchema', prepared)(value => { registered = value; });
    assert.equal(registered, 'export const Schema; import remote');
    assert.equal(prepared.split('\n').length, source.split('\n').length);
    assert.ok(prepared.startsWith('// export const Schema = ignored'));
});

test('only the pinned helper import is removed; unsupported modules fail before execution', () => {
    const source = `import { registerMvuSchema } from 'https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js';\nregisterMvuSchema(3);`;
    let registered;
    Function('registerMvuSchema', prepareSchemaScript(source))(value => { registered = value; });
    assert.equal(registered, 3);
    for (const unsupported of [
        "import 'https://example.invalid/module.js';",
        "export { schema } from 'https://example.invalid/module.js';",
        'export default {};',
        "function later() { return import('https://example.invalid/module.js'); }",
    ]) assert.throws(() => prepareSchemaScript(unsupported));
});
