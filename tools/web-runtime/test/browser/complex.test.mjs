import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';
import { parse as yaml } from 'yaml';
import { localSample } from './local-sample.mjs';

// 最近一次运行实际观察到的宿主桥调用；跳过的样本不写入，也不冒充结果。
const callReport = new Map();
async function recordCalls(hash, calls) {
  callReport.set(hash.slice(0, 16), [...calls]);
  const payload = {
    tool: 'test/browser/complex.test.mjs',
    note: '本文件只记录最近一次浏览器复杂样本测试每条用例实际发生的宿主桥调用；未运行的样本不会出现。',
    samples: [...callReport.entries()].sort(([a], [b]) => a.localeCompare(b, 'en'))
      .map(([sha256_16, list]) => ({ sha256_16, calls: list })),
  };
  await mkdir(new URL('../../build/', import.meta.url), { recursive: true });
  await writeFile(new URL('../../build/capability-calls.json', import.meta.url), JSON.stringify(payload, null, 2) + '\n');
}

const cases = [
  { hash: '7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32', rule: 1, selector: '#tabNav', implicitBody: true },
  { hash: '8f24972a97e9cb357e105e5d7d101a7ec3b7ff5c6cc0f98a4024ac94a895583f', rule: 4, selector: '#content', opening: true },
  { hash: 'fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe', rule: 1, selector: '#toggle-panel-btn' },
  { hash: '0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0', rule: 0, selector: '#story-content' },
];

for (const sample of cases) test(`original complex page ${sample.hash.slice(0, 12)} initializes and interacts`, async t => {
  const card = await localSample(sample.hash);
  if (!card) { t.skip('Optional original is unavailable'); return; }
  const calls = [];
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage(), root = 'https://player.invalid', origin = 'https://card.test', epoch = 'complex';
    const frames = new Map(), errors = [];
    let serial = 0;
    const initial = card.character_book.entries.find(entry => /initvar/i.test(entry.comment ?? ''));
    const mvu = { stat_data: yaml(initial.content.replaceAll('{{user}}', 'User').replaceAll('{{char}}', 'Actor')), schema: {} };
    const raw = card.first_mes;
    const display = card.extensions.regex_scripts[sample.rule].replaceString;
    let snapshot = { conversationId: 'c', revision: 'r0', chatVariables: {}, scriptVariables: {}, draft: '', mvu, worldbooks: [],
      characterRegexes: card.extensions.regex_scripts.map(r => ({ id: r.id, script_name: r.scriptName, enabled: !r.disabled, find_regex: r.findRegex, replace_string: r.replaceString, trim_strings: r.trimStrings ?? [], source: { user_input: r.placement.includes(1), ai_output: r.placement.includes(2), slash_command: r.placement.includes(3), world_info: r.placement.includes(5), reasoning: r.placement.includes(6) }, destination: { display: !!r.markdownOnly, prompt: !!r.promptOnly }, run_on_edit: !!r.runOnEdit, min_depth: r.minDepth ?? null, max_depth: r.maxDepth ?? null })),
      program: { sources: [] }, presetProgram: { sources: [] }, presetHash: 'preset',
      messages: [{ id: 'm', turnId: 't', variantId: 'v', message_id: 0, name: 'Actor', role: 'assistant', status: 'COMPLETE',
        message: raw, display, data: mvu, extra: {}, swipes: [raw, ...(card.alternate_greetings ?? [])],
        swipes_data: [mvu], swipes_info: [{}], swipe_id: 0, is_hidden: false, reasoning: [] }] };
    const flags = { busy: false, running: false };
    page.on('pageerror', error => errors.push(error.message));
    await page.exposeBinding('_testPost', async (_, rawRequest) => {
      const req = JSON.parse(rawRequest); let result = null;
      calls.push(req.method);
      if (req.method === 'ready') await page.evaluate(packet => Player.receive(packet), { type: 'snapshot', epoch, snapshot, flags });
      else if (req.method === 'frame.create') {
        const token = String(++serial), message = req.args.messageId ? snapshot.messages[0] : null;
        frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token, turnId: message?.turnId, variantId: message?.variantId }, snapshot, epoch, rootOrigin: root, viewportHeight: 700 });
        result = { token, url: origin + '/frame/' + token };
      } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
      else if (req.method === 'host.regex.replace') {
        snapshot = { ...snapshot, revision: 'r' + (++serial), characterRegexes: req.args.regexes }; result = { snapshot };
      } else if (req.method === 'host.messages.set') {
        assert.equal(req.args.messages[0].message_id, 0);
        const index = req.args.messages[0].swipe_id;
        assert.ok(index > 0 && index < snapshot.messages[0].swipes.length);
        snapshot = { ...snapshot, revision: 'r' + (++serial), messages: snapshot.messages.map(m => ({ ...m, swipe_id: index, message: m.swipes[index] })) };
        result = { snapshot };
      } else throw new Error('Unexpected host operation: ' + req.method);
      await page.evaluate(packet => Player.receive(packet), { type: 'result', id: req.id, result });
    });
    await page.addInitScript(() => { if (location.origin === 'https://player.invalid') window.PlayerBridge = { postMessage: raw => window._testPost(raw) }; });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (![root, origin].includes(url.origin)) {
        // Decorative media/fonts are outside this JS API test; no third-party code is substituted.
        if (route.request().resourceType() === 'script') throw new Error('Uncovered external program: ' + url.origin);
        return route.fulfill({ status: 200, contentType: 'text/plain', body: '' });
      }
      const name = url.pathname.split('/').at(-1);
      const body = url.pathname.startsWith('/frame/')
        ? (await readFile('build/app-assets/web/parent.html', 'utf8')).replace('__PLAYER_CONFIGURATION__', JSON.stringify(frames.get(name)).replaceAll('<', '\\u003c'))
        : await readFile('build/app-assets/web/' + name);
      await route.fulfill({ status: 200, contentType: url.pathname.startsWith('/frame/') || name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript', body });
    });
    await page.goto(root + '/web/index.html');
    const child = page.frameLocator('article iframe').frameLocator('#page');
    await child.locator(sample.selector).waitFor({ state: 'attached' });
    if (sample.opening) {
      const control = child.locator('[onclick*="switchToOpening"]').first();
      await control.click();
      const parent = page.frames().find(frame => frame.url().startsWith(origin + '/frame/') && frame.name() !== 'player_author_session');
      await parent.waitForFunction(() => getChatMessages(0, { include_swipes: true })[0].swipe_id > 0);
      assert.ok(calls.includes('host.messages.set'));
      const before = snapshot.characterRegexes.map(rule => rule.replace_string);
      const unlock = child.locator('[onclick*="handleRuriClick"]').first();
      for (let i = 0; i < 5; i++) await unlock.click();
      await parent.waitForFunction(() => getChatMessages(0, { include_swipes: true })[0].swipe_id === 6);
      assert.ok(calls.includes('host.regex.replace'));
      assert.ok(snapshot.characterRegexes.some((rule, i) => rule.replace_string !== before[i]));
    } else if (sample.implicitBody) {
      const tab = child.locator('#tabNav .tab-btn').last();
      await tab.click();
      assert.match(await tab.getAttribute('class'), /active/);
    } else if (sample.rule === 1) {
      await child.locator(sample.selector).click();
      await child.locator('#main-panel').waitFor({ state: 'visible' });
    } else {
      await child.locator('#tab-world').click();
      await child.locator('#world-time').waitFor({ state: 'visible' });
    }
    assert.deepEqual(errors, []);
    assert.equal(await page.locator('#notice').textContent(), '');
  } finally { await browser.close(); await recordCalls(sample.hash, calls); }
});
