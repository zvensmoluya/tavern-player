// Diagnostic mock only: these return values and events are not an ST compatibility contract.
export function installHost(options = {}) {

  var LOG = [];
  var MAX = 4000;
  var phase = 'load', dropped = 0;
  var state = structuredClone(options.state || { stat_data: {} });
  var messages = structuredClone(options.messages || [{ message_id: 0, message: '', role: 'assistant', name: 'probe' }]);
  var listeners = new Map();
  var initialized = new Set(options.initialized || ['Mvu']);
  var waiters = new Map();
  function on(name, fn, once) {
    if (typeof fn !== 'function') throw new TypeError('event listener must be a function');
    var list = listeners.get(name) || []; list.push({ fn, once }); listeners.set(name, list);
    return { stop: function () { listeners.set(name, (listeners.get(name) || []).filter(x => x.fn !== fn)); } };
  }
  async function fire(name, args) {
    log({ t: 'dispatch', p: name });
    for (const entry of [...(listeners.get(name) || [])]) {
      if (entry.once) listeners.set(name, (listeners.get(name) || []).filter(x => x !== entry));
      try { await entry.fn(...args); } catch (e) { log({ t: 'callback-error', p: String(e) }); }
    }
  }
  function safe(v, depth = 0) {
    if (depth > 4) return "[depth]";
    if (v === null) return null;
    var t = typeof v;
    if (t === 'string') return v.length > 120 ? v.slice(0, 120) + '…' : v;
    if (t === 'number' || t === 'boolean' || t === 'undefined') return v;
    if (t === 'function') return '[fn]';
    if (Array.isArray(v)) return v.length > 6 ? '[' + v.length + ' items]' : v.map(x => safe(x, depth + 1));
    if (t === 'object') {
      var keys = Object.keys(v);
      var o = {};
      keys.slice(0, 8).forEach(function (k) { o[k] = safe(v[k], depth + 1); });
      if (keys.length > 8) o.__more = keys.length - 8;
      return o;
    }
    return String(v);
  }
  function log(entry) { if (LOG.length < MAX) LOG.push({ phase, ...entry }); else dropped++; }

  // 桩的默认返回值：让页面能越过守卫继续往下跑，而不是第一个空值就退出
  var DEFAULTS = {
    getLastMessageId: 0,
    getCurrentMessageId: 0,
    getChatMessages: function () { return structuredClone(messages); },
    setChatMessages: function (updates) { updates.forEach(u => { const i = messages.findIndex(m => m.message_id === u.message_id); if (i < 0) throw new Error('unknown message'); messages[i] = { ...messages[i], ...structuredClone(u) }; }); return Promise.resolve(); },
    createChatMessages: function () { return Promise.resolve(); },
    deleteChatMessages: function () { return Promise.resolve(); },
    getVariables: function () { return structuredClone(state); },
    getAllVariables: function () { return structuredClone(state); },
    replaceVariables: function (next) { state = structuredClone(next); },
    replaceAllVariables: function () {},
    updateVariablesWith: function (f) { return typeof f === 'function' ? f({}) : {}; },
    waitGlobalInitialized: function (name) { if (initialized.has(name)) return Promise.resolve(); return new Promise(resolve => { const list = waiters.get(name) || []; list.push(resolve); waiters.set(name, list); }); },
    initializeGlobal: function (name) { initialized.add(name); (waiters.get(name) || []).forEach(fn => fn()); waiters.delete(name); },
    eventOn: function (name, fn) { return on(name, fn, false); },
    eventOnce: function (name, fn) { return on(name, fn, true); },
    eventEmit: function (name, ...args) { void fire(name, args); },
    eventEmitAndWait: function (name, ...args) { return fire(name, args); },
    eventRemoveListener: function () {},
    eventClearAll: function () {},
    substitudeMacros: function (s) { return s; },
    formatAsDisplayedMessage: function (s) { return s; },
    generate: function () { return Promise.resolve(''); },
    generateRaw: function () { return Promise.resolve(''); },
    triggerSlash: function () { return Promise.resolve(''); },
    // Tavern Helper 注入的全局错误包装器：卡里直接调用，不提供就整页报错
    errorCatched: function (fn) { return typeof fn === 'function' ? fn : fn; },
    errorCatchedAsync: function (fn) { return typeof fn === 'function' ? fn : fn; },
    'SillyTavern.getCurrentChatId': function () { return 'probe-chat'; },
    'Mvu.getMvuData': function () { return structuredClone(state); },
    'Mvu.isValidMvuData': function () { return true; },
    'Mvu.parseMessage': function () { return structuredClone(state); },
    'Mvu.getMvuVariable': function () { return undefined; },
    'Mvu.setMvuVariable': function () {},
    'Mvu.deleteMvuVariable': function () {},
    'Mvu.replaceMvuData': function () {}
  };

  var cache = new Map();
  function rec(p) {
    if (cache.has(p)) return cache.get(p);
    var fn = function () {};
    var proxy = new Proxy(fn, {
      get: function (t, prop, receiver) {
        if (typeof prop === 'symbol' || prop === 'then' || prop === 'toJSON') return undefined;
        var key = p + '.' + String(prop);
        log({ t: 'get', p: key });
        // 卡里常见 getVariables.bind(null, {...})：保留 bind/call/apply 的原生语义，
        // 否则绑定出来的函数永远调不到真实桩，调用记录也会失真
        if (prop === 'bind') return Function.prototype.bind.bind(receiver);
        if (prop === 'call') return Function.prototype.call.bind(receiver);
        if (prop === 'apply') return Function.prototype.apply.bind(receiver);
        if (key.startsWith('Mvu.events.') || key.startsWith('tavern_events.') || key.startsWith('iframe_events.')) return key;
        return rec(key);
      },
      apply: function (t, self, args) {
        log({ t: 'call', p: p, a: Array.prototype.map.call(args, x => safe(x)) });
        if (!(p in DEFAULTS)) log({ t: 'unsupported', p });
        var dv = DEFAULTS[p];
        return typeof dv === 'function' ? dv.apply(self, args) : dv;
      },
      has: function () { return true; }
    });
    cache.set(p, proxy);
    return proxy;
  }

  var HOST = [
    'TavernHelper', 'SillyTavern', 'Mvu', 'EjsTemplate', 'toastr', 'showdown', 'YAML', 'builtin',
    'eventOn', 'eventOnce', 'eventEmit', 'eventEmitAndWait', 'eventRemoveListener', 'eventClearAll',
    'eventMakeFirst', 'eventMakeLast', 'tavern_events', 'iframe_events',
    'getVariables', 'getAllVariables', 'replaceVariables', 'replaceAllVariables',
    'updateVariablesWith', 'insertOrAssignVariables',
    'insertVariables', 'deleteVariable', 'registerVariableSchema',
    'getChatMessages', 'setChatMessages', 'createChatMessages', 'deleteChatMessages',
    'rotateChatMessages', 'formatAsDisplayedMessage', 'retrieveDisplayedMessage', 'refreshOneMessage',
    'getCurrentMessageId', 'getLastMessageId',
    'getCharacter', 'getCurrentCharacterName', 'getWorldbook', 'getWorldbookNames',
    'getPreset', 'getPresetNames', 'loadPreset',
    'generate', 'generateRaw', 'stopGenerationById', 'stopAllGeneration',
    'triggerSlash', 'substitudeMacros', 'registerMacroLike', 'injectPrompts', 'uninjectPrompts',
    'initializeGlobal', 'waitGlobalInitialized', 'getTavernHelperVersion', 'getTavernVersion',
    'errorCatched', 'errorCatchedAsync'
  ];
  HOST.forEach(function (name) { window[name] = rec(name); });

  window.addEventListener('error', function (e) {
    log({ t: 'error', p: (e.message || '') + ' @' + (e.filename || '').split('/').pop() + ':' + (e.lineno || '') });
  });
  window.addEventListener('unhandledrejection', function (e) {
    log({ t: 'reject', p: safe(e.reason && e.reason.message ? e.reason.message : e.reason) });
  });
  var origError = console.error;
  console.error = function () {
    log({ t: 'console.error', p: Array.prototype.map.call(arguments, safe).join(' ') });
    return origError.apply(console, arguments);
  };

  window.__PROBE__ = {
    log: LOG, ready: true,
    setPhase: value => { phase = value; },
    fire: (name, args = []) => fire(name, args),
    setState: value => { state = structuredClone(value); },
    snapshot: () => ({ log: LOG, ready: true, dropped, phase, state: structuredClone(state), messages: structuredClone(messages), listeners: [...listeners.keys()], pendingGlobals: [...waiters.keys()] })
  };
}
