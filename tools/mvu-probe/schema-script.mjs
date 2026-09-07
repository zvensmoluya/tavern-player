import { parse } from 'acorn';

const schemaHelper = 'https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js';

// These scripts register their schema by side effect; no module namespace is consumed.
// Parse declarations so comments, strings, and the author's variable names stay intact.
export function prepareSchemaScript(source) {
    const tree = parse(source, { ecmaVersion: 2022, sourceType: 'module' });
    const removals = [];
    for (const node of tree.body) {
        if (node.type === 'ImportDeclaration') {
            if (node.source.value !== schemaHelper || node.specifiers.length !== 1 ||
                node.specifiers[0].type !== 'ImportSpecifier' ||
                node.specifiers[0].imported.name !== 'registerMvuSchema' ||
                node.specifiers[0].local.name !== 'registerMvuSchema') {
                throw new Error('Unmapped card import');
            }
            removals.push([node.start, node.end]);
        } else if (node.type === 'ExportNamedDeclaration' && !node.source) {
            removals.push([node.start, node.declaration ? node.start + 'export'.length : node.end]);
        } else if (node.type.startsWith('Export')) {
            throw new Error('Unsupported card module export: only local named exports are supported');
        }
    }
    for (const [start, end] of removals.reverse()) {
        source = source.slice(0, start) + source.slice(start, end).replace(/[^\r\n]/g, ' ') + source.slice(end);
    }
    // Reject remaining module-only syntax (including dynamic imports) before executing any code.
    const script = parse(source, { ecmaVersion: 2022, sourceType: 'script' });
    const pending = [script];
    while (pending.length) {
        const node = pending.pop();
        if (node.type === 'ImportExpression') throw new Error('Unmapped card import');
        for (const value of Object.values(node)) {
            if (Array.isArray(value)) pending.push(...value.filter(item => item && typeof item.type === 'string'));
            else if (value && typeof value.type === 'string') pending.push(value);
        }
    }
    return source;
}
