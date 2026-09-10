import { createSession } from './session.mjs';
import mergeWith from 'lodash/mergeWith.js';
import unset from 'lodash/unset.js';

const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const object = value => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected an object');
  return value;
};
const only = (value, keys) => { object(value); if (Object.keys(value).some(key => !keys.includes(key))) throw new Error('Unsupported argument'); };

export const tavernEvents = Object.freeze({
  CHAT_CHANGED: 'chat_id_changed', MESSAGE_SENT: 'message_sent', MESSAGE_RECEIVED: 'message_received', MESSAGE_UPDATED: 'message_updated',
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
export function createHost({ initial, actor, request, notify = () => {}, session = createSession(initial) }) {
  const owner = session.attach(notify, actor);
  const scriptSource = [initial.program, initial.presetProgram].flatMap(p => p?.sources ?? []).find(s => s.id === actor.scriptId);
  const scriptId = scriptSource?.declaredId ?? actor.scriptId;
  let scriptButtons = clone(scriptSource?.buttons ?? actor.buttons ?? []);
  const receive = next => session.receive(next);
  const enqueue = (method, args, project) => session.enqueue(owner, request, method, args, project);
  const flush = () => session.flush(owner);
  function index(value) {
    if (value === undefined) {
      const owned = session.state.messages.find(m => m.turnId === actor.turnId);
      return owned?.message_id ?? session.state.messages.length - 1;
    }
    if (value === 'latest') return session.state.messages.length - 1;
    const n = Number(value);
    if (!Number.isInteger(n)) throw new Error('Message index must be an integer');
    return n < 0 ? session.state.messages.length + n : n;
  }
  function variableTarget(options = {}) {
    only(options, ['type', 'message_id', 'script_id']);
    const type = options.type ?? 'chat';
    if (!['chat', 'message', 'script', 'character'].includes(type)) throw new Error('Unsupported variable scope: ' + type);
    if (type === 'script') {
      if (!actor.scriptId || (options.script_id !== undefined && options.script_id !== scriptId)) throw new Error('Unavailable script scope');
      return { type, key: actor.scriptId };
    }
    if (type === 'message') {
      const message_id = index(options.message_id ?? 'latest');
      if (!session.state.messages[message_id]) throw new Error('Message does not exist');
      return { type, message_id };
    }
    if (options.message_id !== undefined || options.script_id !== undefined) throw new Error('Invalid scope arguments');
    return { type };
  }
  function readVariables(target, source = session.state) {
    if (target.type === 'character') return source.characterVariables ?? source.program?.variables ?? {};
    if (target.type === 'chat') return source.chatVariables;
    if (target.type === 'script') return source.scriptVariables[target.key] ?? scriptSource?.data ?? {};
    return source.messages[target.message_id].data;
  }
  function getAllVariables() {
    // This profile has no application-wide variable store. Character defaults remain
    // part of the captured program; message data is the selected candidate's view.
    const layers = [session.state.globalVariables ?? {}, session.state.characterVariables ?? session.state.program?.variables ?? {}];
    if (!actor.turnId && actor.scriptId) layers.push(readVariables({ type: 'script', key: actor.scriptId }));
    layers.push(session.state.chatVariables);
    if (actor.turnId) {
      const current = session.state.messages.findIndex(message => message.turnId === actor.turnId);
      if (current < 0) throw new Error('Message context no longer exists');
      layers.push(...session.state.messages.slice(0, current + 1).map(message => message.data));
    }
    // Upstream uses shallow assign (including replacement of whole nested objects).
    return clone(Object.fromEntries(layers.flatMap(layer => Object.entries(layer ?? {}))));
  }
  function writeVariables(source, target, data) {
    if (target.type === 'character') source.characterVariables = clone(data);
    else if (target.type === 'chat') source.chatVariables = clone(data);
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
      let latest = session.state.messages.length - 1;
      while (latest >= 0 && session.state.messages[latest].is_hidden) latest--;
      if (latest < 0) return {};
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
  const replaceArrays = (_previous, next) => Array.isArray(next) ? next : undefined;
  function insertOrAssignVariables(data, options) {
    object(data);
    return updateVariablesWith(previous => mergeWith(previous, clone(data), replaceArrays), options);
  }
  function insertVariables(data, options) {
    object(data);
    return updateVariablesWith(previous => mergeWith({}, clone(data), previous, replaceArrays), options);
  }
  function deleteVariable(path, options) {
    if (typeof path !== 'string') throw new Error('Variable path must be a string');
    let delete_occurred = false;
    const variables = updateVariablesWith(previous => { delete_occurred = unset(previous, path); return previous; }, options);
    return { variables, delete_occurred };
  }
  function getChatMessages(range, options = {}) {
    only(options, ['role', 'hide_state', 'include_swipes']);
    const role = options.role ?? 'all', hidden = options.hide_state ?? 'all';
    if (!['all', 'user', 'assistant', 'system'].includes(role) || !['all', 'hidden', 'unhidden'].includes(hidden)) throw new Error('Invalid message filter');
    if (options.include_swipes !== undefined && typeof options.include_swipes !== 'boolean') throw new Error('Invalid include_swipes');
    let start, end;
    const expanded = String(range).replaceAll('{{lastMessageId}}', String(session.state.messages.length - 1));
    if (!session.state.messages.length) return [];
    const clamp = value => Math.max(0, Math.min(session.state.messages.length - 1, index(value)));
    const pair = expanded.match(/^(-?\d+)-(-?\d+)$/);
    if (pair) [start, end] = [clamp(pair[1]), clamp(pair[2])].sort((a, b) => a - b);
    else if (/^-?\d+$/.test(expanded)) start = end = clamp(expanded);
    else return [];
    return session.state.messages.filter(m => m.message_id >= start && m.message_id <= end &&
      (role === 'all' || m.role === role) && (hidden === 'all' || m.is_hidden === (hidden === 'hidden'))).map(m => {
      const common = { message_id: m.message_id, name: m.name, role: m.role, is_hidden: m.is_hidden };
      return clone(options.include_swipes ? { ...common, swipe_id: m.swipe_id, swipes: m.swipes, swipes_data: m.swipes_data, swipes_info: m.swipes_info }
        : { ...common, message: m.message, data: m.data, extra: m.extra, swipe_id: m.swipe_id, swipes: m.swipes, swipes_data: m.swipes_data });
    });
  }
  async function setChatMessages(messages, options = {}) {
    only(options, ['refresh']);
    if (!Array.isArray(messages)) throw new Error('Messages must be an array');
    return enqueue('messages.set', { messages, refresh: options.refresh ?? 'affected' });
  }
  const on = (name, fn, once = false) => session.on(owner, name, fn, once);
  const remove = (name, fn) => session.remove(owner, name, fn);
  const dispatch = (name, args) => session.emit(name, args);
  const initialize = (name, value) => session.initialize(owner, name, value);
  const waitGlobalInitialized = name => session.wait(owner, name);
  function worldbook(name) {
    const value = session.state.worldbooks.find(book => book.name === name);
    if (!value) throw new Error('World book does not exist');
    return value;
  }
  function regexScope(options = {}) {
    only(options, ['type', 'name', 'scope', 'enable_state']);
    const scope = options.type ?? options.scope ?? 'all';
    if (!['character', 'all'].includes(scope)) throw new Error('Unsupported regex scope: ' + scope);
    if (options.name !== undefined && options.name !== 'current') throw new Error('Unavailable character regex asset');
    if (!['all', 'enabled', 'disabled'].includes(options.enable_state ?? 'all')) throw new Error('Invalid regex enabled filter');
    return options.type === undefined;
  }
  function getTavernRegexes(options) {
    const legacy = regexScope(options);
    return clone(session.state.characterRegexes ?? []).filter(rule => !options?.enable_state || options.enable_state === 'all' || rule.enabled === (options.enable_state === 'enabled'))
      .map(rule => legacy ? { ...rule, scope: 'character' } : rule);
  }
  async function replaceTavernRegexes(regexes, options) {
    regexScope(options);
    if (!Array.isArray(regexes)) throw new Error('Regexes must be an array');
    if (regexes.some(rule => rule.scope && rule.scope !== 'character')) throw new Error('Unsupported regex scope');
    await enqueue('regex.replace', { regexes });
  }
  async function updateTavernRegexesWith(updater, options) {
    if (typeof updater !== 'function') throw new Error('Updater must be a function');
    const regexes = await updater(getTavernRegexes(options));
    await replaceTavernRegexes(regexes, options);
    return regexes;
  }
  const unsupported = name => () => { throw new Error('Unsupported host capability: ' + name); };
  function stopGeneration(id) {
    if (id !== undefined && typeof id !== 'string') throw new Error('Generation ID must be a string');
    if (!session.state.generating || (id !== undefined && (session.state.generation?.id !== id || session.state.generation?.status !== 'running'))) return false;
    request('generation.stop', id === undefined ? {} : { id }, session.revision).catch(error => notify('error', error.message));
    return true;
  }
  const api = {
    // Match the helper's wrapper contract: report failures and rethrow them, never
    // turn a failed initializer into a successful result. Duck typing is needed
    // because author promises are created in a different iframe realm.
    errorCatched: fn => (...args) => {
      const onError = error => {
        notify('error', String(error?.stack || error?.message || error));
        throw error;
      };
      try {
        const result = fn(...args);
        return result != null && (typeof result === 'object' || typeof result === 'function') && typeof result.then === 'function'
          ? result.then(undefined, onError) : result;
      } catch (error) { return onError(error); }
    },
    getVariables, getAllVariables, replaceVariables, updateVariablesWith,
    insertOrAssignVariables, insertVariables, deleteVariable,
    getTavernRegexes, replaceTavernRegexes, updateTavernRegexesWith,
    isCharacterTavernRegexesEnabled: () => true,
    getChatMessages, setChatMessages,
    createChatMessages: async (messages, options = {}) => {
      only(options, ['insert_at', 'insert_before', 'refresh']);
      return enqueue('messages.create', { messages, insert_before: options.insert_at ?? options.insert_before ?? 'end', refresh: options.refresh ?? 'affected' });
    },
    deleteChatMessages: async (message_ids, options = {}) => {
      only(options, ['refresh']);
      return enqueue('messages.delete', { message_ids, refresh: options.refresh ?? 'affected' });
    },
    rotateChatMessages: async (begin, middle, end, options = {}) => {
      only(options, ['refresh']);
      return enqueue('messages.rotate', { begin, middle, end, refresh: options.refresh ?? 'affected' });
    },
    getCurrentMessageId: () => { if (!actor.turnId) throw new Error('Not a message context'); return index(); }, getLastMessageId: () => session.state.messages.length - 1,
    getScriptId: () => { if (!actor.scriptId) throw new Error('Not a script context'); return scriptId; },
    getButtonEvent: name => 'player_button:' + actor.scriptId + ':' + name,
    replaceScriptButtons: buttons => { if (!actor.scriptId) throw new Error('Not a script context'); if (!Array.isArray(buttons)) throw new Error('Buttons must be an array'); scriptButtons = clone(buttons); notify('buttons', scriptButtons); },
    getScriptButtons: () => clone(scriptButtons),
    eventOn: (name, fn) => on(name, fn), eventOnce: (name, fn) => on(name, fn, true),
    eventMakeFirst: (name, fn) => session.on(owner, name, fn, false, 'first'),
    eventMakeLast: (name, fn) => session.on(owner, name, fn, false, 'last'),
    eventClearEvent: name => session.remove(owner, name),
    eventClearListener: fn => session.clear(owner, fn),
    eventRemoveListener: remove, eventClearAll: () => session.clear(owner),
    eventEmit: (name, ...args) => session.emit(name, args),
    eventEmitAndWait: (name, ...args) => session.emitSync(name, args),
    eventOnButton: (name, fn) => on('player_button:' + actor.scriptId + ':' + name, fn),
    initializeGlobal: initialize, waitGlobalInitialized,
    tavern_events: tavernEvents, iframe_events: iframeEvents,
    getCharWorldbookNames: () => ({ primary: session.state.worldbooks[0]?.name ?? null, additional: session.state.worldbooks.slice(1).map(b => b.name) }),
    getCharLorebooks: () => ({ primary: session.state.worldbooks[0]?.name ?? null, additional: session.state.worldbooks.slice(1).map(b => b.name) }),
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
  for (const name of ['triggerSlash', 'executeSlashCommands',
    'registerMvuSchema', 'setWorldbook', 'replaceWorldbook', 'setLorebookEntries', 'injectPrompts', 'replaceAllVariables']) api[name] = unsupported(name);
  // Retained function references must not operate after their owner is destroyed.
  for (const [name, fn] of Object.entries(api)) if (typeof fn === 'function')
    api[name] = (...args) => { if (!owner.active) throw new Error('Runtime disposed'); return fn(...args); };
  for (const [name, fn] of Object.entries(api.Mvu)) if (typeof fn === 'function')
    api.Mvu[name] = (...args) => { if (!owner.active) throw new Error('Runtime disposed'); return fn(...args); };
  return { api, receive, dispatch, flush, enqueue, get state() { return clone(session.state); },
    install: target => session.install(owner, target), observe: callback => session.observe(owner, callback),
    onDispose: callback => { owner.onDispose = callback; },
    onStop: callback => { owner.onStop = callback; },
    dispose: () => session.dispose(owner) };
}
