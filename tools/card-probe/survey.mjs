import fs from 'node:fs';
import path from 'node:path';
import { here, listCards, loadCard, collectSurfaces } from './lib/cards.mjs';
import { runSurface } from './lib/browser.mjs';
export function summarize(rows) {
  const totals = {};
  for (const row of rows) for (const name of new Set(row.calls || [])) totals[name] = (totals[name] || 0) + 1;
  return { attempted: rows.length, completed: rows.filter(r=>r.status==='observed').length, failed: rows.filter(r=>r.status==='failed').length, zeroCalls: rows.filter(r=>r.status==='observed' && !r.calls.length).length, totals };
}
if (process.argv[1] && path.resolve(process.argv[1]) === path.join(here, 'survey.mjs')) {
  const runId = new Date().toISOString().replace(/[:.]/g,'-');
  const outputDir = path.join(here,'build/runs',runId);fs.mkdirSync(outputDir,{recursive:true});
  const rows=[], samples=[];
  for (const file of listCards()) {
    const {data} = loadCard(file), surfaces=collectSurfaces(data), sourceSha256=file.replace('.json','');
    samples.push({sourceSha256,candidates:surfaces.length});
    for (const [index,s] of surfaces.entries()) {
      const row={sourceSha256,index,candidate:s.label,htmlChars:s.html.length};
      try {
        const result=await runSurface(s.html);
        const log=result.final.log;
        Object.assign(row,{status:'observed',calls:[...new Set(log.filter(e=>e.t==='call').map(e=>e.p))].sort(),errors:result.exceptions.length+log.filter(e=>['error','reject','console.error','callback-error'].includes(e.t)).length,blocked:result.blocked.length+log.filter(e=>e.t==='blocked-resource').length,unsupported:[...new Set(log.filter(e=>e.t==='unsupported').map(e=>e.p))],pendingGlobals:result.final.pendingGlobals,dropped:result.final.dropped,record:`${sourceSha256}.${index}.json`});
        fs.writeFileSync(path.join(outputDir,row.record),JSON.stringify({...result,sourceSha256,surfaceIndex:index,candidate:s.label},null,2));
      } catch(e) {Object.assign(row,{status:'failed',error:String(e)});}
      rows.push(row); console.log(sourceSha256.slice(0,16),index,row.status,JSON.stringify(row.calls||[]));
      fs.writeFileSync(path.join(outputDir,'survey.json'),JSON.stringify({runId,samples,rows,...summarize(rows)},null,2));
    }
  }
  const report={runId,samples,rows,...summarize(rows)};
  fs.writeFileSync(path.join(outputDir,'survey.json'),JSON.stringify(report,null,2));
  fs.writeFileSync(path.join(here,'build/survey-v2.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(summarize(rows)));
}
