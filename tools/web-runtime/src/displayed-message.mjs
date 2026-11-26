import $ from 'jquery';
import DOMPurify from 'dompurify';
import { segmentMarkdown } from './markdown.mjs';

// The author can edit display text in its own document. Never pass shell nodes or
// executable message pages across the frame boundary.
export function displayedMessage(message) {
  const body = document.createElement('div');
  const nodes = [];
  if (message) {
    for (const part of segmentMarkdown(message.display ?? message.message)) {
      if (part.kind !== 'normal') continue;
      const fragment = DOMPurify.sanitize(part.text, { RETURN_DOM_FRAGMENT: true,
        FORBID_TAGS: ['script', 'style', 'textarea', 'iframe', 'object', 'embed', 'img', 'video', 'audio', 'source', 'link'] });
      const walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) nodes.push([walker.currentNode, walker.currentNode.data]);
      body.append(fragment);
    }
  }
  return {
    element: message ? $(body) : $(),
    changes: () => Object.fromEntries(nodes.filter(([node, original]) => node.data !== original)
      .map(([node, original]) => [original, node.data])),
  };
}

// Values are assigned as text after Markdown parsing. Keep HTML attributes,
// executable pages and styles outside this projection.
export function applyDisplayChanges(html, changes) {
  if (!changes || !Object.keys(changes).length) return html;
  const template = document.createElement('template');
  template.innerHTML = html;
  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT);
  let changed = false;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (Object.hasOwn(changes, node.data) && !node.parentElement?.closest('script,style,textarea')) {
      node.data = changes[node.data]; changed = true;
    }
  }
  return changed ? template.innerHTML : html;
}
