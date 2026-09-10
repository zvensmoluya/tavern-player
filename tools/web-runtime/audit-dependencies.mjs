// 原卡作者程序的静态依赖核查：只读解码原件、用 acorn 解析作者 JS、与兼容目录交叉引用。
// 不执行卡内程序，不联网，不请求模型。
// 用法: node audit-dependencies.mjs <samples-dir> [--out <path>]
// 默认输出: tools/web-runtime/build/dependency-audit.json
//
// 三档口径：
//   ① referenced        —— 源码里引用的 API（AST 静态扫描）
//   ② runtime_evidence  —— 既有本地探测产物中实际执行到的 API（可能过期，逐条注明来源）
//   ③ blocking_gaps     —— ①中绑定为 absent / rejecting-stub 的名字（浏览器程序），运行时也执行到的优先列为
//                          runtime-observed，其余为 source-reference-only（仅推断，未逐条运行验证）
//
// 入库文件不出现角色卡标题、人物名、作者名或含原名的文件名；素材只用 SHA-256 与编号追溯。

import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parse } from 'acorn';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');

// 已知样本编号 ↔ 原件 SHA-256。C-01/C-02 为夹具登记的历史原件，本机通常不在 source/。
const SAMPLES = [
  { id: 'C-01', sha256: '1945abd1e2368ec332399830cd85c62be4a9a527f0c43dae6f34db9c9b3b2e1a' },
  { id: 'C-02', sha256: 'b7cf04e3198ffc3a6f9ebed5c398faf7a9066e49dc8887896db5681a0d1498eb' },
  { id: 'C-03', sha256: '0d9f771474cab7f170f33700e9a0db6b87df96451a4da473c0cfa9f8b70e8c22' },
  { id: 'C-04', sha256: 'fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe' },
  { id: 'C-05', sha256: '7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32' },
  { id: 'C-06', sha256: '8f24972a97e9cb357e105e5d7d101a7ec3b7ff5c6cc0f98a4024ac94a895583f' },
  { id: 'C-07', sha256: '0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0' },
];
const ID_BY_SHA = new Map(SAMPLES.map(s => [s.sha256, s.id]));

// 既有本地产物（可选）。读取失败或不存在时对应证据为空，不编造。
const RUNTIME_ARTIFACTS = [
  { key: 'mvu-probe', file: 'tools/mvu-probe/build/card-report.json',
    note: '既有本地产物；Node 研究宿主运行卡内引用的外部 MVU 程序时观察到的宿主调用' },
  { key: 'card-probe', file: 'tools/card-probe/build/survey-v2.json',
    note: '既有本地产物；模拟宿主 + 无网络浏览器下加载 Regex 替换页面时的调用记录' },
];

const relative = target => {
  const rel = path.relative(repoRoot, target).replaceAll('\\', '/');
  return rel.startsWith('..') ? target.replaceAll('\\', '/') : rel;
};

// ---------- 原件解码 ----------

/** 读取 PNG 的 tEXt 块，返回 { keyword: text } */
function readPngTextChunks(buf) {
  const chunks = {};
  let offset = 8;
  while (offset + 12 <= buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('latin1', offset + 4, offset + 8);
    if (type === 'tEXt') {
      const data = buf.subarray(offset + 8, offset + 8 + length);
      const split = data.indexOf(0);
      if (split >= 0) chunks[data.toString('latin1', 0, split)] = data.toString('latin1', split + 1);
    }
    if (type === 'IEND') break;
    offset += 12 + length;
  }
  return chunks;
}

/** 角色卡 PNG 把 JSON 以 base64 存在 chara / ccv3 文本块里 */
function decodePngCard(file) {
  const chunks = readPngTextChunks(fs.readFileSync(file));
  for (const key of ['ccv3', 'chara']) {
    if (!chunks[key]) continue;
    try { return JSON.parse(Buffer.from(chunks[key], 'base64').toString('utf8')); }
    catch { /* 继续尝试下一个块 */ }
  }
  return null;
}

