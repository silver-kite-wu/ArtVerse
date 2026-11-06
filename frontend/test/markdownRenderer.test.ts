import test from 'node:test';
import assert from 'node:assert/strict';
import { Marked } from 'marked';
import { normalizeMarkdown } from '../src/components/markdownNormalization.ts';

const markdown = new Marked({ breaks: true, gfm: true });

test('keeps a valid chapter heading on one line', () => {
  const source = '# 《第2章》评审（对照第1章定稿 v8与已确认的设定卡）';
  const normalized = normalizeMarkdown(source);

  assert.equal(normalized, source);
  const html = markdown.parse(normalized) as string;
  assert.match(html, /<h1>《第2章》评审（对照第1章定稿 v8与已确认的设定卡）<\/h1>/);
  assert.doesNotMatch(html, /《第2<br>/);
});

test('keeps the heading and following bold paragraph as separate blocks', () => {
  const source = [
    '# 《第2章》评审（对照第1章定稿 v8与已确认的设定卡）',
    '',
    '**总评：结构骨架很好。**',
    '主要问题集中在三点。',
  ].join('\n');
  const html = markdown.parse(normalizeMarkdown(source)) as string;

  assert.match(html, /<h1>《第2章》评审（对照第1章定稿 v8与已确认的设定卡）<\/h1>/);
  assert.match(html, /<strong>总评：结构骨架很好。<\/strong>/);
});

test('still splits a heading when a bold paragraph was appended without a newline', () => {
  const normalized = normalizeMarkdown('## 📊总评分：6.5 /10**节奏曲线**');

  assert.equal(normalized, '## 📊总评分：6.5 /10\n**节奏曲线**');
});
