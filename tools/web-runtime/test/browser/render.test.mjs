import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const root = 'https://player.invalid', origin = 'https://card.test', epoch = 'render';
const message = (display, index, extra = {}) => ({ id: 'm' + index, turnId: 't' + index, variantId: 'v' + index, message_id: index,
  name: 'Actor', role: 'assistant', status: 'COMPLETE', message: display, display, data: {}, extra: {},
  swipes: [display], swipes_data: [{}], swipes_info: [{}], swipe_id: 0, is_hidden: false, reasoning: [], ...extra });

// Host stand-in: the shell only reaches native through PlayerBridge, answered here by a binding.
async function open(browser, viewport, displays) {
  const page = await browser.newPage({ viewport });
  const frames = new Map(), created = []; let serial = 0, snapshot;
  const build = list => ({ conversationId: 'c', revision: 'r0', chatVariables: {}, scriptVariables: {}, draft: '', mvu: null,
    worldbooks: [], program: { sources: [] }, presetProgram: { sources: [] }, presetHash: 'preset', messages: list });
  snapshot = build(displays);
  const flags = { busy: false, running: false };
  await page.exposeBinding('_testPost', async (_, raw) => {
    const req = JSON.parse(raw); let result = null;
    if (req.method === 'ready') await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    else if (req.method === 'frame.create') {
      const token = String(++serial);
      const target = snapshot.messages.find(value => value.id === req.args.messageId);
      created.push({ token, kind: req.args.kind });
      frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token, turnId: target?.turnId, variantId: target?.variantId },
        snapshot: req.args.kind === 'session' ? snapshot : undefined, epoch, rootOrigin: root, viewportHeight: 700 });
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
  return { page, created,
    messages: () => snapshot.messages,
    set: async list => { snapshot = build(list); await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags }); } };
}

