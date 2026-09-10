import $ from 'jquery';
import { createHost } from './host.mjs';
import { createSession } from './session.mjs';

function main() {
  const config = JSON.parse(document.getElementById('configuration').textContent);
  const page = document.getElementById('page'), pending = new Map();
  let sequence = 0, disposed = false;
  const send = data => window.parent.postMessage({ ...data, channel: 'player-frame', epoch: config.epoch }, config.rootOrigin);
  function notify(level, message) {
    if (level === 'error') { document.getElementById('error').textContent = String(message); measure(); }
    send({ type: 'notice', level, message });
  }
  if (config.kind === 'session') {
    const session = createSession(config.snapshot);
    globalThis.PlayerAuthorSession = session;
    window.addEventListener('message', async event => {
      if (event.source !== window.parent || event.origin !== config.rootOrigin || event.data?.epoch !== config.epoch) return;
      const data = event.data;
      if (data.type === 'snapshot') session.receive(data.snapshot);
      else if (data.type === 'dispose-owner') session.disposeFrame(data.token);
      else if (data.type === 'fatal') session.stop(new Error(data.message));
      else if (data.type === 'dispose') session.destroy();
      else if (data.type === 'event') {
        try { await session.emit(data.name, data.args); send({ type: 'event-ack', id: data.id }); }
        catch (error) { notify('error', error.message); send({ type: 'event-ack', id: data.id, error: error.message }); }
      }
    });
    window.addEventListener('pagehide', () => session.destroy(), { once: true });
    page.remove(); send({ type: 'loaded' }); return;
  }
  const session = window.parent.frames['player_author_session']?.PlayerAuthorSession;
  if (!session) throw new Error('Author session is unavailable');
  const host = createHost({ initial: session.state, actor: config.actor, session,
    request: (method, args, revision) => new Promise((resolve, reject) => {
      const id = String(++sequence); pending.set(id, { resolve, reject });
      send({ type: 'request', id, method, args, revision });
    }), notify,

  });
  Object.assign(globalThis, { $, jQuery: $ });
  if (config.kind !== 'static') {
    Object.assign(globalThis, host.api); globalThis.TavernHelper = host.api; host.install(globalThis);
  }
  host.observe(state => {
    const input = document.getElementById('send_textarea');
    if (input) input.value = state.draft ?? '';
  });
  host.onDispose(() => {
    disposed = true; page.remove();
    for (const item of pending.values()) item.reject(new Error('Runtime disposed'));
    pending.clear();
  });
  host.onStop(error => {
    for (const item of pending.values()) item.reject(error);
    pending.clear();
  });
  window.addEventListener('pagehide', () => host.dispose(), { once: true });
  let draftQueue = Promise.resolve();
  function draftChanged() {
    const text = document.getElementById('send_textarea').value;
    draftQueue = host.enqueue('draft.replace', { text }, current => { current.draft = text; });
    draftQueue.catch(() => {});
  }
  $('#send_textarea').on('input change', draftChanged);
  $('#send_but').on('click', () => {
    // .val(...); .click() is valid even when the author does not dispatch an input event.
    draftChanged();
    draftQueue.then(() => host.enqueue('chat.send', {})).catch(error => notify('error', error.message));
  });

  globalThis.PlayerFrame = {
    install(target) {
      if (config.kind !== 'static') {
        Object.assign(target, host.api);
        target.TavernHelper = host.api;
        host.install(target);
        target.addEventListener('pagehide', () => host.dispose(), { once: true });
      }
      target.addEventListener('error', event => notify('error', event.message || '网页资源或程序执行失败'));
      target.addEventListener('unhandledrejection', event => notify('error', event.reason?.message || '网页操作失败'));
      target.addEventListener('load', () => {
        new target.ResizeObserver(measure).observe(target.document.body);
        measure(); send({ type: 'loaded' });
      });
    },
  };

  function measure() {
    if (disposed) return;
    try {
      const body = page.contentDocument?.body;
      if (body) page.style.height = Math.min(100000, Math.max(1, body.scrollHeight, body.offsetHeight)) + 'px';
      send({ type: 'resize', height: Math.min(100000, document.body.scrollHeight) });
    } catch { notify('error', '页面导航超出兼容运行范围'); }
  }

  window.addEventListener('message', async event => {
    if (event.source !== window.parent || event.origin !== config.rootOrigin || event.data?.epoch !== config.epoch) return;
    const data = event.data;
    if (data.type === 'result') {
      const item = pending.get(data.id); if (!item) return;
      pending.delete(data.id); data.error ? item.reject(new Error(data.error)) : item.resolve(data.result);
    } else if (data.type === 'fatal') {
      session.stop(new Error(data.message));
      for (const p of pending.values()) p.reject(new Error(data.message));
      pending.clear();
    } else if (data.type === 'dispose') {
      host.dispose();
    }
  });

  function inject(html, prefix) {
    if (/<head(?:\s[^>]*)?>/i.test(html)) return html.replace(/<head(?:\s[^>]*)?>/i, match => match + prefix);
    if (/<html(?:\s[^>]*)?>/i.test(html)) return html.replace(/<html(?:\s[^>]*)?>/i, match => match + '<head>' + prefix + '</head>');
    return '<!doctype html><html><head>' + prefix + '</head><body>' + html + '</body></html>';
  }
  const origin = location.origin;
  const libraries = config.kind === 'static' ? '' : ['jquery', 'lodash', 'vue', 'libraries'].map(name => `<script src="${origin}/web/${name}.js"></script>`).join('');
  const prefix = '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' + libraries +
    '<script>parent.PlayerFrame.install(window);</script>' +
    `<style>html{color-scheme:light dark}body{margin:0;overflow-wrap:anywhere}img{max-width:100%;height:auto}</style>`;
  let html = config.html;
  if (config.kind === 'script') html = '<body><script type="module">' + html.replace(/<\/script/gi, '<\\/script') + '</script></body>';
  // Match the source renderer's viewport-height correction without touching JavaScript strings.
  html = html.replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, style => style.replace(/min-height\s*:\s*([\d.]+)vh/gi,
    (_, amount) => `min-height:${Number(amount) * (config.viewportHeight || 800) / 100}px`));
  page.srcdoc = inject(html, prefix);
  if (config.kind === 'script') page.hidden = true;
}
main();
