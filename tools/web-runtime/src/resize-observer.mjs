export function isResizeObserverNotification(message) {
  return message === 'ResizeObserver loop completed with undelivered notifications.' ||
    message === 'ResizeObserver loop limit exceeded';
}

// ResizeObserver runs during layout. Defer writes until the next animation frame.
export function observeFrameSize(target, measure, scheduler = window) {
  let pending = null, disposed = false;
  const observer = new target.ResizeObserver(() => {
    if (disposed || pending !== null) return;
    pending = scheduler.requestAnimationFrame(() => {
      pending = null;
      if (!disposed) measure();
    });
  });
  observer.observe(target.document.body);
  return () => {
    disposed = true;
    observer.disconnect();
    if (pending !== null) scheduler.cancelAnimationFrame(pending);
    pending = null;
  };
}
