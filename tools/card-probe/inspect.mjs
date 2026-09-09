// 查看单张卡的结构细节：tavern_helper 脚本、正则、世界书条目。
// 用法: node inspect.mjs <卡片json|关键字> [--full] [--grep 正则]

import fs from 'node:fs';
import { listCards } from './lib/cards.mjs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const cardsDir = path.join(here, 'build/cards');

const args = process.argv.slice(2);
const needle = args.find((a) => !a.startsWith('--'));
const full = args.includes('--full');
const grepIdx = args.indexOf('--grep');
const grepRe = grepIdx >= 0 ? new RegExp(args[grepIdx + 1], 'i') : null;

const files = listCards().filter((f) => !needle || f.includes(needle));
if (!files.length) {
  console.error('没有匹配的卡:', needle, '\n可用:', listCards().join(', '));
  process.exit(1);
}

const clip = (s, n) => (full || !s ? s : s.length > n ? s.slice(0, n) + `\n…[${s.length - n} 字省略]` : s);

for (const f of files) {
  const card = JSON.parse(fs.readFileSync(path.join(cardsDir, f), 'utf8'));
  const d = card.data ?? card;
  console.log(`\n${'='.repeat(70)}\n# ${f}  —  ${d.name}\n${'='.repeat(70)}`);

  const scripts = d.extensions?.tavern_helper?.scripts;
  if (Array.isArray(scripts) && scripts.length) {
    console.log(`\n## tavern_helper 脚本 (${scripts.length})`);
    for (const s of scripts) {
      const content = s.content ?? '';
      console.log(`\n--- [${s.name}]  id=${s.id}  ${content.length} 字  button=${s.button ? 'yes' : 'no'} ---`);
      console.log(clip(content, 3000));
    }
  }

  const regexes = d.extensions?.regex_scripts ?? [];
  if (regexes.length) {
    console.log(`\n## 正则 (${regexes.length})`);
    for (const r of regexes) {
      console.log(`\n--- [${r.scriptName}] placement=${JSON.stringify(r.placement)} disabled=${r.disabled} markdownOnly=${r.markdownOnly} promptOnly=${r.promptOnly} ---`);
      console.log(`  find: ${clip(r.findRegex, 300)}`);
      console.log(`  repl: ${clip(r.replaceString, 1200)}`);
    }
  }

  const entries = d.character_book?.entries ?? [];
  if (entries.length) {
    console.log(`\n## 世界书条目 (${entries.length})`);
    for (const e of entries) {
      const c = e.content ?? '';
      const keys = (e.keys ?? []).join(',');
      console.log(`\n--- [${e.comment || e.name}] keys=${keys} const=${e.constant} ${c.length} 字 ---`);
      console.log(clip(c, 1500));
    }
  }
}
