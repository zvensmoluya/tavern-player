import get from 'lodash/get.js';
import has from 'lodash/has.js';
import set from 'lodash/set.js';
import unset from 'lodash/unset.js';
import toPath from 'lodash/toPath.js';

const clone = value => JSON.parse(JSON.stringify(value));

/** One author session: live objects and callbacks never pass through JSON or the native bridge. */
export function createSession(initial) {
  let base = clone(initial), state = clone(initial), failed = null, queue = Promise.resolve();
  const pending = [], owners = new Set(), listeners = new Map(), globals = {}, globalOwners = new Map(), waiters = [];
  const check = owner => { if (failed) throw failed; if (owner && !owner.active) throw new Error('Runtime disposed'); };
  function changed() {
    for (const owner of owners) {
      try { owner.changed?.(state); } catch (error) { owner.notify('error', error.message); }
    }
  }
  function rebuild() { state = clone(base); for (const item of pending) if (item.owner.active) item.project?.(state); changed(); }
  function receive(next) {
    base = clone(next);
    accept(() => rebuild());
  }
  // The shell already knows which messages changed. Keep unchanged historical payloads local
  // instead of serializing and cloning them again on every draft or streaming notification.
  function receiveDelta(delta) {
    function apply(current) {
      const changes = clone(delta.changes ?? {});
      if (!delta.messages?.length && !delta.order) return { ...current, ...changes };
      const messages = new Map(current.messages.map(message => [message.turnId, message]));
      for (const message of delta.messages ?? []) messages.set(message.turnId, clone(message));
      const order = delta.order ?? current.messages.map(message => message.turnId);
      return { ...current, ...changes, messages: order.map(id => {
        if (!messages.has(id)) throw new Error('Missing message in author delta');
        return messages.get(id);
      }) };
    }
    const nextBase = apply(base), nextState = pending.length ? null : apply(state);
    base = nextBase;
    accept(() => {
      if (nextState && !pending.length) { state = nextState; changed(); } else rebuild();
    }, Boolean(delta.messages?.length || delta.order));
  }
  function accept(update, checkCandidates = true) {
    // Invalidate a retired candidate before replaying optimistic writes onto the new one.
    if (checkCandidates) {
      const candidates = new Map(base.messages.map(message => [message.turnId, message.variantId]));
      for (const owner of [...owners]) if (owner.actor.variantId &&
        candidates.get(owner.actor.turnId) !== owner.actor.variantId) dispose(owner);
    }
    update();
    for (const waiter of [...waiters]) if (has(globals, waiter.name) || (waiter.name === 'Mvu' && state.mvu)) {
      waiters.splice(waiters.indexOf(waiter), 1); bind(waiter.owner, waiter.name); waiter.resolve();
    }
  }
  function bind(owner, name) {
    if (!has(globals, name)) return;
    const path = toPath(name), key = path.pop();
    for (const installed of owner.targets) {
      let target = installed;
      for (const segment of path) {
        if (!target[segment] || typeof target[segment] !== 'object') target[segment] = {};
        target = target[segment];
      }
      Object.defineProperty(target, key, { configurable: true, get: () => get(globals, name), set: value => initialize(owner, name, value) });
    }
  }
  function initialize(owner, name, value) {
    check(owner);
    if (typeof name !== 'string' || !name.length) throw new Error('Invalid global name');
    set(globals, name, value); globalOwners.set(name, owner);
    for (const target of owners) bind(target, name);
    for (const waiter of [...waiters]) if (waiter.name === name) {
      waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve();
    }
    emit('global_' + name + '_initialized', []).catch(error => owner.notify('error', error.message));
  }
  function remove(owner, name, fn) {
    listeners.set(name, (listeners.get(name) ?? []).filter(item => item.owner !== owner || (fn && item.fn !== fn)));
  }
  function on(owner, name, fn, once = false, position) {
    check(owner);
    if (typeof name !== 'string' || typeof fn !== 'function') throw new Error('Invalid event listener');
    const list = listeners.get(name) ?? [];
    let item = list.find(value => value.owner === owner && value.fn === fn);
    if (!item) item = { owner, fn, once };
    else if (!position) return { stop: () => remove(owner, name, fn) };
    const next = list.filter(value => value !== item);
    position === 'first' ? next.unshift(item) : next.push(item); listeners.set(name, next);
    return { stop: () => remove(owner, name, fn) };
  }
  function ready(name, item) {
    if (!item.owner.active || !(listeners.get(name) ?? []).includes(item)) return false;
    if (item.once) remove(item.owner, name, item.fn);
    return true;
  }
  async function emit(name, args) {
    check();
    for (const item of [...(listeners.get(name) ?? [])]) if (ready(name, item)) {
      try {
        const result = item.fn(...args);
        if (item.once) result?.catch?.(error => item.owner.notify('error', error.message));
        else await Promise.race([result, item.owner.closed]);
      } catch (error) { item.owner.notify('error', error.message); }
    }
  }
  function emitSync(name, args) {
    check();
    for (const item of [...(listeners.get(name) ?? [])]) if (ready(name, item)) {
      try {
        const result = item.fn(...args);
        if (result?.then) result.catch(error => item.owner.notify('error', error.message));
      } catch (error) { item.owner.notify('error', error.message); }
    }
  }
  function dispose(owner) {
    if (!owner.active) return;
    owner.active = false;
    owner.cancelEvents();
    owner.onDispose?.(); owner.onDispose = null; owner.onStop = null;
    for (const name of listeners.keys()) remove(owner, name);
    for (const [name, provider] of globalOwners) if (provider === owner) { unset(globals, name); globalOwners.delete(name); }
    for (const waiter of [...waiters]) if (waiter.owner === owner) { waiters.splice(waiters.indexOf(waiter), 1); waiter.reject(new Error('Runtime disposed')); }
    owners.delete(owner); owner.targets.clear(); owner.changed = null;
    for (let index = pending.length - 1; index >= 0; index--) if (pending[index].owner === owner) pending.splice(index, 1);
    rebuild();
  }
  function stop(error) {
    if (failed) return;
    failed = error instanceof Error ? error : new Error(String(error)); pending.length = 0; state = clone(base);
    listeners.clear();
    for (const waiter of waiters.splice(0)) waiter.reject(failed);
    changed();
    for (const owner of owners) {
      owner.cancelEvents();
      owner.onStop?.(failed);
      owner.notify('error', '宿主操作未完成，当前程序已停止：' + failed.message);
    }
  }
  const session = {
    get state() { return state; }, get revision() { return base.revision; }, receive, receiveDelta, emit, emitSync, stop,
    attach(notify = () => {}, actor = {}) {
      check();
      let cancelEvents;
      const closed = new Promise(resolve => { cancelEvents = resolve; });
      const owner = { id: actor.id, actor, active: true, notify, targets: new Set(), changed: null, closed, cancelEvents };
      owners.add(owner); return owner;
    },
    disposeFrame(id) { for (const owner of [...owners]) if (owner.id === id) dispose(owner); },
    destroy() { for (const owner of [...owners]) dispose(owner); stop(new Error('Session disposed')); },
    install(owner, target) { check(owner); owner.targets.add(target); for (const name of globalOwners.keys()) bind(owner, name); },
    observe(owner, callback) { check(owner); owner.changed = callback; callback(state); },
    initialize, on, remove, dispose,
    clear(owner, fn) { for (const name of listeners.keys()) remove(owner, name, fn); },
    wait(owner, name) {
      check(owner);
      if (has(globals, name) || (name === 'Mvu' && state.mvu)) { bind(owner, name); return Promise.resolve(); }
      return new Promise((resolve, reject) => waiters.push({ owner, name, resolve, reject }));
    },
    enqueue(owner, request, method, args, project) {
      try { check(owner); } catch (error) { return Promise.reject(error); }
      args = clone(args);
      const item = { owner, project }; pending.push(item); project?.(state); changed();
      const run = queue.then(async () => {
        check(owner);
        try {
          const result = await request(method, args, base.revision);
          const index = pending.indexOf(item); if (index >= 0) pending.splice(index, 1);
          if (result?.snapshot) receive({ ...base, ...result.snapshot }); else rebuild();
          return result?.value;
        } catch (error) {
          if (owner.active) stop(error);
          else { const index = pending.indexOf(item); if (index >= 0) pending.splice(index, 1); rebuild(); }
          throw error;
        }
      });
      queue = run.catch(() => {}); return run;
    },
    async flush(owner) { await queue; check(owner); },
    check,
  };
  return session;
}
