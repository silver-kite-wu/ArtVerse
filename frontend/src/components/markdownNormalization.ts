// ---------------------------------------------------------------------------
// Convert space-aligned tabular text to GFM pipe tables
//
// AI responses often contain pseudo-tables where columns are separated by
// 2+ spaces or tabs instead of GFM pipe delimiters. This preprocessor
// detects consecutive lines with consistent column counts and rewrites
// them as GFM tables so the marked parser renders them as proper <table>.
// ---------------------------------------------------------------------------

function convertSpaceAlignedTables(src: string): string {
  const lines = src.split('\n');
  const result: string[] = [];
  let blockLines: string[] = [];
  let blockCols = 0;

  function flush() {
    if (blockLines.length >= 2 && blockCols >= 2) {
      const gfmRows: string[] = [];
      for (const line of blockLines) {
        const cells = line.split(/\s{2,}|\t|\u3000+/).map(c => c.trim()).filter(c => c !== '');
        while (cells.length < blockCols) cells.push('');
        gfmRows.push('| ' + cells.slice(0, blockCols).join(' | ') + ' |');
      }
      // Insert GFM alignment row after the header (first row).
      const sep = '|' + Array(blockCols).fill(' --- ').join('|') + '|';
      gfmRows.splice(1, 0, sep);
      result.push(...gfmRows);
    } else {
      result.push(...blockLines);
    }
    blockLines = [];
    blockCols = 0;
  }

  for (const line of lines) {
    const trimmed = line.trim();

    if (trimmed === '') {
      flush();
      result.push(line);
      continue;
    }

    if (trimmed.startsWith('|')) {
      flush();
      result.push(line);
      continue;
    }

    if (/^\s*(#{1,6}\s|[-*+]\s|\d+\.\s|[>])/.test(line)) {
      flush();
      result.push(line);
      continue;
    }

    const cells = line.split(/\s{2,}|\t|\u3000+/).map(c => c.trim()).filter(c => c !== '');
    if (cells.length >= 2) {
      if (blockCols === 0) {
        blockLines.push(line);
        blockCols = cells.length;
      } else if (cells.length === blockCols) {
        blockLines.push(line);
      } else {
        flush();
        blockLines.push(line);
        blockCols = cells.length;
      }
    } else {
      flush();
      result.push(line);
    }
  }

  flush();
  return result.join('\n');
}

// ---------------------------------------------------------------------------
// Normalize non-standard Markdown from AI output before parsing.
//
// Keep this intentionally conservative around headings. A heading is also a
// valid piece of prose (for example, "《第2章》评审"), so arbitrary length or
// number heuristics must never rewrite text inside it.
// ---------------------------------------------------------------------------

export function normalizeMarkdown(src: string): string {
  let result = convertSpaceAlignedTables(src);

  // Split heading markers concatenated with preceding Chinese text:
  // "报告。#分镜审查" -> "报告。\n# 分镜审查"
  result = result.replace(/([\p{Script=Han}。！？），：；】》])(#{1,6})\s?(?=[^\s#\n])/gu, '$1\n$2 ');

  // Ensure a space after heading markers at line start: "##text" -> "## text".
  result = result.replace(/^(#{1,6})([^\s#])/gm, '$1 $2');

  // Split a paragraph appended to a heading only when a known prose opener
  // follows a heading label, e.g. "# 分镜审查报告我已通读".
  result = result.replace(
    /^(#{1,6}\s+)(.+?)((?:报告|审查|分析|总结|建议|结论|评分|结果|缺失|问题|优点|重点|牺牲|弊端))\s*(\*{0,2})\s*(我|当前|本文|本段|本篇|这里|以下|首先|现在|最后|而这|这是|然而|但前|对此|在此)(.+)$/gmu,
    '$1$2$3\n\n$4$5$6',
  );

  // A bold label immediately after a heading is an unambiguous paragraph
  // boundary: "## 📊总评分：6.5 /10**节奏曲线**" -> heading + bold paragraph.
  // Requiring the ** marker is important: a heading such as
  // "# 《第2章》评审（对照第1章定稿 v8）" must remain one line.
  result = result.replace(
    /^(#{1,6}\s+)(.+?[\d。！？）】》])\s*(\*{2})\s*([\p{Script=Han}])(.+)$/gmu,
    '$1$2\n$3$4$5',
  );

  // Split a heading followed by a GFM table on the same line:
  // "## heading|col|" -> "## heading\n|col|".
  result = result.replace(/^(#{1,6}\s+.+?)(\|[^|\n]+\|[^|\n]+\|)/gm, '$1\n$2');

  // Insert a newline before lists that were appended to Chinese prose.
  result = result.replace(/([\p{Script=Han}。！？）])(\d+)\. /gu, '$1\n$2. ');
  result = result.replace(/([\p{Script=Han}。！？）])(- [^-])/gu, '$1\n$2');

  // Ensure horizontal rules and code fences occupy their own lines.
  result = result.replace(/([^\n])---$/gm, '$1\n---');
  result = result.replace(/^---([^\n])/gm, '---\n$1');
  result = result.replace(/^(.+[^\n])```\s*$/gm, '$1\n```');
  result = result.replace(/^```(\S)/gm, '```\n$1');

  return result;
}