// The author page runs inside parent.html, so it is the nested frame reached through its own DOM.
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
// Addresses the newest page frame through its own parent URL, so a rebuilt page can never be
// confused with the frame it replaced.
async function rebuilt(page, created) {
  const token = created.filter(item => item.kind === 'page').at(-1)?.token;
  for (let i = 0; i < 200; i++) {
    const parent = page.frames().find(candidate => candidate.url() === origin + '/frame/' + token);
    for (const child of parent?.childFrames() ?? []) {
      try { if (await child.locator('body').count()) return child; } catch {}
    }
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Rebuilt author frame not found for token ' + token);
}
const pages = created => created.filter(item => item.kind === 'page').length;
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

const tall = 'Filler\n\n<div style="height:900px">filler block</div>';
const rich = extra => `Intro ${extra}\n\n<div id="tall" style="height:520px">tall</div>`;
const fence = prose => prose + '\n\n```html\n<body><input id="field"><div id="hit">hit</div>' +
  '<script>window.boots=(window.boots||0)+1;window.stamp=Math.random();</script></body>\n```';

// Samples geometry from the observer, the scroll listener and every animation frame. A reader who
// loses the document height between two frames is only visible in this trace.
async function watch(page) {
  await page.evaluate(() => {
    window.__trace = [];
    const record = () => window.__trace.push({ h: document.documentElement.scrollHeight, y: Math.round(window.scrollY) });
    new MutationObserver(record).observe(document.getElementById('messages'),
      { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['style'] });
    window.addEventListener('scroll', record, { passive: true });
    const tick = () => { record(); window.__raf = requestAnimationFrame(tick); };
    tick();
  });
}
const drain = page => page.evaluate(() => { const trace = window.__trace; window.__trace = []; return trace; });
const geometry = page => page.evaluate(() => ({ h: document.documentElement.scrollHeight, y: Math.round(window.scrollY) }));
const lowest = (values, start) => values.reduce((low, value) => Math.min(low, value), start);

test('streaming rich updates keep the document height and a bottom reader in place', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page, messages, set } = await open(browser, { width: 420, height: 700 }, [message(tall, 0), message(rich('start'), 1)]);
    await page.waitForFunction(() => { const items = [...document.querySelectorAll('article iframe')];
      return items.length === 2 && items.every(item => parseFloat(item.style.height) > 100); });
    await page.evaluate(() => { window.scrollTo(0, document.documentElement.scrollHeight); window.dispatchEvent(new Event('scroll')); });
    assert.equal(await page.evaluate(() => document.getElementById('bottom').hidden), true);
    await wait(150);
    await watch(page);
    for (let i = 0; i < 8; i++) {
      const before = await geometry(page); await drain(page);
      const list = messages();
      await set([list[0], { ...list[1], display: rich('t' + i), message: rich('t' + i) }]);
      await wait(200);
      const trace = await drain(page), after = await geometry(page);
      assert.ok(lowest(trace.map(item => item.h), before.h) >= before.h - 40,
        `update ${i} dropped the document height: ${before.h} -> ${lowest(trace.map(item => item.h), before.h)}`);
      assert.ok(lowest(trace.map(item => item.y), before.y) >= before.y - 40,
        `update ${i} pulled the bottom reader up: ${before.y} -> ${lowest(trace.map(item => item.y), before.y)}`);
      assert.ok(after.y >= before.y - 40, `update ${i} left the bottom reader at ${after.y} (was ${before.y})`);
      assert.equal(await page.evaluate(() => document.getElementById('bottom').hidden), true);
    }
    // A reader 200px above the bottom keeps both the position and the return-to-latest control.
    const away = await page.evaluate(() => { window.scrollTo(0, document.documentElement.scrollHeight - window.innerHeight - 200);
      window.dispatchEvent(new Event('scroll')); return { y: Math.round(window.scrollY), hidden: document.getElementById('bottom').hidden }; });
    assert.equal(away.hidden, false);
    await wait(150);
    const list = messages();
    await set([list[0], { ...list[1], display: rich('away'), message: rich('away') }]);
    await wait(300);
    const after = await page.evaluate(() => ({ y: Math.round(window.scrollY), hidden: document.getElementById('bottom').hidden }));
    assert.ok(Math.abs(after.y - away.y) <= 4, `a history reader moved from ${away.y} to ${after.y}`);
    assert.equal(after.hidden, false);
  } finally { await browser.close(); }
});

test('prose and reasoning updates keep the mounted author page, its input and its listeners', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page, created, messages, set } = await open(browser, { width: 420, height: 700 }, [message(fence('Prose A'), 0)]);
    let frame = await authorFrame(page, '#field');
    await frame.locator('#field').fill('unsaved text');
    const before = await frame.evaluate(() => {
      window.marker = Math.random(); window.clicks = 0;
      document.getElementById('hit').addEventListener('click', () => { window.clicks++; });
      document.getElementById('hit').click();
      return { stamp: window.stamp, marker: window.marker, clicks: window.clicks, value: document.getElementById('field').value };
    });
    assert.equal(before.clicks, 1);
    const state = async () => {
      const live = await authorFrame(page, '#field');
      return live.evaluate(() => { const before = window.clicks; document.getElementById('hit').click();
        return { survived: window.clicks === before + 1, stamp: window.stamp, marker: window.marker,
          value: document.getElementById('field').value, boots: window.boots }; });
    };
    const kept = { stamp: before.stamp, marker: before.marker, value: before.value, boots: 1, survived: true };
    // Prose-only change: the fenced page source is identical, so its DOM, input and listeners stay.
    let list = messages();
    await set([{ ...list[0], display: fence('Prose B'), message: fence('Prose B') }]);
    await wait(300);
    assert.deepEqual(await state(), kept);
    // Reasoning-only change: the thought block is rewritten, the page is untouched.
    list = messages();
    await set([{ ...list[0], reasoning: ['thought one'] }]);
    await wait(300);
    assert.deepEqual(await state(), kept);
    // A repeated snapshot is a no-op for the page as well.
    await set(messages());
    await wait(300);
    assert.deepEqual(await state(), kept);
    assert.equal(pages(created), 1);
    assert.equal(await page.locator('article details div').textContent(), 'thought one');
  } finally { await browser.close(); }
});