/** 兼容 { spec, data } 包装与直接的 JSON 导出 */
function cardOf(raw) {
  return raw?.data ?? raw;
}

// ---------- 作者 JS 来源定位 ----------

const SCRIPT_TAG = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;

/** tavern_helper 在 V3 里是对象，在部分导出里是 [[key, value], ...] 的 Map 序列化 */
function helperScripts(extensions) {
  const helper = extensions?.tavern_helper;
  if (!helper) return [];
  let shape = helper;
  if (Array.isArray(helper) && helper.every(item => Array.isArray(item) && item.length === 2)) {
    shape = Object.fromEntries(helper);
  }
  const scripts = shape.scripts;
  if (Array.isArray(scripts)) return scripts.map((script, index) => ({ id: `script:${index}`, code: script?.content, enabled: script?.enabled }));
  if (scripts && typeof scripts === 'object') {
    return Object.keys(scripts).sort().map((key, index) => ({ id: `script:${index}`, code: scripts[key]?.content, enabled: scripts[key]?.enabled }));
  }
  // 旧格式：tavern_helper 自身就是编号脚本映射
  const values = Object.values(shape);
  if (values.length > 0 && values.every(value => value && typeof value === 'object' && typeof value.content === 'string')) {
    return values.map((script, index) => ({ id: `script:${index}`, code: script.content, enabled: script.enabled }));
  }
  return [];
}

