import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { tavernEvents, mvuEvents, iframeEvents } from './host.mjs';

const messagesNode = document.getElementById('messages'), actionsNode = document.getElementById('actions');
const pending = new Map(), frames = new Map(), rows = new Map(), scriptFrames = new Map(), eventAcks = new Map();
let sequence = 0, epoch = null, snapshot = null, flags = {}, shown = 50, programKey = '', following = true;
let renderQueue = Promise.resolve(), coordinator = null;
// Upstream generation notifications emit without awaiting listeners. A listener may itself await
// another generation; holding a global event queue here would deadlock its streaming notifications.
function lifecycle(name, args) { broadcast(name, args).catch(error => notice(error.message)); }
function rpc(method, args = {}, extra = {}) {
  const id = String(++sequence);
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    window.PlayerBridge.postMessage(JSON.stringify({ id, epoch, method, args, revision: snapshot?.revision, ...extra }));
  });
}
function post(frame, data) { frame.element.contentWindow?.postMessage({ ...data, epoch }, frame.origin); }
function notice(message) { document.getElementById('notice').textContent = String(message ?? ''); }
function button(text, action, disabled = false) {
  const node = document.createElement('button'); node.textContent = text; node.disabled = disabled;
  node.addEventListener('click', () => Promise.resolve(action()).catch(error => notice(error.message)));
  return node;
}
const ui = (action, id) => rpc('ui.' + action, id ? { id } : {});
function bottom() { window.scrollTo({ top: document.documentElement.scrollHeight }); }
window.addEventListener('scroll', () => {
  following = document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 80;
  document.getElementById('bottom').hidden = following;
}, { passive: true });
document.getElementById('bottom').onclick = () => { following = true; bottom(); };
document.getElementById('earlier').onclick = () => {
  const top = rows.get(snapshot.messages[snapshot.messages.length - shown]?.turnId)?.element;
  const offset = top?.getBoundingClientRect().top;
  shown += 50;
  renderQueue = renderQueue.then(() => render()).then(() => {
    if (top && offset !== undefined) window.scrollBy(0, top.getBoundingClientRect().top - offset);
  });
};

export function segments(text) {
  const result = [], tokens = marked.lexer(text); let normal = [];
  const flush = () => { if (normal.length) { result.push({ kind: 'normal', text: marked.parser(normal) }); normal = []; } };
  for (const token of tokens) {
    if (token.type === 'code' && /<body(?:\s[^>]*)?>/i.test(token.text) && /<\/body\s*>/i.test(token.text)) {
      flush(); result.push({ kind: 'page', text: token.text });
    } else normal.push(token);
  }
  flush(); return result;
}

