import test from 'node:test';
import assert from 'node:assert/strict';
import { isResizeObserverNotification, observeFrameSize } from '../src/resize-observer.mjs';

test('only the two browser resize notifications are classified as recoverable', () => {
  assert.equal(isResizeObserverNotification('ResizeObserver loop completed with undelivered notifications.'), true);
  assert.equal(isResizeObserverNotification('ResizeObserver loop limit exceeded'), true);
  for (const message of [undefined, 'ResizeObserver is not defined', 'ResizeObserver callback failed',
    'Uncaught Error: ResizeObserver loop limit exceeded']) assert.equal(isResizeObserverNotification(message), false);
});

test('size observation coalesces writes and cancels work when disposed', () => {
  let callback, disconnected = false, measured = 0, id = 0;
  const queued = new Map(), body = {};
  const scheduler = {
    requestAnimationFrame(fn) { queued.set(++id, fn); return id; },
    cancelAnimationFrame(key) { queued.delete(key); },
  };
  const stop = observeFrameSize({ document: { body }, ResizeObserver: class {
    constructor(fn) { callback = fn; }
    observe(node) { assert.equal(node, body); }
    disconnect() { disconnected = true; }
  } }, () => measured++, scheduler);
  callback(); callback(); callback();
  assert.equal(measured, 0);
  assert.equal(queued.size, 1);
  const first = queued.get(id); queued.delete(id); first();
  assert.equal(measured, 1);
  callback();
  const pending = queued.get(id);
  stop();
  assert.equal(disconnected, true);
  assert.equal(queued.size, 0);
  pending(); callback();
  assert.equal(measured, 1);
  assert.equal(queued.size, 0);
});