/** 收集卡内可能承载作者 JS 的文本位置；行号以解析出的脚本块自身为准 */
function scriptBlocks(card, warnings) {
  const blocks = [];
  for (const script of helperScripts(card.extensions)) {
    if (typeof script.code === 'string' && script.code.trim()) blocks.push({ id: script.id, code: script.code });
    if (script.enabled === false) warnings.push(`${script.id} 在卡内标记为 disabled，仍参与静态扫描`);
  }
  const htmlSources = [];
  if (typeof card.first_mes === 'string') htmlSources.push({ id: 'first_mes', html: card.first_mes });
  (card.alternate_greetings ?? []).forEach((greeting, index) => {
    if (typeof greeting === 'string') htmlSources.push({ id: `alternate_greetings:${index}`, html: greeting });
  });
  if (typeof card.mes_example === 'string') htmlSources.push({ id: 'mes_example', html: card.mes_example });
  (card.extensions?.regex_scripts ?? []).forEach((rule, index) => {
    if (typeof rule?.replaceString !== 'string' || !rule.replaceString) return;
    if (rule.disabled) warnings.push(`regex_replace:${index} 在卡内标记为 disabled，仍参与静态扫描`);
    htmlSources.push({ id: `regex_replace:${index}`, html: rule.replaceString });
  });
  for (const source of htmlSources) {
    let found = 0;
    for (const match of source.html.matchAll(SCRIPT_TAG)) {
      found++;
      const attributes = match[1] ?? '';
      if (/\bsrc\s*=/i.test(attributes)) continue; // 外部脚本只在 runtime/网络路径，不在此解析
      const type = (attributes.match(/\btype\s*=\s*["']?([^"'>\s]+)/i) ?? [])[1];
      if (type && !/(java|ecma)script|module/i.test(type)) {
        warnings.push(`${source.id}#script 跳过非 JS 脚本类型`);
        continue;
      }
      if (match[2].trim()) blocks.push({ id: `${source.id}#script`, code: match[2] });
    }
    if (found === 0 && /<script\b/i.test(source.html)) warnings.push(`${source.id} 含 <script 但未提取到脚本块`);
  }
  return blocks;
}

// ---------- AST 扫描 ----------

function walk(node, visit, parent = null) {
  if (!node || typeof node !== 'object') return;
  if (node.type) visit(node, parent);
  for (const [key, value] of Object.entries(node)) {
    if (key === 'loc' || key === 'start' || key === 'end') continue;
    if (Array.isArray(value)) value.forEach(child => walk(child, visit, node));
    else if (value && typeof value === 'object' && value.type) walk(value, visit, node);
  }
}

function addPatternNames(node, names) {
  if (!node) return;
  if (node.type === 'Identifier') names.add(node.name);
  else if (node.type === 'ObjectPattern') node.properties.forEach(property => addPatternNames(property.value ?? property.argument, names));
  else if (node.type === 'ArrayPattern') node.elements.forEach(element => addPatternNames(element, names));
  else if (node.type === 'AssignmentPattern') addPatternNames(node.left, names);
  else if (node.type === 'RestElement') addPatternNames(node.argument, names);
}

/** 文件内所有声明的绑定名（保守近似：同名的自由全局会被一起过滤） */
function declaredNames(tree) {
  const names = new Set();
  walk(tree, node => {
    if (node.type === 'VariableDeclarator') addPatternNames(node.id, names);
    else if (node.type === 'FunctionDeclaration') { if (node.id) names.add(node.id.name); node.params.forEach(param => addPatternNames(param, names)); }
    else if (node.type === 'ClassDeclaration' && node.id) names.add(node.id.name);
    else if (node.type === 'FunctionExpression' || node.type === 'ArrowFunctionExpression') node.params.forEach(param => addPatternNames(param, names));
    else if (node.type === 'CatchClause') addPatternNames(node.param, names);
    else if (node.type === 'ImportDeclaration') node.specifiers.forEach(specifier => names.add(specifier.local.name));
  });
  return names;
}

/** 稳定排序 "块 id:行号"；块 id 自身可能含 ':' */
const compareLocation = (a, b) => {
  const parse = value => {
    const split = value.lastIndexOf(':');
    return { script: value.slice(0, split), line: Number(value.slice(split + 1)) };
  };
  const left = parse(a), right = parse(b);
  return left.script.localeCompare(right.script, 'en') || left.line - right.line;
};

const IDENTIFIER_PATH = node => {
  if (node?.type === 'Identifier') return node.name;
  if (node?.type === 'MemberExpression' && !node.computed && node.property.type === 'Identifier') {
    const base = IDENTIFIER_PATH(node.object);
    if (base) return `${base}.${node.property.name}`;
  }
  return null;
};

const BUILTIN_GLOBALS = new Set([
  'Object', 'Array', 'String', 'Number', 'Boolean', 'BigInt', 'Symbol', 'Function', 'Date', 'RegExp', 'Error', 'TypeError',
  'RangeError', 'SyntaxError', 'Promise', 'Map', 'Set', 'WeakMap', 'WeakSet', 'Proxy', 'Reflect', 'JSON', 'Math', 'Intl',
  'console', 'document', 'navigator', 'location', 'history', 'localStorage', 'sessionStorage', 'fetch', 'URL', 'URLSearchParams',
  'TextEncoder', 'TextDecoder', 'AbortController', 'Event', 'CustomEvent', 'HTMLElement', 'Node', 'NodeList', 'MutationObserver',
  'IntersectionObserver', 'ResizeObserver', 'requestAnimationFrame', 'cancelAnimationFrame', 'setTimeout', 'clearTimeout',
  'setInterval', 'clearInterval', 'queueMicrotask', 'structuredClone', 'parseInt', 'parseFloat', 'isNaN', 'isFinite',
  'encodeURIComponent', 'decodeURIComponent', 'encodeURI', 'decodeURI', 'alert', 'confirm', 'prompt', 'postMessage',
  'addEventListener', 'removeEventListener', 'getComputedStyle', 'atob', 'btoa', 'escape', 'unescape', 'eval', 'globalThis',
  'window', 'self', 'top', 'parent', 'frames', 'undefined', 'arguments',
  'Image', 'FileReader', 'Uint8Array', 'Int8Array', 'Uint16Array', 'Int16Array', 'Uint32Array', 'Int32Array', 'Float32Array',
  'Float64Array', 'ArrayBuffer', 'DataView', 'Blob', 'File', 'FormData', 'XMLHttpRequest', 'WebSocket', 'Worker', 'Audio',
  'MediaQueryList', 'matchMedia', 'crypto', 'performance', 'screen', 'Element', 'Text', 'Comment', 'DocumentFragment',
]);

/**
 * 把调用/成员路径归一化为 catalogue 名：
 * 去掉 window/globalThis/self 前缀与 TavernHelper facade 前缀，再取 catalog 中存在的最长前缀。
 */
function makeResolver(catalogNames) {
  return rawPath => {
    let name = rawPath;
    for (const prefix of ['window.', 'globalThis.', 'self.', 'unsafeWindow.']) {
      if (name.startsWith(prefix)) name = name.slice(prefix.length);
    }
    if (name.startsWith('TavernHelper.')) name = name.slice('TavernHelper.'.length);
    const segments = name.split('.');
    for (let length = segments.length; length >= 1; length--) {
      const candidate = segments.slice(0, length).join('.');
      if (catalogNames.has(candidate)) return candidate;
    }
    return null;
  };
}

function parseJs(code) {
  for (const sourceType of ['module', 'script']) {
    try { return parse(code, { ecmaVersion: 'latest', sourceType, locations: true }); }
    catch { /* 尝试下一种 sourceType */ }
  }
  return null;
}

/** 单个 JS 块 → { references: Map(name → lines), imports: [...], unmatched: Map(name → lines) } */
function scanBlock(code, blockId, resolve) {
  const tree = parseJs(code);
  if (!tree) {
    let message = '语法解析失败';
    try { parse(code, { ecmaVersion: 'latest', sourceType: 'module', locations: true }); }
    catch (error) { message = `语法解析失败: ${error.message}`; }
    return { references: new Map(), imports: [], unmatched: new Map(), warning: `${blockId} ${message}` };
  }
  const local = declaredNames(tree);
  const references = new Map(), unmatched = new Map(), imports = [];
  const record = (map, name, line) => {
    if (!map.has(name)) map.set(name, new Set());
    map.get(name).add(line);
  };
  walk(tree, (node, parent) => {
    if (node.type === 'ImportDeclaration' && typeof node.source.value === 'string') {
      imports.push({ specifier: node.source.value, script: blockId, line: node.loc.start.line });
    } else if (node.type === 'ExportNamedDeclaration' || node.type === 'ExportAllDeclaration') {
      if (typeof node.source?.value === 'string') imports.push({ specifier: node.source.value, script: blockId, line: node.loc.start.line });
    } else if (node.type === 'ImportExpression' && node.source?.type === 'Literal' && typeof node.source.value === 'string') {
      imports.push({ specifier: node.source.value, script: blockId, line: node.loc.start.line });
    }
    // 成员读取：Mvu.events.*、tavern_events.*、SillyTavern.* 等常量/命名空间访问也算引用
    if (node.type === 'MemberExpression') {
      if (node.computed) return;
      const rawPath = IDENTIFIER_PATH(node);
      if (rawPath && !local.has(rawPath.split('.')[0])) {
        const name = resolve(rawPath);
        if (name) record(references, name, node.loc.start.line);
      }
      return;
    }
    // 自由标识符读取：外部全局（lodash、jQuery、Zod、Vue 等）或作者自建全局
    if (node.type === 'Identifier') {
      if (!parent || local.has(node.name)) return;
      const isNonReference = (parent.type === 'MemberExpression' && ((!parent.computed && parent.property === node) || parent.object === node))
        || ((parent.type === 'Property' || parent.type === 'PropertyDefinition' || parent.type === 'MethodDefinition') && !parent.computed && parent.key === node && !parent.shorthand)
        || (parent.type === 'LabeledStatement' && parent.label === node)
        || ((parent.type === 'BreakStatement' || parent.type === 'ContinueStatement') && parent.label === node);
      if (isNonReference || BUILTIN_GLOBALS.has(node.name)) return;
      const name = resolve(node.name);
      if (name) record(references, name, node.loc.start.line);
      else record(unmatched, node.name, node.loc.start.line);
      return;
    }
    if (node.type !== 'CallExpression' && node.type !== 'NewExpression') return;
    const rawPath = IDENTIFIER_PATH(node.callee);
    if (!rawPath) return;
    const root = rawPath.split('.')[0];
    if (local.has(root)) return;
    const name = resolve(rawPath);
    if (name) record(references, name, node.loc.start.line);
    else if (!BUILTIN_GLOBALS.has(root)) record(unmatched, root, node.loc.start.line);
  });
  return { references, imports, unmatched, warning: null };
}

// ---------- 兼容目录 ----------

function loadCatalog(catalogPath) {
  const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));
  const bindings = new Map();
  const add = (name, binding, kind, source) => {
    if (!name || bindings.has(name)) return;
    bindings.set(name, { binding, kind, catalogSource: source });
  };
  for (const domain of catalog.domains ?? []) {
    for (const entry of domain.entries ?? []) add(entry.name, entry.playerBinding, entry.kind, 'domain-entry');
  }
  for (const entry of catalog.declarationFacade ?? []) add(entry.name, entry.playerBinding, 'facade-alias', 'declaration-facade');
  for (const entry of catalog.runtimeRegistrations ?? []) add(entry.name, entry.playerBinding, 'runtime-registration', 'runtime-registration');
  for (const entry of catalog.iframeBoundRegistrations ?? []) add(entry.name, entry.playerBinding, 'iframe-registration', 'iframe-registration');
  return bindings;
}

// ---------- 运行时证据 ----------

function loadRuntimeEvidence(warnings) {
  const sources = [];
  const perSample = new Map();
  for (const artifact of RUNTIME_ARTIFACTS) {
    const full = path.join(repoRoot, artifact.file);
    if (!fs.existsSync(full)) { sources.push({ path: artifact.file, present: false }); continue; }
    let data;
    try { data = JSON.parse(fs.readFileSync(full, 'utf8')); }
    catch (error) { warnings.push(`${artifact.file} 读取失败: ${error.message}`); sources.push({ path: artifact.file, present: true, error: true }); continue; }
    sources.push({ path: artifact.file, present: true, note: artifact.note });
    const record = (sha, names, detail) => {
      const id = ID_BY_SHA.get(sha);
      if (!id) return;
      if (!perSample.has(id)) perSample.set(id, []);
      for (const name of names ?? []) perSample.get(id).push({ name, origin: artifact.file, note: artifact.note, detail });
    };
    if (artifact.key === 'mvu-probe') {
      record(data.sha256, data.hostCalls, data.scope ?? null);
    } else if (artifact.key === 'card-probe') {
      for (const row of data.rows ?? []) {
        const detailParts = [`候选 ${row.candidate}`, `status=${row.status}`, `errors=${row.errors}`, `blocked(resource)=${row.blocked}`];
        record(row.sourceSha256, row.calls, detailParts.join(', '));
        record(row.sourceSha256, row.unsupported?.map(name => `${name} (unsupported)`), null);
        record(row.sourceSha256, row.pendingGlobals?.map(name => `${name} (pending-global)`), null);
      }
    } else {
      warnings.push(`${artifact.file} 无可识别的结构，未合并运行时证据`);
    }
  }
  return { sources, perSample };
}

function mergeRuntimeEvidence(list) {
  const merged = new Map();
  for (const item of list) {
    const cleanName = item.name.replace(/ \((unsupported|pending-global)\)$/, '');
    if (!merged.has(cleanName)) merged.set(cleanName, { name: cleanName, markers: new Set(), origins: new Set(), details: new Set() });
    const entry = merged.get(cleanName);
    const marker = item.name.slice(cleanName.length).trim();
    if (marker) entry.markers.add(marker.replace(/[()]/g, ''));
    entry.origins.add(item.origin);
    if (item.detail) entry.details.add(item.detail);
  }
  return [...merged.values()].sort((a, b) => a.name.localeCompare(b.name, 'en')).map(entry => ({
    name: entry.name,
    origins: [...entry.origins].sort(),
    note: '既有本地产物',
    markers: [...entry.markers].sort(),
    details: [...entry.details].sort(),
  }));
}

// ---------- 主流程 ----------

function parseArgs(argv) {
  const args = argv.slice(2);
  let samplesDir = null, out = null;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--out') out = args[++index];
    else if (!samplesDir) samplesDir = args[index];
    else throw new Error(`未知参数: ${args[index]}`);
  }
  if (!samplesDir) throw new Error('用法: node audit-dependencies.mjs <samples-dir> [--out <path>]');
  return { samplesDir: path.resolve(samplesDir), out: out ? path.resolve(out) : path.join(here, 'build/dependency-audit.json') };
}