async function createFrame(kind, html, target, sourceId) {
  const result = await rpc('frame.create', { kind, html, messageId: target?.id, sourceId, viewportHeight: window.innerHeight });
  const element = document.createElement('iframe');
  element.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  element.setAttribute('referrerpolicy', 'no-referrer'); element.title = kind === 'script' ? '会话脚本' : '消息内容';
  const frame = { element, token: result.token, origin: new URL(result.url).origin, kind, messageId: target?.message_id };
  frames.set(result.token, frame);
  if (kind === 'page') lifecycle(iframeEvents.MESSAGE_IFRAME_RENDER_STARTED, [frame.token]);
  if (kind === 'session') { element.name = 'player_author_session'; element.hidden = true; element.style.display = 'none'; }
  element.src = result.url;
  return frame;
}
function dispose(frame) {
  if (!frame) return;
  if (coordinator && frame !== coordinator) post(coordinator, { type: 'dispose-owner', token: frame.token });
  post(frame, { type: 'dispose' }); frame.element.remove(); frames.delete(frame.token);
  for (const [id, item] of eventAcks) if (item.frame === frame) { eventAcks.delete(id); item.reject(new Error('Runtime disposed')); }
  rpc('frame.dispose', { token: frame.token }).catch(() => {});
}
async function ensureCoordinator() {
  if (coordinator) return;
  const frame = await createFrame('session', '');
  coordinator = frame;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('会话运行环境启动超时')), 15000);
    frame.onLoaded = () => { clearTimeout(timer); resolve(); };
    document.body.append(frame.element);
  });
}
async function render() {
  if (!snapshot) return;
  const visible = snapshot.messages.slice(-shown), ids = new Set(visible.map(m => m.turnId));
  for (const [id, row] of rows) if (!ids.has(id)) { row.frames.forEach(dispose); row.element.remove(); rows.delete(id); }
  for (const [position, message] of visible.entries()) {
    let row = rows.get(message.turnId);
    if (!row) {
      row = { element: document.createElement('article'), frames: [], contentKey: null };
      rows.set(message.turnId, row);
    }
    row.element.dataset.role = message.role;
    const key = JSON.stringify([message.variantId, message.display, message.reasoning, message.status === 'COMPLETE']);
    if (row.contentKey !== key) {
      row.contentKey = key; row.frames.forEach(dispose); row.frames = []; row.element.replaceChildren();
      const header = document.createElement('header'), name = document.createElement('span');
      name.textContent = message.name; header.append(name, button('编辑', () => ui('edit', message.id), flags.busy));
      row.element.append(header);
      if (message.reasoning?.length) {
        const details = document.createElement('details'), summary = document.createElement('summary'), text = document.createElement('div');
        summary.textContent = '思考过程'; text.textContent = message.reasoning.join('\n\n'); details.append(summary, text); row.element.append(details);
      }
      const source = message.display ?? message.message;
      for (const part of segments(source)) {
        if (part.kind === 'page') {
          if (message.status !== 'COMPLETE') { const wait = document.createElement('p'); wait.className = 'runtime-loading'; wait.textContent = '回复完成后显示交互界面'; row.element.append(wait); continue; }
          const frame = await createFrame('page', part.text, message);
          row.frames.push(frame); row.element.append(frame.element);
        } else {
          // HTML styles never share the trusted player's document or controls.
          const rich = /<(?:style|table|div|span|form|input|img|details|section|html|body)\b/i.test(part.text);
          const clean = DOMPurify.sanitize(part.text, { ADD_TAGS: ['style'], FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'base', 'meta', 'link'],
            FORBID_ATTR: ['srcdoc'], WHOLE_DOCUMENT: false });
          if (rich) {
            const frame = await createFrame('static', '<body>' + clean + '</body>', message);
            row.frames.push(frame); row.element.append(frame.element);
          } else {
            const text = document.createElement('div');
            text.innerHTML = DOMPurify.sanitize(clean, { FORBID_TAGS: ['style'], FORBID_ATTR: ['style', 'id', 'name'] });
            row.element.append(text);
          }
        }
      }
      if (message.status !== 'COMPLETE') { const status = document.createElement('p'); status.className = 'status'; status.textContent = ({ STREAMING: '正在生成…', CANCELLED: '已停止', INTERRUPTED: '生成已中断', ERROR: '生成失败' })[message.status] ?? ''; row.element.append(status); }
      if (row.frames.length === 0 && message.status === 'COMPLETE') lifecycle(message.role === 'user' ? tavernEvents.USER_MESSAGE_RENDERED : tavernEvents.CHARACTER_MESSAGE_RENDERED, [message.message_id]);
    }
    row.element.querySelector('header button').disabled = flags.busy;
    if (messagesNode.children[position] !== row.element) messagesNode.insertBefore(row.element, messagesNode.children[position] ?? null);
  }
  document.getElementById('earlier').hidden = shown >= snapshot.messages.length;
  actionsNode.replaceChildren();
  if (flags.retryAvailable) actionsNode.append(button('重试这一轮', () => ui('retry'), flags.busy));
  const last = snapshot.messages[snapshot.messages.length - 1];
  if (last && flags.variantNavigationAvailable) {
    actionsNode.append(button('上一条', () => ui('previous'), flags.busy || last.swipe_id === 0));
    const label = document.createElement('span'); label.textContent = `${last.swipe_id + 1} / ${last.swipes.length}`; actionsNode.append(label);
    actionsNode.append(button('下一条', () => ui('next'), flags.busy || last.swipe_id === last.swipes.length - 1));
  }
  if (flags.regenerateAvailable) actionsNode.append(button('重新生成', () => ui('regenerate'), flags.busy));
  if (following) requestAnimationFrame(bottom);
  await updateScripts();
}

