const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const object = value => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected an object');
  return value;
};
const only = (value, keys) => { object(value); if (Object.keys(value).some(key => !keys.includes(key))) throw new Error('Unsupported argument'); };

export const tavernEvents = Object.freeze({
  CHAT_CHANGED: 'chat_id_changed', MESSAGE_RECEIVED: 'message_received', MESSAGE_UPDATED: 'message_updated',
  MESSAGE_SWIPED: 'message_swiped', USER_MESSAGE_RENDERED: 'user_message_rendered',
  CHARACTER_MESSAGE_RENDERED: 'character_message_rendered', GENERATION_STARTED: 'generation_started',
  GENERATION_ENDED: 'generation_ended', GENERATION_STOPPED: 'generation_stopped',
});
export const mvuEvents = Object.freeze({ VARIABLE_UPDATE_ENDED: 'mag_variable_update_ended' });
export const iframeEvents = Object.freeze({
  MESSAGE_IFRAME_RENDER_STARTED: 'message_iframe_render_started', MESSAGE_IFRAME_RENDER_ENDED: 'message_iframe_render_ended',
  GENERATION_STARTED: 'js_generation_started', GENERATION_ENDED: 'js_generation_ended',
  STREAM_TOKEN_RECEIVED_FULLY: 'js_stream_token_received_fully', STREAM_TOKEN_RECEIVED_INCREMENTALLY: 'js_stream_token_received_incrementally',
});

