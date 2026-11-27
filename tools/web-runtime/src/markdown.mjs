import showdown from 'showdown';
import { parseFragment, serializeOuter } from 'parse5';

// Match the Markdown dialect used to format ST messages before Tavern Helper inspects them.
const converter = new showdown.Converter({
  emoji: true, literalMidWordUnderscores: true, parseImgDimensions: true, tables: true,
  underline: true, simpleLineBreaks: true, strikethrough: true, disableForced4SpacesIndentedSublists: true,
  omitExtraWLInCodeBlocks: true,
});

// Keep unfinished tags, quoted attributes and raw CSS/script bodies out of the preview.
// Ordinary HTML text can still grow inside open elements. Markdown code stays literal.
export function completeHtmlPrefix(text) {
  for (let index = 0; index < text.length; index++) {
    if (text[index] === '\\') { index++; continue; }
    if (text[index] === '`') {
      const ticks = /^`+/.exec(text.slice(index))[0];
      const end = text.indexOf(ticks, index + ticks.length);
      if (end < 0) return text;
      index = end + ticks.length - 1; continue;
    }
    if (text[index] !== '<') continue;
    const tail = text.slice(index);
    if (!/^<(?:\/?[a-zA-Z]|!|\?|\/?$)/.test(tail)) continue;
    if (tail.startsWith('<!--')) {
      const end = text.indexOf('-->', index + 4);
      if (end < 0) return text.slice(0, index);
      index = end + 2; continue;
    }
    let quote = null, end = index + 1;
    for (; end < text.length; end++) {
      const char = text[end];
      if (quote) { if (char === quote) quote = null; }
      else if (char === '"' || char === "'") quote = char;
      else if (char === '>') break;
    }
    if (end === text.length) return text.slice(0, index);
    const raw = /^<(style|script)\b/i.exec(tail);
    if (raw) {
      const close = new RegExp('</' + raw[1] + '\\s*>', 'ig'); close.lastIndex = end + 1;
      const match = close.exec(text);
      if (!match) return text.slice(0, index);
      end = close.lastIndex - 1;
    }
    index = end;
  }
  return text;
}

// A streaming message can end part way through a fence. Showdown needs the closing
// delimiter to produce a code block. Complete the preview copy or hold a possible page.
function prepareMarkdown(text, incomplete) {
  const lines = /^([ \t]*(?:>[ \t]*)*)(?:([-+*]|\d+[.)])[ \t]+)?(`{3,}|~{3,})([^\n]*)$/gm;
  let open, match, lastClosed = 0;
  while ((match = lines.exec(text))) {
    const [, indent, item, fence, info] = match;
    if (open) {
      if (fence[0] === open.fence[0] && fence.length >= open.fence.length && !info.trim()) {
        open = null;
        lastClosed = lines.lastIndex;
      }
    } else if (!info.includes(fence[0]) && (indent.includes('>') || indent.length < 4 || item)) {
      open = { index: match.index, fence, prefix: indent + (item ? ' '.repeat(item.length + 1) : ''),
        language: info.trim().toLowerCase(), start: lines.lastIndex };
    }
  }
  if (open) {
    const { language, start, index, prefix, fence } = open;
    const content = text.slice(start).replace(/^\s*(?:>\s*)*/, '');
    const possible = (language && 'html'.startsWith(language)) ||
      ((!language || 'text'.startsWith(language)) && (!content || content.startsWith('<')));
    if (incomplete && possible) return { text: text.slice(0, index), pending: true };
    return { text: text + '\n' + prefix + fence };
  }
  if (!incomplete) return { text };
  const tail = text.slice(lastClosed).replace(/(?:^|\n)[ \t>]*(?:`{1,2}|~{1,2})$/, '');
  return { text: text.slice(0, lastClosed) + completeHtmlPrefix(tail) };
}

// Walk an inert HTML tree. Only text in a displayed pre is a page candidate.
// Literal examples and the contents of discarded or foreign elements stay inert.
const opaque = new Set(['code', 'script', 'style', 'textarea', 'template', 'iframe', 'object', 'embed',
  'noscript', 'noembed', 'noframes', 'plaintext', 'xmp', 'select', 'title', 'head', 'audio', 'video']);
const htmlNamespace = 'http://www.w3.org/1999/xhtml';

// Syntax highlighters may wrap individual tokens in spans inside the code block.
function codeText(parent) {
  let text = '';
  for (const child of parent.childNodes) {
    if (child.nodeName === '#text') text += child.value;
    else if (child.nodeName !== '#comment') {
      if (child.namespaceURI !== htmlNamespace || child.tagName === 'pre' ||
          (child.tagName !== 'code' && opaque.has(child.tagName))) return null;
      const nested = codeText(child);
      if (nested === null) return null;
      text += nested;
    }
  }
  return text;
}

function pageSource(pre) {
  const text = codeText(pre);
  if (!text || !/(?:html>|<head>|<body\b)/i.test(text)) return null;
  const code = pre.childNodes.find(child => child.tagName === 'code') ?? pre;
  const classes = code.attrs.find(attr => attr.name === 'class')?.value ?? '';
  if (/\blanguage-(md|markdown)\b/i.test(classes) && /^ {0,3}(?:`{3,}|~{3,})/m.test(text)) return null;
  return text;
}

function splitPages(parent) {
  const parts = [];
  let normal = [];
  function flush() {
    if (normal.some(node => node.nodeName !== '#text' || node.value.trim())) parts.push({ nodes: normal });
    normal = [];
  }
  for (const node of parent.childNodes) {
    if (node.tagName === 'pre') {
      const source = pageSource(node);
      if (source !== null) {
        flush();
        parts.push({ kind: 'page', text: source });
        continue;
      }
    } else if (node.childNodes?.length && node.namespaceURI === htmlNamespace && !opaque.has(node.tagName)) {
      const nested = splitPages(node);
      if (nested) {
        for (const part of nested) {
          if (part.nodes) normal.push({ ...node, childNodes: part.nodes });
          else { flush(); parts.push(part); }
        }
        continue;
      }
    }
    normal.push(node);
  }
  if (!parts.length) return null;
  flush();
  return parts;
}

export function segmentMarkdown(text, incomplete = false) {
  const prepared = prepareMarkdown(text.replace(/\r\n?/g, '\n'), incomplete);
  const html = converter.makeHtml(prepared.text);
  const parts = /<pre\b/i.test(html) ? splitPages(parseFragment(html)) : null;
  const result = [];
  if (parts) {
    for (const part of parts) {
      result.push(part.nodes ? { kind: 'normal', text: part.nodes.map(node => serializeOuter(node)).join('') } : part);
    }
  } else if (html) {
    result.push({ kind: 'normal', text: html });
  }
  if (prepared.pending) result.push({ kind: 'pending', text: '' });
  return result;
}
