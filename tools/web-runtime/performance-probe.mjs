// Synthetic research probe. Timings are desktop observations, not Android acceptance limits.
// Run after `npm run build`; native bridge and resource responses are simulated locally.
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { performance } from 'node:perf_hooks';
import { build } from 'esbuild';
import { chromium } from 'playwright-core';
import { createSession } from './src/session.mjs';

const text = 'A quiet room. **A reply** and a short description.\n\n'.repeat(40);
const message = (index, variableChars = 0) => ({
  id: 'm' + index, turnId: 't' + index, variantId: 'v' + index, message_id: index,
  name: 'Actor', role: 'assistant', status: 'COMPLETE', message: text, sourceText: text,
  display: text, data: { value: 'x'.repeat(variableChars) }, extra: {},
  swipes: [text], swipes_data: [{ value: 'x'.repeat(variableChars) }], swipes_info: [{}],
  swipe_id: 0, is_hidden: false, reasoning: [],
});
const snapshot = (count, variableChars = 0) => ({
  conversationId: 'probe', revision: '0', chatVariables: {}, scriptVariables: {}, draft: '',
  mvu: null, worldbooks: [], program: { sources: [] }, presetProgram: { sources: [] },
  presetHash: 'preset', messages: Array.from({ length: count }, (_, i) => message(i, variableChars)),
});
const percentile = (items, p) => [...items].sort((a, b) => a - b)[Math.ceil(items.length * p) - 1];
const stats = items => ({ samples: items.length, p50Ms: percentile(items, .5), p95Ms: percentile(items, .95) });
const sha = value => createHash('sha256').update(value).digest('hex');
const report = {
  generatedAt: new Date().toISOString(),
  commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  node: process.version, platform: process.platform, arch: process.arch, messageChars: text.length,
  sources: Object.fromEntries(await Promise.all(['src/shell.mjs', 'src/session.mjs', 'src/parent.mjs', 'src/markdown.mjs', 'src/dom-patch.mjs']
    .map(async file => [file, sha(await readFile(file))]))),
  scope: 'Synthetic desktop probe; excludes Android, disk, provider, real author programs and frame presentation latency.',
  session: [], sessionDelta: [], browser: [],
};

for (const variableChars of [0, 4096]) for (const count of [50, 200, 1000]) {
  const initial = snapshot(count, variableChars), session = createSession(initial), elapsed = [];
  for (let i = 0; i < 25; i++) {
    const next = { ...initial, revision: String(i + 1), draft: 'draft ' + i };
    const start = performance.now(); session.receive(next); const duration = performance.now() - start;
    if (i >= 5) elapsed.push(duration);
  }
  assert.equal(session.state.draft, 'draft 24');
  report.session.push({ messages: count, variableChars, snapshotBytes: Buffer.byteLength(JSON.stringify(initial)), ...stats(elapsed) });
  const deltaElapsed = [];
  const history = session.state.messages;
  for (let i = 0; i < 25; i++) {
    const start = performance.now();
    session.receiveDelta({ changes: { revision: 'delta-' + i, draft: 'delta ' + i } });
    const duration = performance.now() - start;
    if (i >= 5) deltaElapsed.push(duration);
  }
  assert.equal(session.state.messages, history, 'Draft deltas must retain the historical payload');
  report.sessionDelta.push({ messages: count, variableChars, ...stats(deltaElapsed) });
  session.destroy();
}

// Count calls in the real shell source; do not replace its renderer or change production files.
let shell = await readFile('src/shell.mjs', 'utf8');
const hooks = [
  ['export function segments(text, incomplete = false) {', 'export function segments(text, incomplete = false) { globalThis.__probeParses = (globalThis.__probeParses ?? 0) + 1;'],
  ['globalThis.Player = {', 'globalThis.Player = { probeIdle() { return renderQueue; },'],
];
for (const [before, after] of hooks) {
  assert.equal(shell.split(before).length, 2, 'Probe hook must match exactly once: ' + before);
  shell = shell.replace(before, after);
}
const bundled = await build({ stdin: { contents: shell, resolveDir: process.cwd() + '/src', sourcefile: 'shell.mjs' },
  bundle: true, format: 'iife', write: false, target: 'es2020' });
