import fs from 'node:fs';
import path from 'node:path';
import { here, loadCard, collectSurfaces, unfence } from './lib/cards.mjs';
import { projectOpening } from './lib/project.mjs';
import { runSurface, hash } from './lib/browser.mjs';
const argv = process.argv.slice(2);
const needle = argv[0];
const value = key => argv.includes(key) ? argv[argv.indexOf(key) + 1] : undefined;
if (!needle || needle.startsWith('--')) throw new Error('Usage: node probe.mjs <source-hash> [--list] [--surface N] [--scenario file] [--output file]');
const { file, data } = loadCard(needle);
const surfaces = collectSurfaces(data);
if (argv.includes('--list')) { console.log(JSON.stringify(surfaces.map((s,i)=>({index:i,label:s.label,chars:s.html.length})),null,2)); }
else {
  const index = Number(value('--surface') || 0);
  if (!surfaces[index]) throw new Error('Missing surface');
  const scenario = value('--scenario') ? JSON.parse(fs.readFileSync(value('--scenario'), 'utf8')) : {};
  let html = surfaces[index].html, projection = null;
  if (value('--message')) {
    const projected = projectOpening(data, surfaces[index], value('--message'));
    html = unfence(projected.rendered);
    scenario.host = { ...scenario.host, messages: [{ message_id: 0, message: projected.message, role: 'assistant', name: 'probe' }] };
    projection = { source: projected.source, regexIndex: projected.regexIndex, messageSha256: hash(projected.message), renderedSha256: hash(projected.rendered), scope: 'isolated-display-regex' };
  }
  const result = await runSurface(html, { waitMs: Number(value('--timeout') || 4500), scenario });
  result.projection = projection;
  result.sourceSha256 = file.replace('.json',''); result.surfaceIndex=index;result.candidate=surfaces[index].label;
  const output = value('--output') || path.join(here, 'build/probe-v2', `${result.sourceSha256}.${index}.json`);
  fs.mkdirSync(path.dirname(output), { recursive: true });fs.writeFileSync(output, JSON.stringify(result,null,2));
  console.log(JSON.stringify({source:result.sourceSha256,surface:index,calls:[...new Set(result.final.log.filter(e=>e.t==='call').map(e=>e.p))],errors:result.exceptions.length,actions:result.actions}));
}