const { samplesDir, out } = parseArgs(process.argv);
const globalWarnings = [];
if (!fs.existsSync(samplesDir) || !fs.statSync(samplesDir).isDirectory()) throw new Error(`样本目录不存在: ${samplesDir}`);

const catalogPath = path.join(here, 'compatibility-catalog.json');
const catalogBindings = loadCatalog(catalogPath);
const catalogNames = new Set(catalogBindings.keys());
const resolve = makeResolver(catalogNames);
const runtime = loadRuntimeEvidence(globalWarnings);

const files = fs.readdirSync(samplesDir).filter(name => /\.(png|json)$/i.test(name)).sort();
const decoded = new Map(); // id → card
const unmatchedFiles = [];
for (const name of files) {
  const full = path.join(samplesDir, name);
  if (!fs.statSync(full).isFile()) continue;
  const bytes = fs.readFileSync(full);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const id = ID_BY_SHA.get(sha256);
  if (!id) { unmatchedFiles.push({ sha256, format: name.endsWith('.json') ? 'json' : 'png' }); continue; }
  if (decoded.has(id)) { globalWarnings.push(`${id} 在同一目录匹配到多个原件，只处理第一个`); continue; }
  let raw = null;
  try { raw = name.endsWith('.json') ? JSON.parse(bytes.toString('utf8')) : decodePngCard(full); }
  catch (error) { globalWarnings.push(`${id} 解码失败: ${error.message}`); }
  decoded.set(id, { raw, format: name.endsWith('.json') ? 'json' : 'png' });
}

