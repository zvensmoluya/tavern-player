import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { here } from './cards.mjs';
import { installHost } from './host.mjs';
const sleep = ms => new Promise(r => setTimeout(r, ms));
export const hash = text => createHash('sha256').update(text).digest('hex');
const origin = 'https://card-probe.invalid';
const libs = {
  '/vue.js': 'node_modules/vue/dist/vue.global.js',
  '/lodash.js': 'node_modules/lodash/lodash.js',
  '/jquery.js': 'node_modules/jquery/dist/jquery.js',
  '/zod.js': 'build/libs/z.global.js',
};
function documentFor(html) {
  const prelude = Object.keys(libs).map(p => `<script src="${origin}${p}"></script>`).join('');
  const m = html.match(/<head[^>]*>/i) || html.match(/<html[^>]*>/i);
  if (m) { const at = m.index + m[0].length; return html.slice(0, at) + prelude + html.slice(at); }
  return `<!doctype html><html><head><meta charset="utf-8">${prelude}</head><body>${html}</body></html>`;
}
async function connect(url) {
  const ws = new WebSocket(url), pending = new Map(), handlers = new Map(); let seq = 0;
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  ws.onmessage = ({ data }) => {
    const msg = JSON.parse(data), slot = pending.get(msg.id);
    if (slot) { clearTimeout(slot.timer); pending.delete(msg.id); msg.error ? slot.reject(new Error(msg.error.message)) : slot.resolve(msg.result); }
    else if (handlers.has(msg.method)) handlers.get(msg.method)(msg.params);
  };
  return {
    on: (name, fn) => handlers.set(name, fn),
    send(method, params = {}) {
      const id = ++seq;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
        pending.set(id, { resolve, reject, timer }); ws.send(JSON.stringify({ id, method, params }));
      });
    },
    close() { for (const s of pending.values()) { clearTimeout(s.timer); s.reject(new Error('CDP closed')); } pending.clear(); ws.close(); }
  };
}
export async function runSurface(html, { waitMs = 4500, scenario = {} } = {}) {
  const executable = process.env.EDGE_PATH || path.join(process.env['ProgramFiles(x86)'] || process.env.ProgramFiles, 'Microsoft/Edge/Application/msedge.exe');
  const profile = path.join(here, 'build', 'profiles', randomUUID());
  fs.mkdirSync(profile, { recursive: true });
  const proc = spawn(executable, ['--headless=new', '--disable-gpu', '--no-first-run', '--disable-background-networking', '--disable-sync', '--disable-extensions', '--host-resolver-rules=MAP * ~NOTFOUND', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore', windowsHide: true });
  let spawnError; proc.on('error', e => { spawnError = e; });
  let client; const blocked = [], exceptions = [], transportErrors = [], actions = [];
  try {
    let port;
    for (let i = 0; i < 80; i++) {
      if (spawnError) throw spawnError;
      try { port = Number(fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0]); break; } catch {}
      await sleep(200);
    }
    if (!port) throw new Error('Browser startup timed out');
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    const target = targets.find(t => t.type === 'page' && t.url === 'about:blank');
    if (!target) throw new Error('Missing blank page target');
    client = await connect(target.webSocketDebuggerUrl);
    client.on('Runtime.exceptionThrown', e => exceptions.push(e.exceptionDetails.text + ': ' + (e.exceptionDetails.exception?.description || '')));
    const assets = new Map([[origin + '/page.html', { body: documentFor(html), type: 'text/html' }], ...Object.entries(libs).map(([url, file]) => [origin + url, { body: fs.readFileSync(path.join(here, file), 'utf8'), type: 'application/javascript' }])]);
    client.on('Fetch.requestPaused', e => {
      const asset = assets.get(e.request.url);
      const request = asset ? client.send('Fetch.fulfillRequest', {
        requestId: e.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: asset.type + '; charset=utf-8' }, ...(asset.type === 'text/html' ? [{ name: 'Content-Security-Policy', value: "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'" }] : [])],
        body: Buffer.from(asset.body).toString('base64')
      }) : (blocked.push({ url: e.request.url, type: e.resourceType }), client.send('Fetch.failRequest', { requestId: e.requestId, errorReason: 'BlockedByClient' }));
      request.catch(e => transportErrors.push(e.message));
    });
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    await client.send('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
    await client.send('Page.addScriptToEvaluateOnNewDocument', { source: `(${installHost.toString()})(${JSON.stringify(scenario.host || {})});\nwindow.addEventListener('securitypolicyviolation', e => window.__PROBE__.log.push({phase: 'resource', t:'blocked-resource', p:e.blockedURI, directive:e.violatedDirective}));` });
    const version = await client.send('Browser.getVersion');
    await client.send('Page.navigate', { url: origin + '/page.html' });
    const evaluate = async expression => {
      const r = await client.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ': ' + (r.exceptionDetails.exception?.description || ''));
      return r.result.value;
    };
    await sleep(waitMs);
    const load = await evaluate('window.__PROBE__?.snapshot()');
    if (!load?.ready) throw new Error('Host recorder did not initialize');
    for (const [i, step] of (scenario.steps || []).entries()) {
      await evaluate(`window.__PROBE__.setPhase(${JSON.stringify(`step-${i}:${step.type}`)})`);
      try {
        let result;
        if (step.type === 'state') result = await evaluate(`window.__PROBE__.setState(${JSON.stringify(step.value)})`);
        else if (step.type === 'event') result = await evaluate(`window.__PROBE__.fire(${JSON.stringify(step.name)}, ${JSON.stringify(step.args || [])})`);
        else if (step.type === 'click') result = await evaluate(`(() => {const el=document.querySelector(${JSON.stringify(step.selector)});if(!el)throw Error('Missing click target');if(el.disabled)throw Error('Disabled click target');el.click();return true;})()`);
        else if (step.type === 'input') result = await evaluate(`(() => {const el=document.querySelector(${JSON.stringify(step.selector)});if(!el)throw Error('Missing input target');el.value=${JSON.stringify(step.value)};el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return true;})()`);
        else throw new Error('Unknown scenario step');
        await sleep(200); actions.push({ index: i, type: step.type, status: 'executed', result });
      } catch (e) { actions.push({ index: i, type: step.type, status: 'failed', error: e.message }); }
    }
    const final = await evaluate('window.__PROBE__.snapshot()');
    const dom = await evaluate(`({dataset:{...document.body?.dataset},text:document.body?.innerText.slice(0,20000),controls:[...document.querySelectorAll('button,input,select,a,[onclick]')].slice(0,100).map(e=>({tag:e.tagName,id:e.id,type:e.type,classes:e.className,disabled:e.disabled}))})`);
    return { schemaVersion: 2, recorderSha256: hash(installHost.toString()), runnerSha256: hash(fs.readFileSync(new URL(import.meta.url))), browser: version.product, htmlSha256: hash(html), scenarioSha256: hash(JSON.stringify(scenario)), waitMs, load, final, actions, dom, blocked, exceptions, transportErrors, libraries: Object.fromEntries(Object.entries(libs).map(([key,file])=>[key,hash(fs.readFileSync(path.join(here,file)))])) };
  } finally { client?.close(); proc.kill(); }
}
