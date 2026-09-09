// 卡片加载与「展示面」收集，供 probe.mjs / survey.mjs 共用。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export const cardsDir = path.join(here, 'build/cards');

export function listCards() {
  return JSON.parse(fs.readFileSync(path.join(here, 'build/inventory.json'), 'utf8')).map((r) => r.extractedFile).filter(Boolean);
}

export function loadCard(needle) {
  const matches = listCards().filter((f) => f.includes(needle));
  if (matches.length > 1) throw new Error('Ambiguous sample hash');
  const file = matches[0];
  if (!file) throw new Error(`没有匹配的卡: ${needle}\n可用: ${listCards().join(', ')}`);
  const card = JSON.parse(fs.readFileSync(path.join(cardsDir, file), 'utf8'));
  return { file, card, data: card.data ?? card };
}

export const looksHtml = (s) =>
  typeof s === 'string' && /<(?:html|head|body|div|script|style|section|canvas)\b/i.test(s);

/**
 * 展示面通常整段是 markdown 代码块（```html … ```），
 * Tavern Helper 只把围栏里的内容当页面渲染，探针也必须剥掉围栏。
 */
export function unfence(s) {
  const m = s.match(/```[a-zA-Z]*[ \t]*\r?\n([\s\S]*?)\r?\n?```/);
  return m && looksHtml(m[1]) ? m[1] : s;
}

/** 卡里HTML 候选片段；未证明真实消息会启用或渲染它们 */
export function collectSurfaces(d) {
  const out = [];
  const push = (label, raw) => {
    if (!looksHtml(raw)) return;
    out.push({ label, html: unfence(raw) });
  };
  for (const r of d.extensions?.regex_scripts ?? []) push(`regex[${(d.extensions?.regex_scripts ?? []).indexOf(r)}]`, r.replaceString);
  push('first_mes', d.first_mes);
  (d.alternate_greetings ?? []).forEach((g, i) => push(`alt_greeting[${i}]`, g));
  for (const e of d.character_book?.entries ?? []) push(`worldbook[${(d.character_book?.entries ?? []).indexOf(e)}]`, e.content);
  return out;
}