const samples = SAMPLES.map(meta => {
  const found = decoded.get(meta.id);
  const warnings = [];
  const sample = {
    id: meta.id,
    sha256: meta.sha256,
    present: Boolean(found),
    format: found?.format ?? null,
    referenced: [],
    external_imports: [],
    runtime_evidence: [],
    blocking_gaps: [],
    external_host_calls: [],
    unbound_references: [],
    unmatched_references: [],
    parse_warnings: warnings,
  };
  if (!found || !found.raw) return sample;

  const card = cardOf(found.raw);
  const blocks = scriptBlocks(card, warnings);
  const references = new Map(); // name → Set("script:line")
  const unmatched = new Map();
  const imports = [];
  for (const block of blocks) {
    const result = scanBlock(block.code, block.id, resolve);
    if (result.warning) warnings.push(result.warning);
    for (const [name, lines] of result.references) {
      if (!references.has(name)) references.set(name, new Set());
      for (const line of lines) references.get(name).add(`${block.id}:${line}`);
    }
    for (const [name, lines] of result.unmatched) {
      if (!unmatched.has(name)) unmatched.set(name, new Set());
      for (const line of lines) unmatched.get(name).add(`${block.id}:${line}`);
    }
    imports.push(...result.imports);
  }

  sample.referenced = [...references.entries()].sort(([a], [b]) => a.localeCompare(b, 'en')).map(([name, locations]) => {
    const binding = catalogBindings.get(name);
    return {
      name,
      binding: binding.binding,
      kind: binding.kind,
      catalogSource: binding.catalogSource,
      locations: locations.size,
      sources: [...locations].sort(compareLocation).map(location => {
        const [script, line] = location.split(/:(?=\d+$)/);
        return { script, line: Number(line) };
      }),
    };
  });
  sample.external_imports = [...new Map(imports.map(item => [`${item.specifier}|${item.script}|${item.line}`, item])).values()]
    .sort((a, b) => a.specifier.localeCompare(b.specifier, 'en') || a.script.localeCompare(b.script, 'en') || a.line - b.line);
  sample.runtime_evidence = mergeRuntimeEvidence(runtime.perSample.get(meta.id) ?? []);
  sample.unbound_references = sample.referenced.filter(entry => entry.binding !== 'binding-present' && entry.binding !== 'installed-by-parent').map(entry => entry.name);
  const runtimeNames = new Set(sample.runtime_evidence.map(entry => entry.name));
  const gaps = new Map();
  for (const entry of sample.referenced) {
    if (entry.binding !== 'absent' && entry.binding !== 'rejecting-stub') continue;
    gaps.set(entry.name, { name: entry.name, binding: entry.binding, basis: runtimeNames.has(entry.name) ? 'runtime-observed' : 'source-reference-only' });
  }
  // 运行时证据里的调用来自卡内引用的外部程序（如 MVU bundle），它由另一套宿主执行。
  // 不能用浏览器接口目录判定它们：宿主名要在对应执行环境（QuickJS 宿主）里核对，因此单列而不计入阻塞缺口。
  const externalHostCalls = new Map();
  for (const entry of sample.runtime_evidence) {
    const binding = catalogBindings.get(entry.name);
    if (!binding) continue;
    if (entry.name in gaps) continue;
    externalHostCalls.set(entry.name, {
      name: entry.name,
      browser_binding: binding.binding,
      origins: entry.origins,
      verdict: 'external-program-host-name: judge against the QuickJS host, not the browser catalog',
    });
  }
  sample.external_host_calls = [...externalHostCalls.values()].sort((a, b) => a.name.localeCompare(b.name, 'en'));
  sample.blocking_gaps = [...gaps.values()].sort((a, b) => a.name.localeCompare(b.name, 'en'));
  sample.unmatched_references = [...unmatched.entries()].sort(([a], [b]) => a.localeCompare(b, 'en')).map(([name, locations]) => {
    const sorted = [...locations].sort(compareLocation);
    return {
      name,
      locations: sorted.length,
      sources: sorted.slice(0, 20),
      sourcesDropped: Math.max(0, sorted.length - 20),
    };
  });
  return sample;
});

