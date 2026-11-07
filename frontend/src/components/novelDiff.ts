interface TextChange { type: 'equal' | 'delete' | 'insert'; text: string }
export interface NovelDiffBlock { id: number; original: string; proposed: string; changed: boolean; oldLine: number; newLine: number }

// Bound the LCS matrix: long, completely rewritten chapters fall back to a
// replacement, while shared beginnings/endings still remain exact context.
function diffTokens(before: string[], after: string[], budget = 1_000_000): TextChange[] {
  let prefix = 0;
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix++;
  let suffix = 0;
  while (suffix < before.length - prefix && suffix < after.length - prefix && before[before.length - suffix - 1] === after[after.length - suffix - 1]) suffix++;
  const a = before.slice(prefix, before.length - suffix);
  const b = after.slice(prefix, after.length - suffix);
  const result: TextChange[] = [];
  const add = (type: TextChange['type'], text: string) => {
    if (!text) return;
    const previous = result.at(-1);
    if (previous?.type === type) previous.text += text;
    else result.push({ type, text });
  };
  add('equal', before.slice(0, prefix).join(''));
  if ((a.length + 1) * (b.length + 1) > budget) {
    add('delete', a.join('')); add('insert', b.join(''));
  } else {
    const width = b.length + 1;
    const matrix = new Uint32Array((a.length + 1) * width);
    for (let i = a.length - 1; i >= 0; i--) {
      for (let j = b.length - 1; j >= 0; j--) {
        matrix[i * width + j] = a[i] === b[j] ? matrix[(i + 1) * width + j + 1] + 1 : Math.max(matrix[(i + 1) * width + j], matrix[i * width + j + 1]);
      }
    }
    let i = 0, j = 0;
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i] === b[j]) { add('equal', a[i++]); j++; }
      else if (i < a.length && (j === b.length || matrix[(i + 1) * width + j] >= matrix[i * width + j + 1])) add('delete', a[i++]);
      else add('insert', b[j++]);
    }
  }
  add('equal', before.slice(before.length - suffix).join(''));
  return result;
}

export const textLines = (text: string): string[] => text.match(/[^\n]*\n|[^\n]+$/g) ?? [];

export function buildNovelDiff(original: string, proposed: string): NovelDiffBlock[] {
  const blocks: NovelDiffBlock[] = [];
  let oldLine = 1, newLine = 1;
  for (const part of diffTokens(textLines(original), textLines(proposed))) {
    const changed = part.type !== 'equal';
    let block = blocks.at(-1);
    if (!block || block.changed !== changed) {
      block = { id: blocks.length, changed, original: '', proposed: '', oldLine, newLine };
      blocks.push(block);
    }
    if (part.type !== 'insert') { block.original += part.text; oldLine += textLines(part.text).length; }
    if (part.type !== 'delete') { block.proposed += part.text; newLine += textLines(part.text).length; }
  }
  return blocks;
}

export function mergeNovelDiff(blocks: NovelDiffBlock[], excluded: ReadonlySet<number>): string {
  return blocks.map(block => excluded.has(block.id) ? block.original : block.proposed).join('');
}

export function inlineNovelDiff(original: string, proposed: string): TextChange[] {
  return diffTokens(Array.from(original), Array.from(proposed), 160_000);
}
