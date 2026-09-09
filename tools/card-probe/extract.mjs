// 从 PNG 角色卡或 JSON 中提取原始卡片数据，并输出结构清点。
// 用法: node extract.mjs [sourceDir]
// 输出: build/cards/<slug>.json + build/inventory.json

import fs from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const sourceDir = path.resolve(process.argv[2] ?? path.join(here, '../../source'));
const outDir = path.join(here, 'build/cards');
fs.mkdirSync(outDir, { recursive: true });

/** 读取 PNG 的 tEXt 块，返回 { keyword: text } */
function readPngTextChunks(buf) {
  const chunks = {};
  let p = 8;
  while (p + 12 <= buf.length) {
    const len = buf.readUInt32BE(p);
    const type = buf.toString('latin1', p + 4, p + 8);
    if (type === 'tEXt') {
      const data = buf.subarray(p + 8, p + 8 + len);
      const nul = data.indexOf(0);
      const keyword = data.toString('latin1', 0, nul);
      chunks[keyword] = data.toString('latin1', nul + 1);
    }
    if (type === 'IEND') break;
    p += 12 + len;
  }
  return chunks;
}

/** 角色卡 PNG 把 JSON 以 base64 存在 chara / ccv3 文本块里 */
function cardFromPng(file) {
  const chunks = readPngTextChunks(fs.readFileSync(file));
  for (const key of ['ccv3', 'chara']) {
    if (!chunks[key]) continue;
    try {
      return { data: JSON.parse(Buffer.from(chunks[key], 'base64').toString('utf8')), via: key };
    } catch (e) {
      console.warn(`  ! ${path.basename(file)} ${key} 解析失败: ${e.message}`);
    }
  }
  return null;
}

const slug = (s) => s.replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '').slice(0, 60);

/** 递归收集所有字符串值中出现的宿主 API 名字面量 */
function collectStrings(node, out = [], depth = 0) {
  if (depth > 40 || node == null) return out;
  if (typeof node === 'string') { out.push(node); return out; }
  if (Array.isArray(node)) { for (const v of node) collectStrings(v, out, depth + 1); return out; }
  if (typeof node === 'object') { for (const v of Object.values(node)) collectStrings(v, out, depth + 1); }
  return out;
}

const CODE_FENCE = /```[\s\S]*?```/;
const HTML_HINT = /<(?:html|head|body|script|style|div|iframe|canvas|svg)\b/i;
const SCRIPT_TAG = /<script\b/i;

function describeText(label, text) {
  if (typeof text !== 'string' || !text) return null;
  return {
    label,
    chars: text.length,
    hasHtmlTag: HTML_HINT.test(text),
    hasScriptTag: SCRIPT_TAG.test(text),
    codeFences: (text.match(/```/g) ?? []).length / 2,
  };
}

function inventoryCard(name, data, via) {
  const spec = data.spec ?? 'unknown';
  const ext = data.data ?? {};
  const extKeys = Object.keys(ext.extensions ?? {});
  const helper = ext.extensions?.tavern_helper;
  const helperScripts = helper?.scripts;
  const book = ext.character_book?.entries ?? [];
  const regexes = ext.extensions?.regex_scripts ?? [];
  const depthPrompt = ext.extensions?.depth_prompt;

  // 世界书条目里可能藏着代码块或 MVU 初始化
  const bookWithCode = [];
  const bookWithInit = [];
  for (const e of book) {
    const c = e.content ?? '';
    if (CODE_FENCE.test(c) || HTML_HINT.test(c)) bookWithCode.push(e.comment || e.name || `#${e.id}`);
    if (/\[InitVar\]|InitVar|stat_data|variables/i.test(c) && c.length < 20000) {
      bookWithInit.push(e.comment || e.name || `#${e.id}`);
    }
  }

  const texts = [];
  const add = (label, t) => { const d = describeText(label, t); if (d) texts.push(d); };
  add('description', ext.description);
  add('personality', ext.personality);
  add('scenario', ext.scenario);
  add('first_mes', ext.first_mes);
  add('mes_example', ext.mes_example);
  add('system_prompt', ext.system_prompt);
  add('post_history_instructions', ext.post_history_instructions);
  (ext.alternate_greetings ?? []).forEach((g, i) => add(`alternate_greetings[${i}]`, g));
  (ext.group_only_greetings ?? []).forEach((g, i) => add(`group_only_greetings[${i}]`, g));
  if (depthPrompt?.prompt) add('depth_prompt.prompt', depthPrompt.prompt);

  const helperShape = helperScripts == null
    ? null
    : Array.isArray(helperScripts)
      ? { kind: 'array', count: helperScripts.length, names: helperScripts.map((s) => s?.name ?? '(unnamed)') }
      : { kind: typeof helperScripts, keys: Object.keys(helperScripts).slice(0, 20) };

  return {
    file: name,
    via,
    spec,
    specVersion: data.spec_version ?? null,
    cardName: ext.name ?? null,
    creator: ext.creator ?? null,
    characterVersion: ext.character_version ?? null,
    tags: ext.tags ?? [],
    extensionKeys: extKeys,
    tavernHelper: helperShape,
    worldBook: {
      name: ext.character_book?.name ?? null,
      entries: book.length,
      withCodeOrHtml: bookWithCode.slice(0, 20),
      withInitOrState: bookWithInit.slice(0, 20),
    },
    regexScripts: Array.isArray(regexes) ? regexes.length : null,
    depthPrompt: depthPrompt ? { depth: depthPrompt.depth, role: depthPrompt.role } : null,
    texts,
    totalChars: collectStrings(ext).reduce((n, s) => n + s.length, 0),
  };
}

const results = [];
for (const f of fs.readdirSync(sourceDir).sort()) {
  const full = path.join(sourceDir, f);
  if (fs.statSync(full).isDirectory()) continue;
  let data = null;
  let via = null;
  if (f.endsWith('.png')) {
    const r = cardFromPng(full);
    if (r) { data = r.data; via = r.via; }
  } else if (f.endsWith('.json')) {
    data = JSON.parse(fs.readFileSync(full, 'utf8'));
    via = 'json';
  }
  if (!data) continue;
  // 有些导出把卡包在 { data: {...} } 里
  if (!data.data && data.spec) data = { spec: data.spec, data };
  const sourceSha256 = createHash('sha256').update(fs.readFileSync(full)).digest('hex');
  const extractedFile = sourceSha256 + '.json';
  const out = path.join(outDir, extractedFile);
  fs.writeFileSync(out, JSON.stringify(data, null, 2));
  const inv = inventoryCard(f, data, via);
  results.push({ ...inv, sourceSha256, extractedFile });
  console.log(sourceSha256.slice(0, 16), via);
}

fs.writeFileSync(path.join(here, 'build/inventory.json'), JSON.stringify(results, null, 2));
console.log(`\n卡片 JSON 已写入 ${outDir}`);
