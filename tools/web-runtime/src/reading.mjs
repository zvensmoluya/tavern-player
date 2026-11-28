import { parse, parseFragment } from 'parse5';
import { segmentMarkdown } from './markdown.mjs';

const blocks = new Set('p div section article main aside blockquote h1 h2 h3 h4 h5 h6 ul ol li dl dt dd table tr header footer figure figcaption'.split(' '));
const inline = new Set('a b strong i em u s del span small mark code abbr time label q td th sup sub'.split(' '));
const interaction = '〔互动内容〕';
const attr = (node, name) => node.attrs?.find(a => a.name === name)?.value;
const hasChildren = (node, predicate) => predicate(node) || node.childNodes?.some(child => hasChildren(child, predicate));

// Only text runs cross into the reader. Pages are parsed as data, including when they contain scripts.
// Without a browser layout, only purely typographic author CSS can be assumed to expose its text.
function readableStyle(css) {
  return !css.trim() || /^(?:\s*(?:color|background-color|font-family|font-size|font-weight|font-style|line-height|text-align|padding(?:-\w+)?|border(?:-\w+)*|margin(?:-\w+)?)\s*:\s*[^;{}@()]+;?)+\s*$/i.test(css) &&
    !/transparent|font-size\s*:\s*0(?:[a-z%]*\s*;|\s*$)/i.test(css);
}

function readableStylesheet(node) {
  const css = node.childNodes.map(n => n.value ?? '').join('').replace(/\/\*[\s\S]*?\*\//g, '');
  // Flat rules with presentation-only declarations can be ignored for a reflowable book.
  return !css.replace(/[^{}@]+\{([^{}]*)\}/g, (rule, declarations) =>
    readableStyle(declarations) ? '' : rule).trim();
}

function hidden(node) {
  return ['think', 'thinking', 'reasoning', 'analysis', 'script', 'template', 'updatevariable'].includes(node.tagName) ||
    attr(node, 'hidden') !== undefined || attr(node, 'aria-hidden')?.toLowerCase() === 'true' ||
    /(?:display\s*:\s*none|visibility\s*:\s*(?:hidden|collapse)|opacity\s*:\s*0(?:[;\s!]|$))/i.test(attr(node, 'style') ?? '');
}

// Markdown may split an inline hidden element across paragraphs, losing its closing tag.
// Remove those exact source ranges before formatting, leaving the surrounding Markdown intact.
function withoutHiddenSource(source) {
  if (!source.includes('<')) return source;
  const ranges = [];
  function visit(n) {
    const loc = n.sourceCodeLocation;
    if (loc && hidden(n)) ranges.push([loc.startOffset, loc.endOffset]);
    else n.childNodes?.forEach(visit);
  }
  visit(parseFragment(source, { sourceCodeLocationInfo: true }));
  let end = 0, text = '';
  for (const [from, to] of ranges.sort((a, b) => a[0] - b[0])) {
    if (from >= end) text += source.slice(end, from);
    end = Math.max(end, to);
  }
  return text + source.slice(end);
}

export function paragraphs(display, changes = {}, incomplete = false) {
  const result = [];
  let runs = [];
  function write(text, bold = false, italic = false) {
    const last = runs.at(-1);
    if (!text) return;
    if (last && last.bold === bold && last.italic === italic) last.text += text;
    else runs.push({ text, bold, italic });
  }
  function end() {
    if (runs.some(run => run.text.trim())) {
      runs[0].text = runs[0].text.trimStart();
      runs.at(-1).text = runs.at(-1).text.trimEnd();
      result.push(runs.filter(run => run.text));
    }
    runs = [];
  }
  function placeholder(label) { end(); write(label); end(); }
  function walk(node, replacements, bold = false, italic = false) {
    if (node.nodeName === '#text') {
      const text = Object.hasOwn(replacements, node.value) ? replacements[node.value] : node.value;
      const collapsed = text.replace(/[ \t\r\n]+/g, ' ');
      write(!runs.length || runs.at(-1).text.endsWith('\n') ? collapsed.trimStart() : collapsed, bold, italic);
      return;
    }
    const tag = node.tagName;
    if (hidden(node)) return;
    if (!readableStyle(attr(node, 'style') ?? '')) { placeholder(interaction); return; }
    if (tag === 'br') { write('\n'); return; }
    if (['img', 'svg', 'canvas', 'iframe', 'audio', 'video', 'form', 'input', 'button', 'select', 'textarea', 'pre'].includes(tag)) {
      placeholder(tag === 'img' ? '〔图片〕' : interaction); return;
    }
    if (tag === 'details') {
      const summary = node.childNodes.find(child => child.tagName === 'summary');
      if (summary) { end(); summary.childNodes.forEach(child => walk(child, replacements, bold, italic)); end(); }
      if (attr(node, 'open') === undefined) return;
    } else if (tag && !blocks.has(tag) && !inline.has(tag) && !['body', 'tbody', 'thead', 'tfoot'].includes(tag)) return;
    if (blocks.has(tag)) end();
    if (tag === 'li') write('• ');
    for (const child of node.childNodes ?? []) walk(child, replacements,
      bold || tag === 'strong' || tag === 'b' || /^h[1-6]$/.test(tag), italic || tag === 'em' || tag === 'i');
    if (tag === 'td' || tag === 'th') write(' ');
    if (blocks.has(tag)) end();
  }
  for (const part of segmentMarkdown(withoutHiddenSource(display), incomplete)) {
    if (part.kind === 'pending') { placeholder(interaction); continue; }
    const page = part.kind === 'page';
    const tree = page ? parse(part.text) : parseFragment(part.text);
    const body = page ? tree.childNodes.find(n => n.tagName === 'html')?.childNodes.find(n => n.tagName === 'body') : tree;
    const before = result.length;
    const styled = hasChildren(tree, n => (n.tagName === 'style' && !readableStylesheet(n)) ||
      (n.tagName === 'link' && attr(n, 'rel')?.toLowerCase().includes('stylesheet')));
    if (body && !styled) walk(body, page ? {} : changes);
    end();
    if (styled || (page && before === result.length)) placeholder(interaction);
  }
  return result;
}
