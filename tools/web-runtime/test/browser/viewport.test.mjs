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

test('collapsed streaming reasoning lets the reader leave the bottom in small steps', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page, push } = await open(browser, { width: 420, height: 700 },
      Array.from({ length: 15 }, (_, i) => `History ${i}\n\nA previous conversation paragraph.`));
    await push({ ...message('', 15), status: 'STREAMING', reasoning: ['Thinking'] });
    await page.waitForFunction(() => document.querySelectorAll('article').length === 16 &&
      document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 1);
    assert.equal(await page.locator('article details').evaluate(node => node.open), false);
    const initial = await page.evaluate(() => window.scrollY);
    const update = async index => {
      await page.evaluate(index => Player.receive({ type: 'delta', epoch: 'viewport', changes: {}, flags: { busy: true, running: true },
        messages: [{ ...window.__streamMessage, reasoning: ['Thinking '.repeat(index)] }] }), index);
    };
    await page.evaluate(value => { window.__streamMessage = value; },
      { ...message('', 15), status: 'STREAMING', reasoning: ['Thinking'] });
    await page.mouse.move(200, 350);
    await page.mouse.wheel(0, -20);
    await page.waitForFunction(initial => window.scrollY < initial, initial);
    const reading = await page.evaluate(() => window.scrollY);
    assert.ok(initial - reading < 80);
    for (let i = 2; i <= 6; i++) {
      await update(i);
      await page.waitForFunction(i => document.querySelector('article details div')?.textContent === 'Thinking '.repeat(i), i);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.equal(await page.evaluate(() => window.scrollY), reading);
    }
    assert.equal(await page.locator('#bottom').isVisible(), true);
    await page.locator('#bottom').click();
    await page.waitForFunction(() => document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 1);
  } finally { await browser.close(); }
});

test('resize notifications do not alter layout while real script errors remain visible', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page } = await open(browser, { width: 420, height: 700 },
      [card('<body><div id="panel" style="height:200px"></div></body>')]);
    const frame = await authorFrame(page, '#panel');
    await page.waitForFunction(() => document.querySelector('article iframe:not(.frame-pending)')?.style.height === '200px');
    const parent = page.frames().find(f => f.url().startsWith(origin + '/frame/') && f.name() !== 'player_author_session');
    const warnings = [];
    page.on('console', message => { if (message.type() === 'warning') warnings.push(message.text()); });
    await frame.evaluate(() => {
      for (const message of ['ResizeObserver loop completed with undelivered notifications.', 'ResizeObserver loop limit exceeded']) {
        window.dispatchEvent(new ErrorEvent('error', { message, cancelable: true }));
      }
    });
    assert.equal(await parent.locator('#error').textContent(), '');
    assert.equal(await page.locator('#notice').textContent(), '');
    assert.equal(warnings.length, 2);
    // Trigger the browser's actual loop protection, not just a synthetic ErrorEvent.
    await frame.evaluate(() => new Promise(resolve => {
      const panel = document.getElementById('panel');
      const observer = new ResizeObserver(() => { panel.style.width = (panel.offsetWidth + 1) + 'px'; });
      const onError = event => {
        if (event.message !== 'ResizeObserver loop completed with undelivered notifications.') return;
        observer.disconnect(); window.removeEventListener('error', onError); resolve();
      };
      window.addEventListener('error', onError);
      observer.observe(panel);
    }));
    assert.equal(await parent.locator('#error').textContent(), '');
    assert.equal(await page.locator('#notice').textContent(), '');
    for (const height of [600, 120]) {
      await frame.evaluate(height => { document.getElementById('panel').style.height = height + 'px'; }, height);
      await page.waitForFunction(height => document.querySelector('article iframe')?.style.height === height + 'px', height);
    }
    // A same-height viewport refresh must not write or report unchanged dimensions.
    await parent.evaluate(() => {
      window.__heightWrites = 0;
      new MutationObserver(records => window.__heightWrites += records.length)
        .observe(document.getElementById('page'), { attributes: true, attributeFilter: ['style'] });
    });
    await page.evaluate(() => {
      window.__resizeReports = 0;
      window.addEventListener('message', event => { if (event.data?.type === 'resize') window.__resizeReports++; });
    });
    await page.setViewportSize({ width: 420, height: 500 });
    await frame.waitForFunction(() => getComputedStyle(document.documentElement).getPropertyValue('--player-frame-vh') === '5px');
    await parent.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await parent.evaluate(() => window.__heightWrites), 0);
    assert.equal(await page.evaluate(() => window.__resizeReports), 0);
    await frame.evaluate(() => setTimeout(() => { throw new Error('Author execution failed'); }, 0));
    await parent.waitForFunction(() => document.getElementById('error').textContent.includes('Author execution failed'));
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('Author execution failed'));
  } finally { await browser.close(); }
});

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
