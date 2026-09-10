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
      '$(errorCatched(()=>{window.boots=(window.boots||0)+1;document.getElementById("count").textContent=String(window.boots);' +
      'document.getElementById("change").onclick=()=>{insertOrAssignVariables({score:3});document.getElementById("count").textContent=String(getAllVariables().score)};' +
      'document.getElementById("send").onclick=()=>{parent.$("#send_textarea").val("From card");parent.$("#send_but").click()};' +
      '}));</script></body>\n```';
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
        const message = snapshot.messages.find(value => value.id === req.args.messageId);
        const script = [snapshot.program, snapshot.presetProgram].flatMap(value => value.sources ?? []).find(value => value.id === req.args.sourceId);
        frames.set(token, { html: script?.content ?? req.args.html, kind: req.args.kind,
          actor: { id: token, turnId: message?.turnId, variantId: message?.variantId, scriptId: script?.id }, snapshot, epoch, rootOrigin: root, viewportHeight: 700 });
        result = { token, url: origin + '/frame/' + token };
      } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
      else if (req.method === 'host.variables.replace') {
        snapshot = { ...snapshot, revision: 'r1', chatVariables: req.args.data }; result = { snapshot };
      } else if (req.method === 'host.draft.replace') {
        snapshot = { ...snapshot, revision: 'r2', draft: req.args.text }; result = { snapshot };
      } else if (req.method === 'host.chat.send') { sent++; result = { snapshot }; }
      else if (req.method === 'host.messages.create') {
        const incoming = req.args.messages.map((value, i) => ({ ...snapshot.messages[0], ...value, id: 'created-' + i, turnId: 'created-' + i,
          variantId: 'created-' + i, display: value.message, swipes: [value.message], swipes_data: [{}], data: {}, extra: {} }));
        snapshot = { ...snapshot, revision: 'structure-' + (++number), messages: [...incoming, ...snapshot.messages].map((value, message_id) => ({ ...value, message_id })) };
        result = { snapshot, refresh: { mode: 'affected', messageIds: [] } };
      } else if (req.method === 'host.messages.delete') {
        snapshot = { ...snapshot, revision: 'structure-' + (++number), messages: snapshot.messages.filter(m => !req.args.message_ids.includes(m.message_id)).map((m, message_id) => ({ ...m, message_id })) };
        result = { snapshot, refresh: { mode: 'affected', messageIds: [] } };
      }
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
    await frame.waitForFunction(() => typeof errorCatched === 'function' && window.boots === 1);
    assert.equal(await frame.evaluate(async () => await errorCatched(async () => 42)()), 42);
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

    await frame.evaluate(() => {
      initializeGlobal('Shared', { count: 0, increment() { this.count++; } });
      window.nativeUpdates = 0;
      eventOn(tavern_events.MESSAGE_UPDATED, () => nativeUpdates++);
      eventOn('cross-page', value => { Shared.increment(); value.first = true; });
    });
    const secondDisplay = '```html\n<body><p id="second">Second</p><script>' +
      '$(errorCatched(async()=>{await waitGlobalInitialized("Shared");window.nativeUpdates=0;' +
      'eventOn(tavern_events.MESSAGE_UPDATED,()=>nativeUpdates++);' +
      'eventMakeFirst("cross-page",value=>{value.before=Shared.count;Shared.increment()});' +
      'initializeGlobal("Second",{alive:true});window.started=true}));</script></body>\n```';
    const secondMessage = { ...snapshot.messages[0], id: 'second', turnId: 'second', variantId: 'second', message_id: 3,
      message: secondDisplay, display: secondDisplay, swipes: [secondDisplay] };
    snapshot = { ...snapshot, messages: [...snapshot.messages, secondMessage] };
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await page.waitForFunction(() => document.querySelectorAll('article').length === 4);
    let second;
    for (let i = 0; i < 100 && !second; i++) {
      second = page.frames().find(candidate => candidate.url() === 'about:srcdoc' && candidate !== frame &&
        candidate.parentFrame()?.url().endsWith('/' + number));
      if (!second) await new Promise(resolve => setTimeout(resolve, 20));
    }
    assert.ok(second); await second.waitForFunction(() => window.started);
    assert.deepEqual(await second.evaluate(() => {
      const value = {}; eventEmitAndWait('cross-page', value);
      return { ...value, count: Shared.count, same: Shared === parent.Shared };
    }), { before: 0, first: true, count: 2, same: true });
    assert.equal(await frame.evaluate(() => Shared.count), 2);
    // One native event is emitted once into the shared registry, not once per page.
    snapshot = { ...snapshot, messages: snapshot.messages.map((message, i) => i ? message : { ...message, message: message.message + '\nChanged' }) };
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await frame.waitForFunction(() => nativeUpdates === 1);
    assert.equal(await second.evaluate(() => nativeUpdates), 1);
    // Removal cleans owned callbacks/globals while the coordinator and first page survive.
    snapshot = { ...snapshot, messages: snapshot.messages.slice(0, 3) };
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await frame.waitForFunction(() => typeof Second === 'undefined');
    assert.deepEqual(await frame.evaluate(() => { const value = {}; eventEmitAndWait('cross-page', value); return { ...value, count: Shared.count }; }),
      { first: true, count: 3 });
    assert.equal(await page.locator('iframe[name="player_author_session"]').count(), 1);

    const source = { id: 'preset-script', enabled: true, sha256: 'one',
      content: "initializeGlobal('PresetLibrary',{version:1});eventOn('preset-event',()=>Shared.increment());" };
    snapshot = { ...snapshot, presetHash: 'p1', presetProgram: { sources: [source] } };
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await frame.waitForFunction(() => typeof PresetLibrary !== 'undefined' && PresetLibrary.version === 1);
    snapshot = { ...snapshot, presetHash: 'p2', presetProgram: { sources: [{ ...source, sha256: 'two',
      content: "initializeGlobal('PresetLibrary',{version:2});eventOn('preset-event',()=>{Shared.count+=10});" }] } };
    // A running generation retains its captured preset runtime.
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags: { ...flags, running: true } });
    assert.equal(await frame.evaluate(() => PresetLibrary.version), 1);
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    await frame.waitForFunction(() => PresetLibrary?.version === 2);
    assert.equal(await frame.evaluate(() => { eventEmitAndWait('preset-event'); return Shared.count; }), 13);
    assert.equal(await page.locator('iframe[name="player_author_session"]').count(), 1);

    // Structural writes preserve surviving author frames and their changed floor identity.
    await frame.evaluate(() => { window.sentEvents = 0; eventOn(tavern_events.MESSAGE_SENT, () => sentEvents++); });
    await frame.evaluate(() => createChatMessages([{ role: 'user', message: 'Inserted' }], { insert_before: 0 }));
    await frame.waitForFunction(() => getCurrentMessageId() === 1 && sentEvents === 1);
    assert.equal(await frame.evaluate(() => window.boots), 1);
    await page.evaluate(data => Player.receive(data), { type: 'snapshot', epoch, snapshot, flags });
    assert.equal(await frame.evaluate(() => sentEvents), 1);
    await frame.evaluate(() => deleteChatMessages([0]));
    await frame.waitForFunction(() => getCurrentMessageId() === 0);
    assert.equal(await frame.evaluate(() => window.boots), 1);

  } finally { await browser.close(); }
});
