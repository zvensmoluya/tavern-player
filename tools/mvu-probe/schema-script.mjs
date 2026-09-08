import { parse } from 'acorn';

const helpers = new Set([
    'https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js',
    'https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js',
]);

// Resolve known module identities to the bundled helper, never to a network loader.
// Validate the whole tree before running effects, including imports in uncalled functions.
export function prepareSchemaScript(source) {
    const tree = parse(source, { ecmaVersion: 2022, sourceType: 'module' });
    let binding = '__playerMvuModule';
    while (source.includes(binding)) binding += '_';
    const edits = [];
    for (const node of tree.body) {
        if (node.type === 'ImportDeclaration') {
            if (!helpers.has(node.source.value) || node.specifiers.length !== 1 ||
                node.specifiers[0].type !== 'ImportSpecifier' ||
                node.specifiers[0].imported.name !== 'registerMvuSchema') {
                throw new Error('Unmapped card import');
            }
            edits.push([node.start, node.end,
                `const ${node.specifiers[0].local.name} = ${binding}.registerMvuSchema;`]);
        } else if (node.type === 'ExportNamedDeclaration' && !node.source) {
            edits.push([node.start, node.declaration ? node.start + 'export'.length : node.end, '']);
        } else if (node.type.startsWith('Export')) {
            throw new Error('Unsupported card module export: only local named exports are supported');
        }
    }
    const pending = [tree];
    while (pending.length) {
        const node = pending.pop();
        if (node.type === 'ImportExpression') {
            if (node.source.type !== 'Literal' || !helpers.has(node.source.value))
                throw new Error('Unmapped card import');
            edits.push([node.start, node.end, `Promise.resolve(${binding})`]);
        }
        for (const value of Object.values(node)) {
            if (Array.isArray(value)) pending.push(...value.filter(item => item && typeof item.type === 'string'));
            else if (value && typeof value.type === 'string') pending.push(value);
        }
    }
    for (const [start, end, replacement] of edits.sort((a, b) => b[0] - a[0])) {
        const lines = source.slice(start, end).match(/\r\n|\r|\n/g)?.join('') ?? '';
        source = source.slice(0, start) + replacement + lines + source.slice(end);
    }
    // Capture the host binding outside the source lexical scope (which may declare its own variable).
    // Await this expression before initializing MVU; top-level await and try/catch keep their order.
    return `(async (${binding}) => {${source}\n})(Object.freeze({registerMvuSchema}))`;
}
