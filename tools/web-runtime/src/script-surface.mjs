// A script can put controls in its compatibility parent. Report the painted control
// rectangles so the shell can leave the rest of this cross-origin iframe click-through.
export function observeScriptSurface(send) {
  const hostNodes = new Set(document.body.children);
  let pending = 0, previous = '', observed = new Set();
  function schedule() {
    // A clipped cross-origin iframe can have its animation frames suspended.
    if (!pending) pending = setTimeout(measure, 0);
  }
  const sizes = new ResizeObserver(schedule);
  function measure() {
    pending = 0;
    const rects = [], next = new Set();
    let coversViewport = false;
    function visit(element) {
      if (coversViewport || hostNodes.has(element) || ['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName)) return;
      const style = getComputedStyle(element);
      if (style.display === 'none' || Number(style.opacity) === 0) return;
      const box = element.getBoundingClientRect();
      if (style.visibility === 'visible' && style.pointerEvents !== 'none' && box.width && box.height) {
        next.add(element);
        const left = Math.max(0, Math.floor(box.left)), top = Math.max(0, Math.floor(box.top));
        const right = Math.min(innerWidth, Math.ceil(box.right)), bottom = Math.min(innerHeight, Math.ceil(box.bottom));
        if (left < right && top < bottom) {
          coversViewport = left === 0 && top === 0 && right === innerWidth && bottom === innerHeight;
          if (coversViewport) rects.length = 0;
          rects.push([left, top, right, bottom]);
        }
      }
      // Fixed children can extend outside a wrapper, including a zero-sized mount point.
      for (const child of element.children) visit(child);
    }
    for (const element of document.body.children) visit(element);
    for (const node of observed) if (!next.has(node)) sizes.unobserve(node);
    for (const node of next) if (!observed.has(node)) sizes.observe(node);
    observed = next;
    const value = JSON.stringify(rects);
    if (value !== previous) { previous = value; send(rects); }
  }
  const mutations = new MutationObserver(schedule);
  mutations.observe(document.documentElement, { subtree: true, attributes: true, childList: true, characterData: true });
  const events = ['resize', 'scroll', 'transitionstart', 'transitionend', 'animationend', 'load'];
  for (const event of events) window.addEventListener(event, schedule, true);
  schedule();
  return () => {
    mutations.disconnect(); sizes.disconnect(); clearTimeout(pending);
    for (const event of events) window.removeEventListener(event, schedule, true);
  };
}