const browser = await chromium.launch({ channel: process.env.PROBE_BROWSER_CHANNEL || 'msedge', headless: true });
try {
  report.browserVersion = browser.version();
  for (const [count, shown] of [[50, 50], [200, 50], [1000, 50], [200, 200]]) {
    console.error(`Probing ${count} messages, ${shown} displayed`);
    const page = await browser.newPage({ viewport: { width: 420, height: 700 } });
    const initial = snapshot(count), frames = new Map(), errors = [];
    const origin = 'https://player.invalid', author = 'https://card.test', epoch = 'probe';
    const flags = { busy: false, running: false }; let serial = 0;
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    await page.exposeBinding('_probePost', async (_, raw) => {
      const req = JSON.parse(raw); let result = null;
      if (req.method === 'ready') await page.evaluate(packet => Player.receive(packet), { type: 'snapshot', epoch, snapshot: initial, flags });
      else if (req.method === 'frame.create') {
        const token = String(++serial);
        frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token },
          snapshot: initial, epoch, rootOrigin: origin, viewportHeight: 700 });
        result = { token, url: author + '/frame/' + token };
      } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
      else throw new Error('Unexpected native method: ' + req.method);
      await page.evaluate(packet => Player.receive(packet), { type: 'result', id: req.id, result });
    });
    await page.addInitScript(() => {
      if (location.origin === 'https://player.invalid') window.PlayerBridge = { postMessage: raw => window._probePost(raw) };
    });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url()), name = url.pathname.split('/').at(-1);
      let body, contentType;
      if (url.pathname.startsWith('/frame/')) {
        body = (await readFile('build/app-assets/web/parent.html', 'utf8'))
          .replace('__PLAYER_CONFIGURATION__', JSON.stringify(frames.get(name)).replaceAll('<', '\\u003c'));
        contentType = 'text/html';
      } else {
        body = name === 'shell.js' ? Buffer.from(bundled.outputFiles[0].contents) : await readFile('build/app-assets/web/' + name);
        contentType = name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript';
      }
      await route.fulfill({ status: 200, contentType, body });
    });
    await page.goto(origin + '/web/index.html', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelectorAll('article').length === 50);
    await page.evaluate(() => Player.probeIdle());
    for (let visible = 50; visible < shown; visible += 50) {
      await page.evaluate(async () => { document.getElementById('earlier').click(); await Player.probeIdle(); });
    }
    assert.equal(await page.locator('article').count(), shown);
    const measurements = await page.evaluate(async ({ initial, epoch, flags }) => {
      const times = [], parses = [];
      for (let i = 0; i < 15; i++) {
        const before = globalThis.__probeParses, start = performance.now();
        Player.receive({ type: 'delta', epoch, changes: { draft: 'draft ' + i, revision: 'd' + i },
          messages: [], flags });
        await Player.probeIdle();
        if (i >= 5) { times.push(performance.now() - start); parses.push(globalThis.__probeParses - before); }
      }
      const before = globalThis.__probeParses, start = performance.now();
      for (let i = 0; i < 20; i++) {
        const last = { ...initial.messages.at(-1), display: initial.messages.at(-1).display + i, status: 'STREAMING' };
        Player.receive({ type: 'delta', epoch, changes: { revision: 's' + i }, messages: [last],
          flags: { ...flags, running: true } });
      }
      await Player.probeIdle();
      return { times, parses, burstParses: globalThis.__probeParses - before, burstShellMs: performance.now() - start };
    }, { initial, epoch, flags });
    assert.deepEqual(errors, []);
    const authorSession = page.frames().find(frame => frame.name() === 'player_author_session');
    // The coordinator is display:none; Chromium may suspend animation-frame polling there.
    await authorSession.waitForFunction(() => PlayerAuthorSession.state.revision === 's19', null, { polling: 100 });
    assert.equal(await authorSession.evaluate(() => PlayerAuthorSession.state.draft), 'draft 14');
    assert.ok(measurements.parses.every(value => value === 0), 'Draft changes must not parse history');
    assert.equal(measurements.burstParses, 20, 'Each tail update must parse only the changed message');
    report.browser.push({ messages: count, shown, draftShell: stats(measurements.times),
      draftParseCalls: [...new Set(measurements.parses)], burstUpdates: 20,
      burstParseCalls: measurements.burstParses, burstShellMs: measurements.burstShellMs });
    await page.close();
  }
} finally { await browser.close(); }
await mkdir('build/performance', { recursive: true });
await writeFile('build/performance/probe.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
