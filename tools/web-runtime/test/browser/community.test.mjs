import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright-core';

const sampleHash = '68c9429e69a9c38d8e8b79cace03675c99ca48830ed25a49ac89ce61dda961a9';
async function sample() {
  const directory = new URL('../../../../source/', import.meta.url);
  for (const name of await readdir(directory).catch(() => [])) {
    if (!name.endsWith('.json')) continue;
    const bytes = await readFile(new URL(encodeURIComponent(name), directory));
    if (createHash('sha256').update(bytes).digest('hex') === sampleHash) return JSON.parse(bytes);
  }
}

test('C-05 original form fills the shared draft without automatically sending', async t => {
  const card = await sample();
  if (!card) { t.skip('Optional local C-05 source hash is unavailable'); return; }
  const data = card.data ?? card;
  const display = data.extensions.regex_scripts[0].replaceString;
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage(), root = 'https://player.invalid', origin = 'https://card.test', epoch = 'community';
    const frames = new Map(), errors = [], calls = [];
    let serial = 0;
    let snapshot = { conversationId: 'c', revision: 'r0', chatVariables: {}, scriptVariables: {}, draft: '', mvu: null, worldbooks: [],
      program: { sources: [] }, presetProgram: { sources: [] }, presetHash: 'preset',
      messages: [{ id: 'm', turnId: 't', variantId: 'v', message_id: 0, name: 'Actor', role: 'assistant', status: 'COMPLETE',
        message: display, display, data: {}, extra: {}, swipes: [display], swipes_data: [{}], swipes_info: [{}], swipe_id: 0, is_hidden: false, reasoning: [] }] };
    const flags = { busy: false, running: false };
    page.on('pageerror', error => errors.push(error.name));
    await page.exposeBinding('_testPost', async (_, raw) => {
      const req = JSON.parse(raw); let result = null;
      calls.push(req.method);
      if (req.method === 'ready') await page.evaluate(packet => Player.receive(packet), { type: 'snapshot', epoch, snapshot, flags });
      else if (req.method === 'frame.create') {
        const token = String(++serial);
        frames.set(token, { html: req.args.html, kind: req.args.kind, actor: { id: token, turnId: 't', variantId: 'v' }, snapshot, epoch, rootOrigin: root, viewportHeight: 700 });
        result = { token, url: origin + '/frame/' + token };
      } else if (req.method === 'frame.dispose') frames.delete(req.args.token);
      else if (req.method === 'host.draft.replace') {
        snapshot = { ...snapshot, revision: 'r' + (++serial), draft: req.args.text }; result = { snapshot };
      } else throw new Error('Unexpected host operation: ' + req.method);
      await page.evaluate(packet => Player.receive(packet), { type: 'result', id: req.id, result });
    });
    await page.addInitScript(() => {
      if (location.origin === 'https://player.invalid') window.PlayerBridge = { postMessage: raw => window._testPost(raw) };
    });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      let body, contentType;
      if (url.pathname.startsWith('/frame/')) {
        body = (await readFile('build/app-assets/web/parent.html', 'utf8'))
          .replace('__PLAYER_CONFIGURATION__', JSON.stringify(frames.get(url.pathname.split('/').at(-1))).replaceAll('<', '\\u003c'));
        contentType = 'text/html';
      } else {
        const name = url.pathname.split('/').at(-1);
        body = await readFile('build/app-assets/web/' + name);
        contentType = name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript';
      }
      await route.fulfill({ status: 200, contentType, body });
    });
    await page.goto(root + '/web/index.html');
    const form = page.frameLocator('article iframe').frameLocator('#page');
    await form.locator('#name').fill('Sample User');
    await form.locator('#time').fill('14:00');
    await form.locator('.reason-checkbox').first().check();
    await form.locator('#desc').fill('Sample concern');
    await form.locator('#dietary').fill('Sample preference');
    await form.locator('#confirm-btn').click();
    await page.waitForFunction(() => document.getElementById('notice').textContent.length > 0);
    // Flush the production JS command queue, including the author's native input event.
    const parent = page.frames().find(frame => frame.url().startsWith(origin + '/frame/') && frame.name() !== 'player_author_session');
    await parent.waitForFunction(() => document.getElementById('send_textarea').value.includes('Sample concern'));
    for (let i = 0; i < 100 && !snapshot.draft.includes('Sample concern'); i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(['Sample User', '14:00', 'Sample concern', 'Sample preference'].every(value => snapshot.draft.includes(value)));
    assert.equal(calls.includes('host.chat.send'), false);
    assert.deepEqual(errors, []);
    // A normal host refresh must preserve entered fields and installed handlers.
    await page.evaluate(packet => Player.receive(packet), { type: 'snapshot', epoch, snapshot, flags });
    assert.equal(await form.locator('#desc').inputValue(), 'Sample concern');
    await mkdir('build', { recursive: true });
    await writeFile('build/community-form-audit.json', JSON.stringify({ sample: 'C-05', sha256: sampleHash,
      browser: browser.version(), draftPopulated: true, automaticSend: false, preservedOnRefresh: true, pageErrors: errors.length }, null, 2));
  } finally { await browser.close(); }
});