test('status text and the page placeholder follow the message status independently', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const { page, created, messages, set } = await open(browser, { width: 420, height: 700 },
      [message(fence('Waiting'), 0, { status: 'STREAMING' })]);
    await page.waitForFunction(() => document.querySelector('article .status')?.textContent === '正在生成…');
    assert.deepEqual(await page.evaluate(() => { const row = document.querySelector('article');
      row.querySelector('.status').dataset.probe = 'kept'; row.querySelector('.runtime-loading').dataset.probe = 'kept';
      return { status: row.querySelector('.status').textContent, waiting: row.querySelector('.runtime-loading').textContent,
        frames: row.querySelectorAll('iframe').length }; }),
      { status: '正在生成…', waiting: '回复完成后显示交互界面', frames: 0 });
    const expected = { CANCELLED: ['已停止', '已停止生成，交互界面未装载'], INTERRUPTED: ['生成已中断', '生成已中断，交互界面未装载'],
      ERROR: ['生成失败', '生成失败，交互界面未装载'] };
    for (const [status, [label, waiting]] of Object.entries(expected)) {
      const list = messages();
      await set([{ ...list[0], status }]);
      await wait(200);
      assert.deepEqual(await page.evaluate(() => { const row = document.querySelector('article');
        return { status: row.querySelector('.status').textContent, waiting: row.querySelector('.runtime-loading').textContent,
          statusKept: row.querySelector('.status').dataset.probe, waitingKept: row.querySelector('.runtime-loading').dataset.probe,
          frames: row.querySelectorAll('iframe').length }; }),
        { status: label, waiting, statusKept: 'kept', waitingKept: 'kept', frames: 0 });
    }
    const list = messages();
    await set([{ ...list[0], status: 'COMPLETE' }]);
    await page.waitForFunction(() => document.querySelector('article iframe')?.style.height);
    assert.deepEqual(await page.evaluate(() => { const row = document.querySelector('article');
      return { status: row.querySelector('.status'), loading: row.querySelector('.runtime-loading'), frames: row.querySelectorAll('iframe').length,
        name: row.querySelector('header span').textContent }; }),
      { status: null, loading: null, frames: 1, name: 'Actor' });
    assert.equal(pages(created), 1);
  } finally { await browser.close(); }
});

test('a candidate or page source change rebuilds the mounted author page', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page1 = 'Doc One\n\n```html\n<body><p id="slot">one</p><script>window.stamp=Math.random();</script></body>\n```';
    const page2 = 'Doc Two\n\n```html\n<body><p id="slot">two</p><script>window.stamp=Math.random();</script></body>\n```';
    const { page, created, messages, set } = await open(browser, { width: 420, height: 700 }, [message(page1, 0)]);
    let frame = await authorFrame(page, '#slot');
    await frame.waitForFunction(() => document.getElementById('slot').textContent === 'one');
    const first = await frame.evaluate(() => window.stamp);
    assert.equal(pages(created), 1);
    // A new candidate invalidates the mounted page even though its rendered source is identical.
    let list = messages();
    await set([{ ...list[0], variantId: 'v0-2' }]);
    frame = await rebuilt(page, created);
    await frame.waitForFunction(() => document.getElementById('slot').textContent === 'one');
    await frame.waitForFunction(stamp => typeof window.stamp === 'number' && window.stamp !== stamp, first);
    assert.equal(pages(created), 2);
    // A changed fenced source rebuilds the page as well.
    list = messages();
    await set([{ ...list[0], variantId: 'v0-3', display: page2, message: page2 }]);
    frame = await rebuilt(page, created);
    await frame.waitForFunction(() => document.getElementById('slot').textContent === 'two');
    assert.equal(pages(created), 3);
    assert.equal(await page.locator('article iframe').count(), 1);
    assert.equal(await page.locator('article .runtime-loading').count(), 0);
    assert.equal(await page.locator('article .status').count(), 0);
  } finally { await browser.close(); }
});

