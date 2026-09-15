import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { tavernEvents, mvuEvents, iframeEvents } from './host.mjs';

const messagesNode = document.getElementById('messages'), actionsNode = document.getElementById('actions');
const pending = new Map(), frames = new Map(), rows = new Map(), scriptFrames = new Map(), eventAcks = new Map();
let sequence = 0, epoch = null, snapshot = null, flags = {}, shown = 50, programKey = '', following = true, previousBottom = 0;
let renderQueue = Promise.resolve(), coordinator = null, factSnapshot = null;
// Upstream generation notifications emit without awaiting listeners. A listener may itself await
// another generation; holding a global event queue here would deadlock its streaming notifications.
function lifecycle(name, args) { broadcast(name, args).catch(error => notice(error.message)); }
// Both native notifications and command replies can carry the same saved state.
// Observe facts once independently of which transport delivers that state first.
function publishMessageFacts(next) {
  const previous = factSnapshot; factSnapshot = next;
  if (!previous) return;
  if (previous.messages === next.messages && previous.mvu === next.mvu) return;
  const previousMessages = new Map(previous.messages.map(message => [message.turnId, message]));
  for (const message of next.messages) {
    const before = previousMessages.get(message.turnId);
    if (!before && message.status === 'COMPLETE') lifecycle(message.role === 'user' ? tavernEvents.MESSAGE_SENT : tavernEvents.MESSAGE_RECEIVED, [message.message_id]);
    if (before && before.swipe_id !== message.swipe_id) lifecycle(tavernEvents.MESSAGE_SWIPED, [message.message_id]);
    if (message.status === 'COMPLETE' && before?.status === 'STREAMING') lifecycle(tavernEvents.MESSAGE_RECEIVED, [message.message_id]);
    if (before && before.message !== message.message && message.status === 'COMPLETE') lifecycle(tavernEvents.MESSAGE_UPDATED, [message.message_id]);
  }
  if (JSON.stringify(previous.mvu) !== JSON.stringify(next.mvu) && next.mvu) lifecycle(mvuEvents.VARIABLE_UPDATE_ENDED, [next.mvu, previous.mvu]);
}
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
function followBottom() {
  requestAnimationFrame(() => { if (following && !focusInFrame()) bottom(); });
}
// 视口变化时 scroll 可能晚于 resize 到达，贴底判定只能对比变化前记录的几何。
function recordBottom() { previousBottom = document.documentElement.scrollHeight - window.innerHeight; }
const focusInFrame = () => document.activeElement?.tagName === 'IFRAME';
window.addEventListener('resize', () => {
  // 键盘弹出会改变视口而不产生滚动，读者原本是否在底部要用变化前的偏移判断。
  const wasFollowing = following && window.scrollY >= previousBottom - 80;
  following = wasFollowing || document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 80;
  recordBottom();
  document.getElementById('bottom').hidden = following;
  for (const frame of frames.values()) if (frame.kind !== 'script') post(frame, { type: 'viewport', height: window.innerHeight });
  if (following && !focusInFrame()) followBottom();
}, { passive: true });
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
    if (token.type === 'code' && /<body(?:\s[^>]*)?>/i.test(token.text) && (/<\/body\s*>/i.test(token.text) || token.lang?.toLowerCase() === 'html')) {
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
// 段级重建在同一任务里把新帧换到旧帧的位置，此时 detach 为 false，节点由调用方接管。
function dispose(frame, detach = true) {
  if (!frame) return;
  if (coordinator && frame !== coordinator) post(coordinator, { type: 'dispose-owner', token: frame.token });
  post(frame, { type: 'dispose' });
  if (detach) frame.element.remove();
  frames.delete(frame.token);
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
const statusLabels = { STREAMING: '正在生成…', CANCELLED: '已停止', INTERRUPTED: '生成已中断', ERROR: '生成失败' };
// 占位文案随状态分流，非 COMPLETE 的页面不再无限等待“回复完成后显示交互界面”。
const waitingLabels = { CANCELLED: '已停止生成，交互界面未装载', INTERRUPTED: '生成已中断，交互界面未装载', ERROR: '生成失败，交互界面未装载' };
const WAITING = '回复完成后显示交互界面';
const richText = text => /<(?:style|table|div|span|form|input|img|details|section|html|body)\b/i.test(text);
// HTML styles never share the trusted player's document or controls.
const SANITIZE = { ADD_TAGS: ['style'], FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'base', 'meta', 'link'],
  FORBID_ATTR: ['srcdoc'] };
function sanitize(text) {
  return DOMPurify.sanitize(text, { ...SANITIZE, WHOLE_DOCUMENT: false });
}
// 作者的 <style> 会被整文档解析提升进 <head>，只序列化 body 就会连样式一起丢掉。
// 取回 head 里的样式块与正文一起交给 static 帧：样式仍只作用于这个没有宿主能力的沙箱帧。
function staticContent(text) {
  const parsed = new DOMParser().parseFromString(DOMPurify.sanitize(text, { ...SANITIZE, WHOLE_DOCUMENT: true }), 'text/html');
  return [...parsed.head.querySelectorAll('style')].map(node => node.outerHTML).join('') + parsed.body.innerHTML;
}
function newRow() {
  return { element: document.createElement('article'), frames: [], segments: [], headerKey: null, header: null, name: null,
    reasoningKey: null, reasoning: null, reasoningText: null, status: null, statusText: null, reasoningOpen: false,
    message: null, lastRenderedMessage: null, parsedSource: null, parsedParts: null, rendered: false, forced: false };
}
function clearRow(row) {
  row.frames.forEach(dispose); row.frames = [];
  row.segments = []; row.element.replaceChildren();
  row.headerKey = null; row.header = null; row.name = null;
  row.reasoningKey = null; row.reasoning = null; row.reasoningText = null;
  row.status = null; row.statusText = null;
  row.lastRenderedMessage = null;
}
// 段的身份是序号 + kind：kind 与内容都没变的段一律不触碰，也不重发视口或高度。
function segmentKey(kind, text, variantId) {
  return JSON.stringify(kind === 'page' ? [kind, text, variantId] : [kind, text]);
}
// 重建的段在同一任务内原地换节点：旧帧先收到 dispose，新帧已带上旧帧最后实测的高度，
// 浏览器只在任务结束后布局，读者看到的文档高度不会先掉再涨。
function replaceSegment(row, previous, node) {
  if (!previous) return;
  if (previous.frame) { dispose(previous.frame, false); row.frames = row.frames.filter(item => item !== previous.frame); }
  previous.node.replaceWith(node);
}
// resize 处理把每次实测高度记在帧上；重建该段时用它预置新 iframe 的高度。
function presetHeight(segment) {
  const height = segment?.frame?.height;
  return Number.isFinite(height) ? height : null;
}
// 未变的段由调用方直接复用，新节点只接在段列表尾部，顺序不会被这段整理打乱。
function placeRow(row) {
  const order = [row.header, row.reasoning, ...row.segments.map(segment => segment.node), row.status].filter(Boolean);
  let position = 0;
  for (const node of order) {
    if (row.element.children[position] !== node) row.element.insertBefore(node, row.element.children[position] ?? null);
    position++;
  }
  while (row.element.children.length > position) row.element.children[position].remove();
}
async function buildSegment(row, message, part, kind, previous) {
  if (kind === 'text') {
    const node = document.createElement('div');
    node.innerHTML = DOMPurify.sanitize(sanitize(part.text), { FORBID_TAGS: ['style'], FORBID_ATTR: ['style', 'id', 'name'] });
    replaceSegment(row, previous, node);
    return { kind, key: segmentKey(kind, part.text), node };
  }
  const height = presetHeight(previous);
  const frame = kind === 'page' ? await createFrame('page', part.text, message)
    : await createFrame('static', '<body>' + staticContent(part.text) + '</body>', message);
  if (height) frame.element.style.height = height + 'px';
  frame.height = height;
  row.frames.push(frame); replaceSegment(row, previous, frame.element);
  return { kind, key: segmentKey(kind, part.text, message.variantId), node: frame.element, frame };
}
async function renderRow(row, message) {
  row.message = message;
  if (row.forced) { row.forced = false; clearRow(row); }
  const headerKey = JSON.stringify([message.name]);
  if (row.headerKey !== headerKey) {
    row.headerKey = headerKey;
    if (!row.header) {
      row.header = document.createElement('header'); row.name = document.createElement('span');
      row.header.append(row.name, button('编辑', () => ui('edit', row.message.id), flags.busy));
    }
    row.name.textContent = message.name;
  }
  const reasoning = message.reasoning ?? [], reasoningKey = JSON.stringify(reasoning);
  if (!reasoning.length) {
    if (row.reasoning) { row.reasoning.remove(); row.reasoning = null; row.reasoningText = null; }
    row.reasoningKey = null;
  } else if (row.reasoningKey !== reasoningKey) {
    row.reasoningKey = reasoningKey;
    if (row.reasoning) row.reasoningText.textContent = reasoning.join('\n\n');
    else {
      const details = document.createElement('details'), summary = document.createElement('summary'), text = document.createElement('div');
      summary.textContent = '思考过程'; text.textContent = reasoning.join('\n\n');
      // 流式期间每次刷新都会原地改写内容；展开状态记在行上，读者打开后不会被折回去。
      details.open = row.reasoningOpen;
      details.addEventListener('toggle', () => { row.reasoningOpen = details.open; });
      details.append(summary, text); row.reasoning = details; row.reasoningText = text;
    }
  }
  const next = [];
  const source = message.display ?? message.message;
  if (row.parsedSource !== source || !row.parsedParts) {
    row.parsedParts = segments(source); row.parsedSource = source;
  }
  for (const [index, part] of row.parsedParts.entries()) {
    const previous = row.segments[index];
    if (part.kind === 'page' && message.status !== 'COMPLETE') {
      // 非 COMPLETE 不建 page 帧；占位文案原地随状态改写，不再无限等待。
      const text = waitingLabels[message.status] ?? WAITING;
      if (previous?.kind === 'waiting') {
        if (previous.key !== text) { previous.node.textContent = text; previous.key = text; }
        next.push(previous); continue;
      }
      const node = document.createElement('p');
      node.className = 'runtime-loading'; node.textContent = text;
      replaceSegment(row, previous, node); next.push({ kind: 'waiting', key: text, node });
      continue;
    }
    const kind = part.kind === 'page' ? 'page' : richText(part.text) ? 'static' : 'text';
    const key = segmentKey(kind, part.text, message.variantId);
    if (previous?.kind === kind && previous.key === key) { next.push(previous); continue; }
    next.push(await buildSegment(row, message, part, kind, previous));
  }
  for (const segment of row.segments.slice(next.length)) {
    if (segment.frame) { dispose(segment.frame); row.frames = row.frames.filter(item => item !== segment.frame); }
    segment.node.remove();
  }
  row.segments = next;
  const statusText = message.status === 'COMPLETE' ? null : statusLabels[message.status] ?? '';
  if (statusText === null) { if (row.status) row.status.remove(); row.status = null; row.statusText = null; }
  else if (row.status) { if (row.statusText !== statusText) { row.status.textContent = statusText; row.statusText = statusText; } }
  else { row.status = document.createElement('p'); row.status.className = 'status'; row.status.textContent = statusText; row.statusText = statusText; }
  placeRow(row);
  // 行级标记：内容变化只重建变化的段，渲染事件仍按消息只发一次。
  if (!row.rendered && message.status === 'COMPLETE' && row.frames.length === 0) {
    row.rendered = true;
    lifecycle(message.role === 'user' ? tavernEvents.USER_MESSAGE_RENDERED : tavernEvents.CHARACTER_MESSAGE_RENDERED, [message.message_id]);
  }
}
async function render() {
  if (!snapshot) return;
  const visible = snapshot.messages.slice(-shown), ids = new Set(visible.map(m => m.turnId));
  for (const [id, row] of rows) if (!ids.has(id)) { row.frames.forEach(dispose); row.element.remove(); rows.delete(id); }
  for (const [position, message] of visible.entries()) {
    let row = rows.get(message.turnId);
    if (!row) { row = newRow(); rows.set(message.turnId, row); }
    row.frames.forEach(frame => { frame.messageId = message.message_id; });
    row.element.dataset.role = message.role;
    if (row.lastRenderedMessage !== message || row.forced) {
      await renderRow(row, message); row.lastRenderedMessage = message;
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
  if (following && !focusInFrame()) followBottom();
  recordBottom();
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
    if (Number.isFinite(height) && height > 0) {
      // 实测高度记在帧上：该段按契约重建时用它预置新 iframe，文档高度不会骤降。
      frame.height = Math.min(height, 100000); frame.element.style.height = frame.height + 'px';
      if (following && !focusInFrame()) followBottom(); recordBottom();
    }
  } else if (data.type === 'loaded') {
    frame.loaded = true;
    if (frame.kind === 'session') { post(frame, { type: 'snapshot', snapshot }); frame.onLoaded?.(); return; }
    if (frame.kind === 'page') lifecycle(iframeEvents.MESSAGE_IFRAME_RENDER_ENDED, [frame.token]);
    // 建帧到加载完成之间可能已经发生过视口变化（键盘、旋转、分屏），补发一次当前可见高度。
    if (frame.kind !== 'script') post(frame, { type: 'viewport', height: window.innerHeight });
    if (frame.kind !== 'script') {
      const message = snapshot.messages[frame.messageId];
      // 行级标记：渲染事件按消息只发一次，段级更新不再补发。
      const row = rows.get(message?.turnId); if (row) row.rendered = true;
      lifecycle(message?.role === 'user' ? tavernEvents.USER_MESSAGE_RENDERED : tavernEvents.CHARACTER_MESSAGE_RENDERED, [frame.messageId]);
    }
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
        const previousMessages = new Map(snapshot.messages.map(message => [message.id, message]));
        snapshot = { ...snapshot, ...result.snapshot, messages: result.snapshot.messages.map(m => ({ ...previousMessages.get(m.id), ...m })) };
        if (coordinator) post(coordinator, { type: 'snapshot', snapshot });
        publishMessageFacts(snapshot);
      }
      if (result.refresh && result.refresh.mode !== 'none') {
        const refresh = result.refresh;
        renderQueue = renderQueue.then(async () => {
          for (const message of snapshot.messages) if (refresh.mode === 'all' || refresh.messageIds.includes(message.message_id)) {
            const row = rows.get(message.turnId); if (row) row.forced = true;
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
  if (packet.epoch !== epoch) {
    epoch = packet.epoch; shown = 50; programKey = '';
    // 换 epoch 后旧行不再属于当前会话，整行移除，不能只留下悬挂的帧与节点。
    for (const row of rows.values()) { row.frames.forEach(dispose); row.element.remove(); }
    rows.clear();
  }
  if (packet.type === 'delta') {
    if (!snapshot) throw new Error('Missing initial conversation snapshot');
    let ordered = snapshot.messages;
    if (packet.messages.length || packet.order) {
      const messages = new Map(snapshot.messages.map(m => [m.turnId, m]));
      for (const message of packet.messages) messages.set(message.turnId, message);
      ordered = (packet.order ?? snapshot.messages.map(message => message.turnId)).map(id => messages.get(id));
    }
    snapshot = { ...snapshot, ...packet.changes, messages: ordered };
  } else snapshot = packet.snapshot;
  if (previous && snapshot.messages.length > previous.messages.length)
    shown += snapshot.messages.length - previous.messages.length;
  flags = packet.flags;
  await ensureCoordinator();
  if (packet.type === 'delta') post(coordinator, { type: 'delta', changes: packet.changes, messages: packet.messages,
    ...(!packet.order || (packet.order.length === previous.messages.length && packet.order.every((id, index) => id === previous.messages[index].turnId))
      ? {} : { order: packet.order }) });
  else post(coordinator, { type: 'snapshot', snapshot });
  notice(flags.notice);
  const draftOnly = packet.type === 'delta' && !packet.messages.length && !packet.order &&
    Object.keys(packet.changes).every(key => key === 'draft' || key === 'revision') && JSON.stringify(oldFlags) === JSON.stringify(flags);
  if (!draftOnly) await render();
  publishMessageFacts(snapshot);
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