async function updateScripts() {
  if (flags.running || flags.browserGenerating) return;
  const key = JSON.stringify([snapshot.program, snapshot.presetHash, snapshot.presetProgram]);
  if (key === programKey) return;
  programKey = key;
  const wanted = new Map();
  for (const program of [snapshot.program, snapshot.presetProgram]) {
    if (!program) continue;
    if (program.diagnostics?.length) notice(program.diagnostics.join('\n'));
    for (const source of program.sources ?? []) if (source.enabled && !program.mvuSourceIds?.includes(source.id) && !program.blockedSourceIds?.includes(source.id)) wanted.set(source.id, source);
  }
  for (const [id, item] of scriptFrames) if (wanted.get(id)?.sha256 !== item.hash) { dispose(item.frame); item.node.remove(); scriptFrames.delete(id); }
  for (const [id, source] of wanted) if (!scriptFrames.has(id)) {
    const frame = await createFrame('script', '', null, id), node = document.createElement('span');
    frame.element.hidden = true; node.append(frame.element); document.getElementById('scripts').append(node);
    const item = { frame, node, hash: source.sha256 }; scriptFrames.set(id, item); scriptButtons(item, source.buttons ?? []);
  }
}
function scriptButtons(item, buttons) {
  item.node.querySelectorAll('button').forEach(b => b.remove());
  for (const value of buttons) if (value.visible !== false && typeof value.name === 'string') {
    item.node.append(button(value.name, () => broadcast('player_button:' + [...scriptFrames].find(([, v]) => v === item)?.[0] + ':' + value.name, [])));
  }
}
function dispatchTo(frame, name, args) {
  return new Promise((resolve, reject) => {
    const id = 'dispatch-' + (++sequence);
    // A valid listener may await a whole generation. Its lifetime, not an arbitrary
    // ten-second timeout, bounds this acknowledgement.
    eventAcks.set(id, { frame, resolve, reject });
    post(frame, { type: 'event', id, name, args });
  });
}
async function broadcast(name, args) {
  if (coordinator?.loaded) await dispatchTo(coordinator, name, args);
}
window.addEventListener('message', async event => {
  if (event.data?.channel !== 'player-frame' || event.data.epoch !== epoch) return;
  const frame = [...frames.values()].find(f => f.element.contentWindow === event.source && f.origin === event.origin);
  if (!frame) return;
  const data = event.data;
  if (data.type === 'resize') {
    const height = Number(data.height);
    if (Number.isFinite(height) && height > 0) { frame.element.style.height = Math.min(height, 100000) + 'px'; if (following) requestAnimationFrame(bottom); }
  } else if (data.type === 'loaded') {
    frame.loaded = true;
    if (frame.kind === 'session') { post(frame, { type: 'snapshot', snapshot }); frame.onLoaded?.(); return; }
    if (frame.kind === 'page') lifecycle(iframeEvents.MESSAGE_IFRAME_RENDER_ENDED, [frame.token]);
    if (frame.kind !== 'script') lifecycle(snapshot.messages[frame.messageId]?.role === 'user' ? tavernEvents.USER_MESSAGE_RENDERED : tavernEvents.CHARACTER_MESSAGE_RENDERED, [frame.messageId]);
  } else if (data.type === 'notice') {
    if (data.level === 'buttons') {
      const item = [...scriptFrames.values()].find(item => item.frame === frame); if (item && Array.isArray(data.message)) scriptButtons(item, data.message);
    } else notice(data.message);
  } else if (data.type === 'request') {
    try {
      if (frame.kind === 'static' || frame.kind === 'session') throw new Error('Static HTML has no host capability');
      const result = await rpc('host.' + data.method, data.args, { actorToken: frame.token, revision: data.revision });
      if (result.snapshot) {
        // Do not replace render metadata with the smaller authoritative host state.
        snapshot = { ...snapshot, ...result.snapshot, messages: result.snapshot.messages.map(m => ({ ...snapshot.messages.find(old => old.id === m.id), ...m })) };
        if (coordinator) post(coordinator, { type: 'snapshot', snapshot });
      }
      if (result.refresh && result.refresh.mode !== 'none') {
        const refresh = result.refresh;
        renderQueue = renderQueue.then(async () => {
          for (const message of snapshot.messages) if (refresh.mode === 'all' || refresh.messageIds.includes(message.message_id)) {
            const row = rows.get(message.turnId); if (row) row.contentKey = null;
          }
          await render();
          if (refresh.mode === 'all') lifecycle(tavernEvents.CHAT_CHANGED, [snapshot.conversationId]);
        }).catch(error => notice(error.message));
        await renderQueue;
      }
      post(frame, { type: 'result', id: data.id, result });
    } catch (error) { post(frame, { type: 'result', id: data.id, error: error.message }); }
  } else if (data.type === 'event-ack') {
    const item = eventAcks.get(data.id); if (!item || item.frame !== frame) return;
    eventAcks.delete(data.id); data.error ? item.reject(new Error(data.error)) : item.resolve();
  }
});

