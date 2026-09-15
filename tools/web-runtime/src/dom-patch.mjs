// Patch sanitized content only. This must never be used to update an executable author page.
// Remember source attributes separately from the live DOM so opening <details> or editing a
// form does not get undone by an unrelated streamed suffix.
const attributes = new WeakMap();
function remember(node) {
  if (node.nodeType === 1) attributes.set(node, new Map([...node.attributes].map(attr => [attr.name, attr.value])));
  for (const child of node.childNodes) remember(child);
}
const compatible = (node, next) => node.nodeType === next.nodeType && node.nodeName === next.nodeName &&
  node.namespaceURI === next.namespaceURI && (node.nodeType !== 1 || node.id === next.id);

export function patchChildren(target, source) {
  let current = target.firstChild, remainingById = null;
  for (const next of source.childNodes) {
    if (!current || !compatible(current, next)) {
      // Reuse an explicitly identified sibling when blocks are inserted or removed.
      if (next.nodeType === 1 && next.id && !remainingById) {
        remainingById = new Map();
        for (let node = current; node; node = node.nextSibling) if (node.nodeType === 1 && node.id) remainingById.set(node.id, node);
      }
      const candidate = next.nodeType === 1 && next.id && remainingById?.get(next.id);
      const keyed = candidate && compatible(candidate, next) ? candidate : null;
      if (keyed) { target.insertBefore(keyed, current); current = keyed; }
      else {
        const node = target.ownerDocument.importNode(next, true); remember(node);
        target.insertBefore(node, current); continue;
      }
    }
    if (current.nodeType === 1) remainingById?.delete(current.id);
    if (current.nodeType === 1) {
      const before = attributes.get(current) ?? new Map([...current.attributes].map(attr => [attr.name, attr.value]));
      const after = new Map([...next.attributes].map(attr => [attr.name, attr.value]));
      for (const name of before.keys()) if (!after.has(name)) current.removeAttribute(name);
      for (const [name, value] of after) if (before.get(name) !== value) current.setAttribute(name, value);
      attributes.set(current, after);
      patchChildren(current, next);
    } else if (current.nodeValue !== next.nodeValue) {
      // appendData leaves a reader's selection in the already visible prefix intact.
      if (current.nodeType === 3 && next.nodeValue.startsWith(current.nodeValue)) current.appendData(next.nodeValue.slice(current.nodeValue.length));
      else current.nodeValue = next.nodeValue;
    }
    current = current.nextSibling;
  }
  while (current) { const next = current.nextSibling; current.remove(); current = next; }
}
