import { parse } from 'acorn';
import postcss from 'postcss';
import valueParser from 'postcss-value-parser';

export function cssResources(source, replacements = {}) {
  const root = postcss.parse(source), urls = [];
  const decode = text => text.replace(/\\([0-9a-f]{1,6})\s?|\\([^\r\n])/gi, (_, hex, literal) => hex ? String.fromCodePoint(parseInt(hex, 16) || 0xfffd) : literal);
  const process = (text, isImport) => {
    const value = valueParser(text);
    value.walk(node => {
      let target;
      if (node.type === 'function' && node.value.toLowerCase() === 'url') target = node.nodes.find(n => n.type === 'string' || n.type === 'word');
      else if (isImport && node === value.nodes[0] && node.type === 'string') target = node;
      if (target) {
        const url = decode(target.value); urls.push(url);
        if (Object.hasOwn(replacements, url)) { target.type = 'string'; target.quote = '"'; target.value = replacements[url].replaceAll('"', '\\"'); }
        return false;
      }
    });
    return value.toString();
  };
  root.walkDecls(node => { node.value = process(node.value, false); });
  root.walkAtRules('import', node => { node.params = process(node.params, true); });
  return JSON.stringify({ urls, text: root.toString() });
}

const loader = /^https:\/\/(?:cdn|testingcf|fastly)\.jsdelivr\.net\/gh\/MagicalAstrogy\/MagVarUpdate\/artifact\/bundle\.js$/;
const schemaHelper = /^https:\/\/(?:cdn|testingcf)\.jsdelivr\.net\/gh\/StageDog\/tavern_resource\/dist\/util\/mvu_zod\.js$/;

export function inspect(source) {
  const tree = parse(source, { ecmaVersion: 'latest', sourceType: 'module' });
  const imports = [];
  walk(tree, node => {
    if (node.type === 'ImportDeclaration' || node.type === 'ExportAllDeclaration' || node.type === 'ExportNamedDeclaration') {
      if (node.source) imports.push(node.source.value);
    }
    if (node.type === 'ImportExpression' && node.source.type === 'Literal') imports.push(node.source.value);
  });
  const body = tree.body.filter(node => node.type !== 'EmptyStatement');
  if (body.length === 1 && body[0].type === 'ImportDeclaration' && body[0].specifiers.length === 0 && loader.test(body[0].source.value))
    return 'mvu-loader';
  if (imports.some(url => schemaHelper.test(url))) {
    if (!imports.every(url => schemaHelper.test(url))) throw new Error('Schema module has dependencies outside the QuickJS profile');
    return 'mvu-schema';
  }
  if (imports.some(url => loader.test(url))) throw new Error('MVU loader mixed with author effects cannot be split across engines');
  return 'browser';
}

/** Parse only, never evaluate downloaded source. Ranges let Kotlin preserve redirect bases. */
export function imports(source) {
  const tree = parse(source, { ecmaVersion: 'latest', sourceType: 'module', allowReturnOutsideFunction: true });
  const found = [];
  walk(tree, node => {
    if (['ImportDeclaration', 'ExportNamedDeclaration', 'ExportAllDeclaration'].includes(node.type) && node.source)
      found.push({ start: node.source.start, end: node.source.end, value: node.source.value });
    if (node.type === 'ImportExpression') {
      if (node.source.type === 'Literal' && typeof node.source.value === 'string')
        found.push({ start: node.source.start, end: node.source.end, value: node.source.value });
      else found.push({ start: node.source.start, end: node.source.end, dynamic: true });
    }
    if (node.type === 'MetaProperty' && node.meta.name === 'import' && node.property.name === 'meta')
      found.push({ start: node.start, end: node.end, meta: true });
  });
  return JSON.stringify(found);
}

function walk(tree, visit) {
  const pending = [tree];
  while (pending.length) {
    const node = pending.pop(); visit(node);
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) pending.push(...value.filter(v => v && typeof v.type === 'string'));
      else if (value && typeof value.type === 'string') pending.push(value);
    }
  }
}
