import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { parse } from 'acorn';

// Inventory, not a conformance score. No author programs or upstream modules execute.
const here = fileURLToPath(new URL('.', import.meta.url));
const upstream = process.argv[2];
if (!upstream) throw new Error('Usage: node audit-contract.mjs <helper-checkout> [--check]');
const root = resolve(upstream), check = process.argv.includes('--check');
const manifest = JSON.parse(await readFile(resolve(here, 'compatibility-domains.json'), 'utf8'));
const hash = text => createHash('sha256').update(text).digest('hex');
const files = new Map();
async function source(path) {
  const text = await readFile(resolve(root, path), 'utf8');
  files.set(path, hash(text)); return text;
}
function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (node.type) visit(node);
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach(child => walk(child, visit));
    else if (value && typeof value === 'object') walk(value, visit);
  }
}
const hostSource = await readFile(resolve(here, 'src/host.mjs'), 'utf8');
const bindings = new Map();
const tree = parse(hostSource, { ecmaVersion: 'latest', sourceType: 'module' }), objects = new Map();
walk(tree, node => {
  if (node.type !== 'VariableDeclarator') return;
  const value = node.init?.callee?.property?.name === 'freeze' ? node.init.arguments[0] : node.init;
  if (value?.type === 'ObjectExpression') objects.set(node.id.name, value);
});
const stub = node => node?.type === 'CallExpression' && node.callee.name === 'unsupported';
function properties(node, prefix = '') {
  for (const property of node.properties ?? []) {
    if (property.type !== 'Property' || property.computed) continue;
    const name = prefix + (property.key.name ?? property.key.value);
    bindings.set(name, stub(property.value) ? 'rejecting-stub' : 'binding-present');
    const value = property.value.type === 'Identifier' ? objects.get(property.value.name) : property.value;
    if (value?.type === 'ObjectExpression') properties(value, name + '.');
  }
}
walk(tree, node => {
  if (node.type === 'VariableDeclarator' && node.id.name === 'api') properties(node.init);
  if (node.type === 'AssignmentExpression' && node.left.type === 'MemberExpression' && node.left.object.name === 'api' && !node.left.computed) {
    const name = node.left.property.name;
    bindings.set(name, stub(node.right) ? 'rejecting-stub' : 'binding-present');
    if (node.right.type === 'ObjectExpression') properties(node.right, name + '.');
  }
  if (node.type === 'ForOfStatement' && node.right.type === 'ArrayExpression') {
    let rejects = false;
    walk(node.body, child => { if (child.type === 'AssignmentExpression' && child.left.object?.name === 'api' && stub(child.right)) rejects = true; });
    if (rejects) for (const item of node.right.elements) if (typeof item?.value === 'string') bindings.set(item.value, 'rejecting-stub');
  }
});
const binding = name => bindings.get(name) ?? (name === 'TavernHelper' ? 'installed-by-parent' : 'absent');
const mapped = new Set(), domains = [];
for (const domain of manifest.domains) {
  const entries = new Map(), dependencies = new Set(), implementations = [];
  for (const file of domain.declarations) {
    if (mapped.has(file)) throw new Error('Duplicate declaration domain: ' + file);
    mapped.add(file);
    const path = '@types/' + file, text = await source(path);
    for (const match of text.matchAll(/^declare\s+(function|const|class|namespace)\s+(\w+)/gm)) {
      const name = match[2], existing = entries.get(name);
      if (existing) { existing.declarationCount++; continue; }
      entries.set(name, { name, kind: match[1], declaration: path, line: text.slice(0, match.index).split('\n').length,
        declarationCount: 1, playerBinding: binding(name) });
    }
    // Include first-level exported object members; namespace type fields are not APIs.
    for (const object of text.matchAll(/^declare const (\w+): \{([\s\S]*?)^\};/gm)) {
      for (const member of object[2].matchAll(/^  (?:readonly )?(\w+)\??\s*:/gm)) {
        const name = object[1] + '.' + member[1];
        entries.set(name, { name, kind: 'object-member', declaration: path,
          playerBinding: binding(name) });
      }
    }
  }
  for (const path of domain.implementations) {
    const text = await source(path);
    for (const match of text.matchAll(/(?:from\s+|import\s*)['"]([^'"]+)['"]/g)) dependencies.add(match[1]);
    implementations.push(path);
  }
  domains.push({ ...domain, implementations, dependencies: [...dependencies].sort(), entries: [...entries.values()].sort((a, b) => a.name.localeCompare(b.name, 'en')) });
}
for (const folder of ['function', 'iframe']) {
  for (const name of await readdir(resolve(root, '@types', folder))) {
    const path = folder + '/' + name;
    if (name.endsWith('.d.ts') && path !== 'function/index.d.ts' && !mapped.has(path)) throw new Error('Unassigned declaration file: ' + path);
  }
}
const facadeSource = await source('@types/function/index.d.ts');
const facade = [...facadeSource.matchAll(/readonly\s+(\w+):\s*typeof\s+(\w+)/g)]
  .map(match => ({ name: match[1], declarationTarget: match[2], playerBinding: binding(match[1]) }));
// Actual installed names include historical aliases not present in the declaration facade.
const registration = await source('src/function/index.ts');
await source('src/iframe/predefine.js');
const registrationBody = registration.slice(registration.indexOf('function getTavernHelper()'));
const registered = [...registrationBody.matchAll(/^    ([A-Za-z]\w*)(?:,|:\s*(\w+),)/gm)]
  .map(match => ({ name: match[1], target: match[2] ?? match[1], playerBinding: binding(match[1]) }));
const bound = [...registrationBody.matchAll(/^      (_\w+),/gm)]
  .map(match => ({ name: match[1].slice(1), target: match[1], playerBinding: binding(match[1].slice(1)) }));
const catalog = { schemaVersion: 1, helperCommit: execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  meaning: 'Binding presence is not compatibility. Domains, namespace members, aliases and source dependencies are inventoried separately. No conformance percentage is computed.',
  hostSourceSha256: hash(hostSource),
  playerSources: Object.fromEntries(await Promise.all(['host', 'session', 'parent', 'shell'].map(async name =>
    ['src/' + name + '.mjs', hash(await readFile(resolve(here, 'src/' + name + '.mjs'), 'utf8'))]))),
  sources: Object.fromEntries([...files].sort(([a], [b]) => a.localeCompare(b, 'en'))),
  domains, declarationFacade: facade, runtimeRegistrations: registered, iframeBoundRegistrations: bound };
const output = resolve(here, 'compatibility-catalog.json'), bytes = JSON.stringify(catalog, null, 2) + '\n';
if (check) {
  if (await readFile(output, 'utf8') !== bytes) throw new Error('Catalog differs from the inspected sources; regenerate and review the changes.');
} else await writeFile(output, bytes);
console.log(JSON.stringify({ mode: check ? 'check' : 'write', domains: domains.length, declarationFiles: mapped.size,
  entries: domains.reduce((sum, d) => sum + d.entries.length, 0), declarationFacade: facade.length,
  runtimeRegistrations: registered.length, iframeBoundRegistrations: bound.length,
  output: relative(here, output) }));
