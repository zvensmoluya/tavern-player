import { marked } from 'marked';

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

function closedFence(token) {
  const opening = /^ {0,3}(`{3,}|~{3,})[^\n]*\n/.exec(token.raw);
  if (!opening) return false;
  const fence = opening[1];
  return new RegExp('(?:^|\\n) {0,3}' + fence[0] + '{' + fence.length + ',}[ \\t]*(?:\\n)?$').test(token.raw.slice(opening[0].length));
}

export function segmentMarkdown(text, incomplete = false) {
  const result = [], tokens = marked.lexer(text); let normal = [];
  const flush = () => {
    if (normal.length) { normal.links = tokens.links; result.push({ kind: 'normal', text: marked.parser(normal) }); normal = []; }
  };
  for (const token of tokens) {
    if (token.type === 'code') {
      if (/<body(?:\s[^>]*)?>/i.test(token.text) && (/<\/body\s*>/i.test(token.text) || token.lang?.toLowerCase() === 'html')) {
        flush(); result.push({ kind: 'page', text: token.text }); continue;
      }
      const lang = token.lang?.toLowerCase() ?? '';
      const possiblePage = ['h', 'ht', 'htm', 'html'].includes(lang) || (!lang && /^\s*(?:<|$)/.test(token.text));
      if (incomplete && possiblePage && !closedFence(token)) {
        flush(); result.push({ kind: 'pending', text: '' }); continue;
      }
      normal.push(token); continue;
    }
    let safe = incomplete ? completeHtmlPrefix(token.raw) : token.raw;
    // A fence marker can itself span provider chunks. Do not flash one or two backticks.
    if (incomplete) safe = safe.replace(/(?:^|\n) {0,3}(?:`{1,2}|~{1,2})$/, '');
    normal.push(...(safe === token.raw ? [token] : marked.lexer(safe)));
    if (safe !== token.raw) break;
  }
  flush(); return result;
}