const referencedTotal = samples.reduce((sum, sample) => sum + sample.referenced.length, 0);
const countBinding = binding => samples.reduce((sum, sample) => sum + sample.referenced.filter(entry => entry.binding === binding).length, 0);
const result = {
  generatedFrom: {
    samplesDir: relative(samplesDir),
    catalog: relative(catalogPath),
    generator: relative(path.join(here, 'audit-dependencies.mjs')),
    runtimeArtifacts: runtime.sources,
  },
  note: '静态扫描记录源码引用；runtime_evidence 来自既有本地产物，可能过期；两者都不代表完整游玩验收。审计不执行卡内程序、不联网、不请求模型。',
  samples,
  unmatched_files: unmatchedFiles.sort((a, b) => a.sha256.localeCompare(b.sha256, 'en')),
  summary: {
    samples_present: samples.filter(sample => sample.present).length,
    referenced_total: referencedTotal,
    binding_present: countBinding('binding-present'),
    rejecting_stub: countBinding('rejecting-stub'),
    absent: countBinding('absent'),
    installed_by_parent: countBinding('installed-by-parent'),
    runtime_evidence_total: samples.reduce((sum, sample) => sum + sample.runtime_evidence.length, 0),
    blocking_gap_total: samples.reduce((sum, sample) => sum + sample.blocking_gaps.length, 0),
    external_host_call_total: samples.reduce((sum, sample) => sum + (sample.external_host_calls?.length ?? 0), 0),
    parse_warning_total: samples.reduce((sum, sample) => sum + sample.parse_warnings.length, 0),
  },
  warnings: globalWarnings.sort(),
};

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ output: relative(out), ...result.summary, unmatchedFiles: unmatchedFiles.length }, null, 2));
