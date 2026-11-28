import test from 'node:test';
import assert from 'node:assert/strict';
import { paragraphs } from '../src/reading.mjs';

const text = result => result.map(runs => runs.map(r => r.text).join('')).join('\n\n');

test('reading paragraphs preserve display replacements, emphasis and hard breaks', () => {
  const result = paragraphs('雨停了。\n下一行\n\n**原话**和*回声*。', { 原话: '别开门' });
  assert.equal(text(result), '雨停了。\n下一行\n\n别开门和回声。');
  assert.equal(result[1][0].bold, true);
  assert.equal(result[1][2].italic, true);
});

test('extract static page words without copying controls, scripts or hidden instructions', () => {
  const source = '开场。\n\n~~~html\n<html><head><style>p{padding:2em;color:red}</style></head>' +
    '<body><p>钟响了。</p><div hidden>秘密</div><button onclick="privateAction()">行动</button>' +
    '<script>privateSource()</script></body></html>\n~~~\n\n尾声。';
  assert.equal(text(paragraphs(source)), '开场。\n\n钟响了。\n\n〔互动内容〕\n\n尾声。');
  assert.equal(text(paragraphs('<think>secret\n\nmore secret</think>\n\n落雨。')), '落雨。');
  assert.equal(text(paragraphs('<span hidden>secret\n\nmore secret</span>\n\n落雨。')), '落雨。');
  assert.equal(text(paragraphs('<style>.private{display:none}</style><p class="private">暗号</p>')), '〔互动内容〕');
  assert.equal(text(paragraphs('![封面](https://example.org/private.png)')), '〔图片〕');
});
