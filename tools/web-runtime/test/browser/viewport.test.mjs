import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const root = 'https://player.invalid', origin = 'https://card.test', epoch = 'viewport';
const card = body => 'Narrative\n\n```html\n' + body + '\n```';
const message = (display, index) => ({ id: 'm' + index, turnId: 't' + index, variantId: 'v' + index, message_id: index, name: 'Actor',
  role: 'assistant', status: 'COMPLETE', message: display, display, data: {}, extra: {}, swipes: [display], swipes_data: [{}], swipes_info: [{}], swipe_id: 0, is_hidden: false, reasoning: [] });

// Host stand-in: the shell only reaches native through PlayerBridge, answered here by a binding.
async function open(browser, viewport, displays) {
  const page = await browser.newPage({ viewport });
  const frames = new Map(); let serial = 0;
  let snapshot = { conversationId: 'c', revision: 'r0', chatVariables: {}, scriptVariables: {}, draft: '', mvu: null, worldbooks: [],
    program: { sources: [] }, presetProgram: { sources: [] }, presetHash: 'preset', messages: displays.map(message) };
  const flags = { busy: false, running: false };
  await page.exposeBinding('_testPost', async (_, raw) => {
    const req = JSON.parse(raw); let result = null;
    if (req.method === 'ready') await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    else if (req.method === 'frame.create') {
      const token = String(++serial);
      const message = snapshot.messages.find(value => value.id === req.args.messageId);
      frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token, turnId: message?.turnId, variantId: message?.variantId }, snapshot, epoch, rootOrigin: root, viewportHeight: 700 });
      result = { token, url: origin + '/frame/' + token };
    } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
    else throw new Error('Unexpected request ' + req.method);
    await page.evaluate(data => Player.receive(data), { type: 'result', id: req.id, result });
  });
  await page.addInitScript(() => { if (location.origin === 'https://player.invalid') window.PlayerBridge = { postMessage: raw => window._testPost(raw) }; });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    let body, type;
    if (url.pathname.startsWith('/frame/')) {
      body = (await readFile('build/app-assets/web/parent.html', 'utf8')).replace('__PLAYER_CONFIGURATION__', JSON.stringify(frames.get(url.pathname.split('/').at(-1))).replaceAll('<', '\\u003c'));
      type = 'text/html';
    } else {
      const name = url.pathname.split('/').at(-1);
      body = await readFile('build/app-assets/web/' + name);
      type = name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript';
    }
    await route.fulfill({ status: 200, contentType: type, body });
  });
  await page.goto(root + '/web/index.html');
  return { page, frames, push: async (...extra) => {
    snapshot = { ...snapshot, revision: 'r' + (++serial), messages: [...snapshot.messages, ...extra] };
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
  } };
}

async function authorFrame(page, selector) {
  for (let i = 0; i < 200; i++) {
    for (const candidate of page.frames()) {
      if (candidate.url() !== 'about:srcdoc') continue;
      try { if (await candidate.locator(selector).count()) return candidate; } catch {}
    }
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Author frame with ' + selector + ' not found');
}

test('author vh layout follows the visible viewport without reloading the page', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page } = await open(browser, { width: 420, height: 700 }, [card('<body><style>#panel{min-height:100vh}</style><div id="panel"></div></body>')]);
    const frame = await authorFrame(page, '#panel');
    assert.equal(await frame.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--player-frame-vh')), '7px');
    // 折算引用变量并保留作者原本的 vh 语义作为回退；缺少变量时不会整体失效。
    assert.match(await frame.evaluate(() => [...document.querySelectorAll('style')].map(style => style.textContent).join('')),
      /min-height:calc\(100 \* var\(--player-frame-vh, 1vh\)\)/);
    assert.equal(await frame.evaluate(() => getComputedStyle(document.getElementById('panel')).minHeight), '700px');
    await page.waitForFunction(() => document.querySelector('article iframe:not(.frame-pending)')?.style.height === '700px');
    // The keyboard shrinking the WebView arrives as a plain viewport change.
    await page.setViewportSize({ width: 420, height: 420 });
    await frame.waitForFunction(() => getComputedStyle(document.documentElement).getPropertyValue('--player-frame-vh') === '4.2px');
    assert.equal(await frame.evaluate(() => getComputedStyle(document.getElementById('panel')).minHeight), '420px');
    await page.waitForFunction(() => document.querySelector('article iframe')?.style.height === '420px');
  } finally { await browser.close(); }
});

test('keyboard-sized viewports keep the reader anchored and defer to focused author inputs', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const tall = index => card(`<body><div style="height:600px">${index}</div></body>`);
    const { page, push } = await open(browser, { width: 420, height: 700 },
      [tall(1), tall(2), card('<body><div style="height:520px">3</div><input id="field"></body>')]);
    await page.waitForFunction(() => { const items = [...document.querySelectorAll('article iframe:not(.frame-pending)')]; return items.length === 3 && items.every(item => parseFloat(item.style.height) > 100); });
    await page.evaluate(() => { window.scrollTo(0, document.documentElement.scrollHeight); window.dispatchEvent(new Event('scroll')); });
    assert.equal(await page.evaluate(() => document.getElementById('bottom').hidden), true);
    // Interleave a frame measurement and a same-position scroll with the viewport change.
    // The queued bottom adjustment must survive these notifications.
    await page.evaluate(() => window.addEventListener('resize', () => {
      const frame = document.querySelector('article iframe:not(.frame-pending)');
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow, origin: new URL(frame.src).origin,
        data: { channel: 'player-frame', epoch: 'viewport', type: 'resize', height: parseFloat(frame.style.height) } }));
      window.dispatchEvent(new Event('scroll'));
    }, { capture: true, once: true }));
    await page.setViewportSize({ width: 420, height: 420 });
    await page.waitForFunction(() => document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 80 && document.getElementById('bottom').hidden);
    // Focus inside an author frame pauses automatic following so the browser can keep it in view.
    await page.bringToFront();
    const author = await authorFrame(page, '#field');
    await author.locator('#field').focus();
    await page.waitForFunction(() => document.activeElement?.tagName === 'IFRAME');
    await push(message(tall(4), 3));
    await page.waitForFunction(() => document.querySelectorAll('article iframe:not(.frame-pending)').length === 4 &&
      parseFloat(document.querySelectorAll('article iframe:not(.frame-pending)')[3].style.height) > 100);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(resolve)))));
    assert.ok(await page.evaluate(() => document.documentElement.scrollHeight - window.scrollY - window.innerHeight > 80));
    // Leaving the author frame resumes following on the next render.
    await page.evaluate(() => document.activeElement.blur());
    await page.waitForFunction(() => document.activeElement?.tagName !== 'IFRAME');
    await push(message(tall(5), 4));
    await page.waitForFunction(() => document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 80);
  } finally { await browser.close(); }
});
