import test from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';

// Architecture proof only: the production renderer still creates separate hosts.
// Same-origin author frames can share live objects without entering the trusted shell.
test('author session coordinator preserves shared objects and synchronous callbacks across frames', async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => {
      const url = new URL(route.request().url());
      let body;
      if (url.hostname === 'player.invalid') body = `<script>window.PlayerBridge={secret:'native-only'}</script>
        <iframe name="author_session" sandbox="allow-scripts allow-same-origin" src="https://author.invalid/session"></iframe>
        <iframe name="author_a" sandbox="allow-scripts allow-same-origin" src="https://author.invalid/page"></iframe>
        <iframe name="author_b" sandbox="allow-scripts allow-same-origin" src="https://author.invalid/page"></iframe>`;
      else if (url.pathname === '/session') body = `<script>
        window.Session = {
          globals: new Map(), listeners: [],
          initialize(name, value) { this.globals.set(name, value); },
          on(owner, fn) { this.listeners.push({owner, fn}); },
          emit(value) { for (const item of [...this.listeners]) item.fn(value); },
          dispose(owner) { this.listeners = this.listeners.filter(item => item.owner !== owner); }
        };
      </script>`;
      else body = '<body>Author page</body>';
      return route.fulfill({ status: 200, contentType: 'text/html', body });
    });
    await page.goto('https://player.invalid/');
    const author = name => page.frames().find(frame => frame.name() === name);
    const a = author('author_a'), b = author('author_b');
    assert.ok(a && b);
    await a.evaluate(() => {
      window.session = top.frames.author_session.Session;
      window.local = { count: 1, increment() { this.count++; } };
      session.initialize('shared', local);
      session.on('a', value => { value.fromA = true; local.increment(); });
    });
    assert.equal(await b.evaluate(() => {
      window.session = top.frames.author_session.Session;
      const value = session.globals.get('shared');
      const payload = {};
      session.on('b', data => { data.sameObject = value === session.globals.get('shared'); });
      session.emit(payload);
      return payload.fromA && payload.sameObject && value.count === 2;
    }), true);
    assert.equal(await a.evaluate(() => session.globals.get('shared') === local && local.count === 2), true);
    assert.equal(await b.evaluate(() => {
      try { return top.PlayerBridge.secret; } catch { return 'isolated'; }
    }), 'isolated');
    await a.evaluate(() => session.dispose('a'));
    await page.evaluate(() => document.querySelector('[name="author_a"]').remove());
    assert.equal(await b.evaluate(() => {
      const payload = {}; session.emit(payload);
      return !payload.fromA && payload.sameObject && session.globals.get('shared').count === 2;
    }), true);
  } finally { await browser.close(); }
});
