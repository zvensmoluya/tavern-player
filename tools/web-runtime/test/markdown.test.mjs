import test from 'node:test';
import assert from 'node:assert/strict';
import { completeHtmlPrefix, segmentMarkdown } from '../src/markdown.mjs';

test('standalone author markers leave the following document fence intact', () => {
  const html = '<head><style>.panel{color:red}</style></head><body>Panel</body>';
  for (const [marker, language] of [['<opening7 />', 'html'], ['<panel mode>', 'text']]) {
    const source = marker + '\r\n```' + language + '\n' + html + '\n```\n\nAfter';
    const parts = segmentMarkdown(source);
    assert.deepEqual(parts.map(p => p.kind), ['normal', 'page', 'normal']);
    assert.equal(parts[1].text, html);
    for (let i = source.indexOf('`'); i < source.indexOf('After'); i++) {
      const normal = segmentMarkdown(source.slice(0, i), true).filter(p => p.kind === 'normal');
      assert.doesNotMatch(normal.map(p => p.text).join(''), /panel\{|&lt;head|```|language-/);
    }
  }
  for (const tag of ['pre', 'script', 'code', 'template', 'object']) {
    const raw = '<' + tag + '>\n```html\n' + html + '\n```\n</' + tag + '>';
    assert.equal(segmentMarkdown(raw).some(p => p.kind === 'page'), false);
  }
  const literal = '````markdown\n<card-marker/>\n```html\n' + html + '\n```\n````';
  const [example] = segmentMarkdown(literal);
  assert.equal(example.kind, 'normal');
  assert.match(example.text, /&lt;body&gt;Panel/);
});

test('streamed HTML fences never expose document source before the body arrives', () => {
  const source = 'Hello\n\n```html\n<!doctype html><html><head><style>.card{color:red}</style></head><body><p>World</p></body></html>\n```';
  for (let end = source.indexOf('```') + 1; end <= source.length; end++) {
    const parts = segmentMarkdown(source.slice(0, end), true);
    const visible = parts.filter(part => part.kind === 'normal').map(part => part.text).join('');
    assert.doesNotMatch(visible, /doctype|&lt;|card\{|```|language-h/, `prefix ${end}: ${visible}`);
  }
  assert.equal(segmentMarkdown(source, false).at(-1).kind, 'page');
});

test('streaming withholds unfinished HTML and CSS while retaining the preceding text', () => {
  for (const suffix of ['<', '</', '<di', '<div title="a >', '<!-- comment', '<style>.card{color:r', '<script>alert(1)']) {
    assert.equal(completeHtmlPrefix('Hello ' + suffix), 'Hello ', suffix);
  }
  const closed = '<div title="a > b">text</div><style>div{color:red}</style>';
  assert.equal(completeHtmlPrefix(closed), closed);
  assert.equal(completeHtmlPrefix('1 < 2 and `a <style>`'), '1 < 2 and `a <style>`');
  assert.equal(completeHtmlPrefix('Literal \\<style'), 'Literal \\<style');
  const open = segmentMarkdown('Hello\n\n<style>p{color:red', true);
  assert.equal(open.map(part => part.text).join('').trim(), '<p>Hello</p>');
  const full = segmentMarkdown('Hello\n\n<style>p{color:red}</style>', true);
  assert.match(full.map(part => part.text).join(''), /<style>p\{color:red\}<\/style>/);
});

test('complete and ordinary streamed Markdown keep reference links, lists and tables', () => {
  for (const [source, expected] of [
    ['See [guide][doc].\n\n[doc]: https://example.test/guide', /<a href="https:\/\/example.test\/guide">guide<\/a>/],
    ['- first\n- second **bold**', /<li>first<\/li>\s*<li>second <strong>bold<\/strong><\/li>/],
    ['| A | B |\n|---|---|\n| 1 | 2 |', /<table>[\s\S]*<td>2<\/td>[\s\S]*<\/table>/],
    ['> A quoted paragraph\n\nAnother paragraph.', /<blockquote>[\s\S]*quoted paragraph[\s\S]*<\/blockquote>\s*<p>Another paragraph\.<\/p>/],
  ]) {
    for (const incomplete of [true, false]) assert.match(segmentMarkdown(source, incomplete)[0].text, expected);
  }
});

test('ordinary fenced examples, inline code and completed HTML snippets remain code', () => {
  for (const source of ['```js\nconst text = "<style>";', '```html\n<div>Example</div>\n```', 'An example: `<style>`.']) {
    assert.match(segmentMarkdown(source, true).map(part => part.text).join(''), /<code/);
  }
  assert.match(segmentMarkdown('```html\n<div>Example', false)[0].text, /&lt;div&gt;/);
  assert.match(segmentMarkdown('```\nplain code', true)[0].text, /plain code/);
});

test('formatted blocks preserve the order of static text and independent pages from one message', () => {
  const story = '<html><body><p>A &amp; B</p></body></html>';
  const actions = '<head><title>Options</title></head>';
  const body = ['```', story, '```'].join('\n');
  const choice = ['> ```html', '> ' + actions, '> ```'].join('\n');
  for (const gap of ['\n', '\n\n']) {
    const source = '<div>Time</div>' + gap + body + '\n\n> Choices\n>\n' + choice + '\n>\n> After';
    for (const incomplete of [true, false]) {
      const parts = segmentMarkdown(source, incomplete);
      assert.deepEqual(parts.map(p => p.kind), ['normal', 'page', 'normal', 'page', 'normal']);
      assert.equal(parts[1].text, story);
      assert.equal(parts[3].text, actions);
      assert.match(parts[0].text, /<div>Time<\/div>/);
      assert.match(parts[2].text, /<blockquote>[\s\S]*Choices/);
      assert.match(parts[4].text, /<blockquote>[\s\S]*After/);
    }
  }
  const raw = '<details><summary>Summary</summary></details>\n\n<pre>&lt;head&gt;&lt;title&gt;A &amp;amp; B&lt;/title&gt;&lt;/head&gt;</pre>';
  assert.deepEqual(segmentMarkdown(raw).map(p => p.kind), ['normal', 'page']);
  assert.equal(segmentMarkdown(raw)[1].text, '<head><title>A &amp; B</title></head>');
  const list = '<ul><li>First</li><li><pre><span>&lt;body&gt;</span>Story&lt;/body&gt;</pre></li><li>Last</li></ul>';
  const items = segmentMarkdown(list);
  assert.deepEqual(items.map(part => part.kind), ['normal', 'page', 'normal']);
  assert.equal(items[1].text, '<body>Story</body>');
  assert.match(items[0].text, /<li>First<\/li>/);
  assert.match(items[2].text, /<li>Last<\/li>/);
});
