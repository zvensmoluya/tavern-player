import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

test('real browser runs author HTML, bridges parent input, and keeps frames during state updates', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    const root = 'https://player.invalid', origin = 'https://card.test', epoch = 'epoch';
    const frames = new Map(); let number = 0, sent = 0;
    const display = 'Narrative\n\n```html\n<body><button id="change">Change</button><button id="send">Send</button><p id="count"></p><script>' +
      'window.boots=(window.boots||0)+1;document.getElementById("count").textContent=String(window.boots);' +
      'document.getElementById("change").onclick=()=>{replaceVariables({score:3});document.getElementById("count").textContent=String(getVariables().score)};' +
      'document.getElementById("send").onclick=()=>{parent.$("#send_textarea").val("From card");parent.$("#send_but").click()};' +
      '</script></body>\n```';
    let snapshot = { conversationId: 'c', revision: 'r0', chatVariables: {}, scriptVariables: {}, draft: '', mvu: null, worldbooks: [],
      program: { sources: [] }, presetProgram: { sources: [] }, presetHash: 'preset',
      messages: [{ id: 'm', turnId: 't', variantId: 'v', message_id: 0, name: 'Actor', role: 'assistant', status: 'COMPLETE',
        message: display, display, data: {}, extra: {}, swipes: [display], swipes_data: [{}], swipes_info: [{}], swipe_id: 0, is_hidden: false, reasoning: [] }] };
    const flags = { busy: false, running: false };
    await page.exposeBinding('_testPost', async (_, raw) => {
      const req = JSON.parse(raw); let result = null;
      if (req.method === 'ready') {
        await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
      } else if (req.method === 'frame.create') {
        const token = String(++number);
        frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token, turnId: 't', variantId: 'v' }, snapshot, epoch, rootOrigin: root, viewportHeight: 700 });
        result = { token, url: origin + '/frame/' + token };
      } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
      else if (req.method === 'host.variables.replace') {
        snapshot = { ...snapshot, revision: 'r1', chatVariables: req.args.data }; result = { snapshot };
      } else if (req.method === 'host.draft.replace') {
        snapshot = { ...snapshot, revision: 'r2', draft: req.args.text }; result = { snapshot };
      } else if (req.method === 'host.chat.send') { sent++; result = { snapshot }; }
      else throw new Error('Unexpected request ' + req.method);
      await page.evaluate(data => Player.receive(data), { type: 'result', id: req.id, result });
    });
    await page.addInitScript(() => { if (location.origin === 'https://player.invalid') window.PlayerBridge = { postMessage: raw => window._testPost(raw) }; });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      let body, type;
      if (url.pathname.startsWith('/frame/')) {
        const config = frames.get(url.pathname.split('/').at(-1));
        body = (await readFile('build/app-assets/web/parent.html', 'utf8')).replace('__PLAYER_CONFIGURATION__', JSON.stringify(config).replaceAll('<', '\\u003c'));
        type = 'text/html';
      } else {
        const name = url.pathname.split('/').at(-1);
        body = await readFile('build/app-assets/web/' + name);
        type = name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript';
      }
      await route.fulfill({ status: 200, contentType: type, body });
    });
    await page.goto(root + '/web/index.html');
    await page.waitForFunction(() => document.querySelectorAll('article iframe').length > 0);
    const child = () => page.frames().find(frame => frame.url() === 'about:srcdoc');
    for (let i = 0; i < 100 && !child(); i++) await new Promise(resolve => setTimeout(resolve, 20));
    const frame = child(); assert.ok(frame);
    await frame.locator('#change').click();
    await frame.locator('#count').filter({ hasText: '3' }).waitFor();
    await page.waitForFunction(() => document.getElementById('notice').textContent === '');
    assert.equal(snapshot.chatVariables.score, 3);
    await frame.locator('#send').click();
    for (let i = 0; i < 100 && sent === 0; i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(sent, 1); assert.equal(snapshot.draft, 'From card');
    assert.equal(await frame.evaluate(() => typeof PlayerBridge), 'undefined');
    assert.equal(await frame.evaluate(() => { try { return parent.parent.document.title; } catch { return 'isolated'; } }), 'isolated');
    for (let i = 0; i < 3; i++) await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await frame.waitForFunction(() => document.getElementById('count').textContent === '3');
    assert.equal(await frame.evaluate(() => window.boots), 1);
    const unsafe = '<div id="ordinary-html"><img src="data:image/png;base64,bad" onerror="globalThis.unwanted=1"><script>globalThis.unwanted=2</script>Ordinary HTML</div>';
    const code = '```js\n<script>globalThis.unwanted=3</script>\n```';
    const extra = [unsafe, code].map((text, i) => ({ ...snapshot.messages[0], id: 'extra-' + i, turnId: 'extra-' + i,
      variantId: 'extra-' + i, message_id: i + 1, message: text, display: text, swipes: [text] }));
    snapshot = { ...snapshot, revision: 'safe', messages: [...snapshot.messages, ...extra] };
    await page.evaluate(data => Player.receive(data), { type: 'delta', epoch, changes: { revision: 'safe' }, messages: extra,
      order: snapshot.messages.map(m => m.turnId), flags });
    await page.waitForFunction(() => document.querySelectorAll('article').length === 3);
    let staticFrame;
    for (let i = 0; i < 100 && !staticFrame; i++) {
      for (const candidate of page.frames()) if (candidate.url() === 'about:srcdoc' && await candidate.locator('#ordinary-html').count()) staticFrame = candidate;
      if (!staticFrame) await new Promise(resolve => setTimeout(resolve, 20));
    }
    assert.ok(staticFrame);
    assert.equal(await staticFrame.evaluate(() => typeof getVariables), 'undefined');
    assert.equal(await staticFrame.evaluate(() => typeof globalThis.unwanted), 'undefined');
    assert.equal(await staticFrame.locator('script').count(), 1); // Only the trusted resize/bootstrap hook remains.
    assert.equal(await frame.evaluate(() => window.boots), 1);
    assert.equal(await page.locator('article iframe').count(), 2);

  } finally { await browser.close(); }
});