async function apply(packet) {
  const previous = snapshot, oldFlags = flags;
  if (packet.epoch !== epoch) { epoch = packet.epoch; shown = 50; programKey = ''; }
  if (packet.type === 'delta') {
    if (!snapshot) throw new Error('Missing initial conversation snapshot');
    const messages = new Map(snapshot.messages.map(m => [m.turnId, m]));
    for (const message of packet.messages) messages.set(message.turnId, message);
    snapshot = { ...snapshot, ...packet.changes, messages: packet.order.map(id => messages.get(id)) };
  } else snapshot = packet.snapshot;
  if (previous && snapshot.messages.length > previous.messages.length)
    shown += snapshot.messages.length - previous.messages.length;
  flags = packet.flags;
  await ensureCoordinator();
  post(coordinator, { type: 'snapshot', snapshot });
  notice(flags.notice);
  await render();
  if (!previous) { lifecycle(tavernEvents.CHAT_CHANGED, [snapshot.conversationId]); return; }
  const generation = snapshot.generation, prior = previous.generation;
  if (generation?.id && generation.id !== prior?.id) lifecycle(iframeEvents.GENERATION_STARTED, [generation.id]);
  if (generation?.stream && generation.text !== prior?.text) {
    lifecycle(iframeEvents.STREAM_TOKEN_RECEIVED_FULLY, [generation.text, generation.id]);
    lifecycle(iframeEvents.STREAM_TOKEN_RECEIVED_INCREMENTALLY, [generation.text.slice(generation.id === prior?.id ? prior.text.length : 0), generation.id]);
  }
  if (generation?.status === 'complete' && (prior?.status !== 'complete' || prior?.id !== generation.id)) lifecycle(iframeEvents.GENERATION_ENDED, [generation.text, generation.id]);
  if (!oldFlags.running && flags.running) lifecycle(tavernEvents.GENERATION_STARTED, ['normal']);
  if (oldFlags.running && !flags.running) lifecycle(tavernEvents.GENERATION_ENDED, [snapshot.messages.length - 1]);
  for (const m of snapshot.messages) {
    const before = previous.messages.find(item => item.turnId === m.turnId);
    if (before && before.swipe_id !== m.swipe_id) lifecycle(tavernEvents.MESSAGE_SWIPED, [m.message_id]);
    if (m.status === 'COMPLETE' && before?.status === 'STREAMING') lifecycle(tavernEvents.MESSAGE_RECEIVED, [m.message_id]);
    if (before && before.message !== m.message && m.status === 'COMPLETE') lifecycle(tavernEvents.MESSAGE_UPDATED, [m.message_id]);
  }
  if (JSON.stringify(previous.mvu) !== JSON.stringify(snapshot.mvu) && snapshot.mvu) lifecycle(mvuEvents.VARIABLE_UPDATE_ENDED, [snapshot.mvu, previous.mvu]);
}
globalThis.Player = {
  receive(packet) {
    if (packet.type === 'fatal') {
      notice(packet.message);
      if (coordinator) post(coordinator, { type: 'fatal', message: packet.message });
      return;
    }
    if (packet.type === 'result') {
      const item = pending.get(packet.id); if (!item) return; pending.delete(packet.id);
      packet.error ? item.reject(new Error(packet.error)) : item.resolve(packet.result); return;
    }
    if (packet.type === 'snapshot' || packet.type === 'delta') renderQueue = renderQueue.then(() => apply(packet)).catch(error => notice(error.message));
  },
};
rpc('ready').catch(error => notice(error.message));