// 普通 Markdown 含表格、图片或内联 HTML 时整段正文落在 static 帧，帧内改用与主壳同一份排版基线；
// 作者自己的样式在文档里更靠后，仍然覆盖基线。
test('a static frame keeps the shell prose baseline while author styles still win', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const prose = 'Ordinary paragraph.\n\n| Name | Value |\n| --- | --- |\n| Alpha | 1 |\n| Beta | 2 |';
    // 段首的 <style> 会被整文档解析提升进 <head>，只序列化 body 会整块丢掉；内容中间的则留在 body。
    // 两个位置都必须生效，并且都压过基线（基线里没有 !important）。
    const styled = '<style>#lead-quote{border-left-width:7px;border-left-style:solid}</style>\n\n' +
      'Author paragraph.\n\n' +
      '<div id="tint" style="color:rgb(1, 2, 3);font-size:30px">Author styled text</div>\n\n' +
      '<style>#inner-quote{border-left-width:9px;border-left-style:solid}</style>\n\n' +
      '<blockquote id="lead-quote">Lead style block</blockquote>\n\n' +
      '<blockquote id="inner-quote">Inner style block</blockquote>\n\n' +
      '<blockquote id="quote" style="border-left:0">Author blockquote</blockquote>\n\n' +
      '<blockquote id="baseline-quote">Baseline blockquote</blockquote>';
    const authorPage = 'Doc\n\n```html\n<body><p id="author-page">Author page</p></body>\n```';
    const { page } = await open(browser, { width: 420, height: 700 }, [message(prose, 0), message(styled, 1), message(authorPage, 2)]);
    const sampled = frame => frame.evaluate(() => {
      const body = getComputedStyle(document.body), cell = document.querySelector('td');
      return { fontFamily: body.fontFamily, lineHeight: body.lineHeight, fontSize: body.fontSize,
        border: cell ? [getComputedStyle(cell).borderTopWidth, getComputedStyle(cell).borderTopStyle].join(' ') : null,
        scripts: document.querySelectorAll('script').length, host: typeof getVariables };
    });
    const proseFrame = await authorFrame(page, 'td');
    for (let i = 0; i < 100 && !(await sampled(proseFrame)).border?.startsWith('1px'); i++) await wait(20);
    const frame = await sampled(proseFrame);
    const shell = await page.evaluate(() => { const body = getComputedStyle(document.body);
      return { fontFamily: body.fontFamily, lineHeight: body.lineHeight, fontSize: body.fontSize }; });
    assert.match(frame.fontFamily, /system-ui/);
    assert.equal(frame.fontFamily, shell.fontFamily);
    assert.equal(frame.lineHeight, shell.lineHeight);
    assert.equal(frame.fontSize, shell.fontSize);
    assert.equal(frame.border, '1px solid');
    assert.equal(frame.scripts, 1); // 基线只多一个 <link>：static 帧仍然没有第二个脚本，也没有宿主接口。
    assert.equal(frame.host, 'undefined');
    const styledFrame = await authorFrame(page, '#tint');
    assert.deepEqual(await styledFrame.evaluate(() => {
      const computed = id => getComputedStyle(document.getElementById(id));
      return { color: computed('tint').color, fontSize: computed('tint').fontSize,
        quote: computed('quote').borderLeftWidth, baseline: computed('baseline-quote').borderLeftWidth,
        lead: computed('lead-quote').borderLeftWidth, inner: computed('inner-quote').borderLeftWidth };
    }), { color: 'rgb(1, 2, 3)', fontSize: '30px', quote: '0px', baseline: '3px', lead: '7px', inner: '9px' });
    // page 帧是作者自己的页面：只拿作者样式，不拿主壳的正文排版基线。
    const pageFrame = await authorFrame(page, '#author-page');
    assert.equal(await pageFrame.locator('link[href$="message.css"]').count(), 0);
  } finally { await browser.close(); }
});