/** A synchronous view plus an ordered durable command queue. No bridge call blocks JavaScript. */
export function createHost({ initial, actor, request, notify = () => {}, broadcast = async () => {} }) {
  let base = clone(initial), state = clone(initial), failed = null;
  let queue = Promise.resolve();
  const pending = [], listeners = new Map(), initialized = new Set();
  const scriptSource = [initial.program, initial.presetProgram].flatMap(p => p?.sources ?? []).find(s => s.id === actor.scriptId);
  const scriptId = scriptSource?.declaredId ?? actor.scriptId;
  let scriptButtons = clone(scriptSource?.buttons ?? actor.buttons ?? []);
  if (state.mvu) initialized.add('Mvu');
  const waiters = new Map();

  function receive(next) {
    base = clone(next); state = clone(base);
    for (const item of pending) item.project?.(state);
    if (state.mvu) initialize('Mvu');
  }
  function enqueue(method, args, project) {
    if (failed) return Promise.reject(failed);
    args = clone(args);
    const item = { project };
    pending.push(item); project?.(state);
    const run = queue.then(async () => {
      if (failed) throw failed;
      try {
        const result = await request(method, clone(args), base.revision);
        pending.splice(pending.indexOf(item), 1);
        if (result?.snapshot) receive({ ...base, ...result.snapshot });
        return result?.value;
      } catch (error) {
        failed = error instanceof Error ? error : new Error(String(error));
        pending.length = 0; state = clone(base);
        notify('error', '宿主操作未完成，当前程序已停止：' + failed.message);
        throw failed;
      }
    });
    queue = run.catch(() => {});
    return run;
  }
  async function flush() { await queue; if (failed) throw failed; }
  function index(value) {
    if (value === undefined) {
      const owned = state.messages.find(m => m.turnId === actor.turnId);
      return owned?.message_id ?? state.messages.length - 1;
    }
    if (value === 'latest') return state.messages.length - 1;
    const n = Number(value);
    if (!Number.isInteger(n)) throw new Error('Message index must be an integer');
    return n < 0 ? state.messages.length + n : n;
  }
  function variableTarget(options = {}) {
    only(options, ['type', 'message_id', 'script_id']);
    const type = options.type ?? 'chat';
    if (!['chat', 'message', 'script'].includes(type)) throw new Error('Unsupported variable scope: ' + type);
    if (type === 'script') {
      if (!actor.scriptId || (options.script_id !== undefined && options.script_id !== scriptId)) throw new Error('Unavailable script scope');
      return { type, key: actor.scriptId };
    }
    if (type === 'message') {
      const message_id = index(options.message_id ?? 'latest');
      if (!state.messages[message_id]) throw new Error('Message does not exist');
      return { type, message_id };
    }
    if (options.message_id !== undefined || options.script_id !== undefined) throw new Error('Invalid scope arguments');
    return { type };
  }
  function readVariables(target, source = state) {
    if (target.type === 'chat') return source.chatVariables;
    if (target.type === 'script') return source.scriptVariables[target.key] ?? {};
    return source.messages[target.message_id].data;
  }
  function writeVariables(source, target, data) {
    if (target.type === 'chat') source.chatVariables = clone(data);
    else if (target.type === 'script') source.scriptVariables[target.key] = clone(data);
    else {
      const message = source.messages[target.message_id];
      message.data = clone(data); message.swipes_data[message.swipe_id] = clone(data);
      if (target.message_id === source.messages.length - 1 && source.mvu) source.mvu = clone(data);
    }
  }
  function getVariables(options) {
    const target = variableTarget(options);
    if (target.type === 'message' && (options?.message_id === undefined || options.message_id === 'latest')) {
      let latest = state.messages.length - 1;
      while (latest >= 0 && state.messages[latest].is_hidden) latest--;
      if (latest < 0) throw new Error('No visible message exists');
      target.message_id = latest;
    }
    return clone(readVariables(target));
  }
  function replaceVariables(data, options) {
    object(data); data = clone(data); const target = variableTarget(options);
    const args = { type: target.type, data: clone(data) };
    if (target.type === 'message') args.message_id = target.message_id;
    // Upstream's synchronous return is retained; failures surface through the stopped runtime.
    enqueue('variables.replace', args, current => writeVariables(current, target, data)).catch(() => {});
  }
  function updateVariablesWith(updater, options) {
    if (typeof updater !== 'function') throw new Error('Updater must be a function');
    const commit = next => { replaceVariables(next, options); return next; };
    const next = updater(getVariables(options));
    return next && typeof next.then === 'function' ? next.then(commit) : commit(next);
  }
  function getChatMessages(range, options = {}) {
    only(options, ['role', 'hide_state', 'include_swipes']);
    const role = options.role ?? 'all', hidden = options.hide_state ?? 'all';
    if (!['all', 'user', 'assistant', 'system'].includes(role) || !['all', 'hidden', 'unhidden'].includes(hidden)) throw new Error('Invalid message filter');
    if (options.include_swipes !== undefined && typeof options.include_swipes !== 'boolean') throw new Error('Invalid include_swipes');
    let start, end;
    const expanded = String(range).replaceAll('{{lastMessageId}}', String(state.messages.length - 1));
    const pair = expanded.match(/^(-?\d+)-(-?\d+)$/);
    if (pair) { start = index(pair[1]); end = index(pair[2]); }
    else { start = end = index(expanded); }
    return state.messages.filter(m => m.message_id >= start && m.message_id <= end &&
      (role === 'all' || m.role === role) && (hidden === 'all' || m.is_hidden === (hidden === 'hidden'))).map(m => {
      const common = { message_id: m.message_id, name: m.name, role: m.role, is_hidden: m.is_hidden };
      return clone(options.include_swipes ? { ...common, swipe_id: m.swipe_id, swipes: m.swipes, swipes_data: m.swipes_data, swipes_info: m.swipes_info }
        : { ...common, message: m.message, data: m.data, extra: m.extra });
    });
  }
  async function setChatMessages(messages, options = {}) {
    only(options, ['refresh']);
    if (!Array.isArray(messages)) throw new Error('Messages must be an array');
    return enqueue('messages.set', { messages, refresh: options.refresh ?? 'affected' });
  }
  function on(name, fn, once = false) {
    if (typeof name !== 'string' || typeof fn !== 'function') throw new Error('Invalid event listener');
    const item = { fn, once };
    if (!listeners.has(name)) listeners.set(name, []);
    if (!listeners.get(name).some(item => item.fn === fn)) listeners.get(name).push(item);
    return { stop: () => remove(name, fn) };
  }
  function remove(name, fn) { listeners.set(name, (listeners.get(name) ?? []).filter(item => item.fn !== fn)); }
  async function dispatch(name, args) {
    for (const item of [...(listeners.get(name) ?? [])]) {
      if (item.once) remove(name, item.fn);
      await item.fn(...args);
    }
  }
  function initialize(name) {
    initialized.add(name);
    for (const resolve of waiters.get(name) ?? []) resolve();
    waiters.delete(name);
  }
  function waitGlobalInitialized(name) {
    if (initialized.has(name)) return Promise.resolve();
    if (name === 'Mvu' && !initial.mvu) return Promise.reject(new Error('This conversation has no MVU runtime'));
    return new Promise(resolve => { if (!waiters.has(name)) waiters.set(name, []); waiters.get(name).push(resolve); });
  }
  function worldbook(name) {
    const value = state.worldbooks.find(book => book.name === name);
    if (!value) throw new Error('World book does not exist');
    return value;
  }
  const unsupported = name => () => { throw new Error('Unsupported host capability: ' + name); };
  function stopGeneration(id) {
    if (id !== undefined && typeof id !== 'string') throw new Error('Generation ID must be a string');
    if (!state.generating || (id !== undefined && (state.generation?.id !== id || state.generation?.status !== 'running'))) return false;
    request('generation.stop', id === undefined ? {} : { id }, base.revision).catch(error => notify('error', error.message));
    return true;
  }
  const api = {
    getVariables, replaceVariables, updateVariablesWith,
    getChatMessages, setChatMessages,
    getCurrentMessageId: () => { if (!actor.turnId) throw new Error('Not a message context'); return index(); }, getLastMessageId: () => state.messages.length - 1,
    getScriptId: () => { if (!actor.scriptId) throw new Error('Not a script context'); return scriptId; },
    getButtonEvent: name => 'player_button:' + actor.scriptId + ':' + name,
    replaceScriptButtons: buttons => { if (!actor.scriptId) throw new Error('Not a script context'); if (!Array.isArray(buttons)) throw new Error('Buttons must be an array'); scriptButtons = clone(buttons); notify('buttons', scriptButtons); },
    getScriptButtons: () => clone(scriptButtons),
    eventOn: (name, fn) => on(name, fn), eventOnce: (name, fn) => on(name, fn, true),
    eventMakeFirst: (name, fn) => { remove(name, fn); const result = on(name, fn); listeners.get(name).unshift(listeners.get(name).pop()); return result; },
    eventMakeLast: (name, fn) => { remove(name, fn); return on(name, fn); },
    eventClearEvent: name => { listeners.delete(name); },
    eventClearListener: fn => { for (const name of listeners.keys()) remove(name, fn); },
    eventRemoveListener: remove, eventClearAll: () => listeners.clear(),
    eventEmit: async (name, ...args) => { await flush(); await broadcast(name, args); },
    eventEmitAndWait: unsupported('eventEmitAndWait: synchronous callbacks cannot cross isolated frames'),
    initializeGlobal: initialize, waitGlobalInitialized,
    tavern_events: tavernEvents, iframe_events: iframeEvents,
    getCharWorldbookNames: () => ({ primary: state.worldbooks[0]?.name ?? null, additional: state.worldbooks.slice(1).map(b => b.name) }),
    getCharLorebooks: () => ({ primary: state.worldbooks[0]?.name ?? null, additional: state.worldbooks.slice(1).map(b => b.name) }),
    getWorldbook: async name => clone(worldbook(name).entries).map(({ player_entry_id, comment, ...entry }) => entry),
    getLorebookEntries: unsupported('getLorebookEntries: use getWorldbook in this profile'),
    setWorldbookEnabled: async (name, enabled) => enqueue('worldbook.activation', { book: worldbook(name).name, enabled }),
    setWorldbookEntryEnabled: async (name, uid, enabled) => enqueue('worldbook.activation', { book: worldbook(name).name, entry: String(uid), enabled }),
    generate: async (options = {}) => { await flush(); return enqueue('generation.generate', options); },
    generateRaw: async (options = {}) => { await flush(); return enqueue('generation.raw', options); },
    stopAllGeneration: () => stopGeneration(),
    stopGenerationById: stopGeneration,
    toastr: Object.fromEntries(['info', 'success', 'warning', 'error'].map(level => [level, message => notify(level, String(message))])),
    getPlayerCapabilities: () => ({ profile: 'player-web-1', scopes: ['chat', 'message', 'script'], nativeUi: false }),
    getTavernHelperVersion: unsupported('getTavernHelperVersion: Player implements a profile, not a complete extension version'),
    getTavernVersion: unsupported('getTavernVersion'),
  };
  api.Mvu = {
    events: mvuEvents,
    getMvuData: (options = { type: 'message', message_id: 'latest' }) => {
      const data = getVariables(options);
      if (!data.stat_data || !('schema' in data)) throw new Error('MVU is not initialized for this candidate');
      return data;
    },
    replaceMvuData: async (data, options = { type: 'message', message_id: 'latest' }) => {
      data = clone(data);
      const target = variableTarget(options);
      if (target.type !== 'message') throw new Error('MVU requires a message scope');
      return enqueue('mvu.replace', { data, message_id: target.message_id }, current => writeVariables(current, target, data));
    },
    isValidMvuData: data => !!data && typeof data.stat_data === 'object' && 'schema' in data,
  };
  for (const name of ['createChatMessages', 'deleteChatMessages', 'rotateChatMessages', 'triggerSlash', 'executeSlashCommands',
    'registerMvuSchema', 'setWorldbook', 'replaceWorldbook', 'setLorebookEntries', 'injectPrompts', 'getAllVariables', 'replaceAllVariables']) api[name] = unsupported(name);
  return { api, receive, dispatch, flush, enqueue, get state() { return clone(state); },
    dispose() { listeners.clear(); pending.length = 0; state = clone(base); failed = new Error('Runtime disposed'); } };
}
