import { Marked, Renderer, type Tokens } from 'marked';
import { useMemo } from 'react';
import { normalizeMarkdown } from './markdownNormalization';

// ---------------------------------------------------------------------------
// Custom Renderer — injects Tailwind classes matching the project palette.
//
// Design note: we override EVERY method needed so the renderer object passed
// to the Marked constructor is a complete, self-contained renderer.  We do
// NOT call sibling renderer methods manually (e.g. list → listitem) because
// that can bypass marked's internal token-walk and trigger assertion errors.
// Instead we let the Marked engine walk the token tree and call the right
// method for every token.
// ---------------------------------------------------------------------------

class TailwindRenderer extends Renderer {
  heading({ tokens, depth }: Tokens.Heading): string {
    const text = this.parser.parseInline(tokens);
    if (depth === 2) return `<h2 class="text-lg font-semibold text-text-primary mt-6 mb-3 pb-2 border-b border-border">${text}</h2>`;
    if (depth === 3) return `<h3 class="text-base font-semibold text-text-primary mt-5 mb-2">${text}</h3>`;
    if (depth === 4) return `<h4 class="text-sm font-semibold text-text-secondary mt-4 mb-1">${text}</h4>`;
    return `<h${depth} class="text-sm font-semibold text-text-primary my-2">${text}</h${depth}>`;
  }

  paragraph({ tokens }: Tokens.Paragraph): string {
    const text = this.parser.parseInline(tokens);
    return `<p class="text-sm text-text-primary leading-relaxed my-2">${text}</p>`;
  }

  list({ items, ordered, start }: Tokens.List): string {
    const tag = ordered ? 'ol' : 'ul';
    const cls = ordered
      ? 'list-decimal pl-5 space-y-1 my-3 text-sm text-text-primary leading-relaxed'
      : 'list-disc pl-5 space-y-1 my-3 text-sm text-text-primary leading-relaxed';
    const startAttr = ordered && start && start !== 1 ? ` start="${start}"` : '';
    let body = '';
    for (const item of items) {
      body += this.listitem(item);
    }
    return `<${tag} class="${cls}"${startAttr}>${body}</${tag}>`;
  }

  listitem(item: Tokens.ListItem): string {
    const content = this.parser.parseInline(item.tokens);
    let checkbox = '';
    if (item.task) {
      checkbox = item.checked
        ? '<input type="checkbox" class="mr-2 accent-vermilion" checked disabled />'
        : '<input type="checkbox" class="mr-2 accent-vermilion" disabled />';
    }
    return `<li>${checkbox}${content}</li>`;
  }

  hr(): string {
    return '<hr class="border-border my-6" />';
  }

  blockquote({ tokens }: Tokens.Blockquote): string {
    const content = this.parser.parseInline(tokens);
    return `<blockquote class="border-l-4 border-accent-secondary/40 bg-accent-secondary/5 px-4 py-2 my-3 rounded-r-lg text-sm text-text-secondary italic">${content}</blockquote>`;
  }

  codespan({ text }: { text: string }): string {
    return `<code class="rounded bg-bg-surface px-1.5 py-0.5 text-xs text-accent font-mono">${text}</code>`;
  }

  code({ text }: { text: string }): string {
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return `<div class="relative group"><pre class="block bg-bg-surface rounded-lg p-4 overflow-x-auto my-3 border border-border"><code class="text-xs font-mono text-text-primary leading-relaxed">${escaped}</code></pre><button onclick="navigator.clipboard?.writeText(this.closest('.group').querySelector('code').textContent)" class="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity px-2 py-1 text-xs rounded-lg  bg-bg-raised border border-border text-text-secondary hover:text-text-primary cursor-pointer" aria-label="复制代码">复制</button></div>`;
  }

  link({ href, title, tokens }: Tokens.Link): string {
    const text = this.parser.parseInline(tokens);
    const safeHref = href ?? '';
    const titleAttr = title ? ` title="${title}"` : '';
    return `<a href="${safeHref}"${titleAttr} class="text-accent-tertiary underline hover:brightness-110 transition-colors" target="_blank" rel="noopener noreferrer">${text}</a>`;
  }

  image({ href, title, text }: { href: string; title?: string | null; text: string }): string {
    const safeHref = href ?? '';
    const titleAttr = title ? ` title="${title}"` : '';
    return `<img src="${safeHref}" alt="${text}"${titleAttr} class="max-w-full rounded-lg my-2" />`;
  }

  strong({ tokens }: Tokens.Strong): string {
    const text = this.parser.parseInline(tokens);
    return `<strong class="font-semibold text-text-primary">${text}</strong>`;
  }

  em({ tokens }: Tokens.Em): string {
    const text = this.parser.parseInline(tokens);
    return `<em class="italic">${text}</em>`;
  }

  del({ tokens }: Tokens.Del): string {
    const text = this.parser.parseInline(tokens);
    return `<del class="line-through text-text-secondary">${text}</del>`;
  }

  // Table — we override this to add wrapper div and header/body styling.
  // The engine supplies us with parsed Table token; we render header and
  // body by calling this.tablecell / this.tablerow the same way the
  // default renderer does.
  table({ header, rows, align }: Tokens.Table): string {
    // header row
    let headHtml = '<tr>';
    for (let i = 0; i < header.length; i++) {
      headHtml += this.tablecell({ ...header[i], header: true, align: align[i] });
    }
    headHtml += '</tr>';

    // body rows
    let bodyHtml = '';
    for (const row of rows) {
      let rowHtml = '';
      for (let i = 0; i < row.length; i++) {
        rowHtml += this.tablecell({ ...row[i], header: false, align: align[i] });
      }
      bodyHtml += `<tr>${rowHtml}</tr>`;
    }

    return `<div class="overflow-x-auto rounded-lg border border-border my-4"><table class="w-full border-collapse"><thead class="bg-bg-base text-white">${headHtml}</thead><tbody>${bodyHtml}</tbody></table></div>`;
  }

  tablecell({ tokens, header: isHeader, align }: Tokens.TableCell): string {
    const content = this.parser.parseInline(tokens);
    const alignCls = align ? ` text-${align}` : '';
    if (isHeader) {
      return `<th class="px-4 py-2 text-xs font-medium uppercase tracking-wider${alignCls}">${content}</th>`;
    }
    return `<td class="px-4 py-2 text-sm border-t border-border${alignCls}">${content}</td>`;
  }
}

// ---------------------------------------------------------------------------
// Marked instance — per-module singleton, NOT global marked
// ---------------------------------------------------------------------------

const renderer = new TailwindRenderer();

const md = new Marked({
  breaks: true,
  gfm: true,
  renderer,
});

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface MarkdownRendererProps {
  content: string;
}

export default function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const html = useMemo(() => {
    try {
      const normalized = normalizeMarkdown(content);
      let parsed = md.parse(normalized) as string;
      // Post-process: wrap bare <table> in overflow container with proper styling
      // (marked v16 may not call custom renderer for tables)
      parsed = parsed.replace(
        /<table>/g,
        '<div class="overflow-x-auto rounded-lg border border-border my-4"><table class="w-full border-collapse">'
      );
      parsed = parsed.replace(/<\/table>/g, '</table></div>');
      return parsed;
    } catch {
      // Fallback: if parsing fails, show raw text safely escaped
      return content.replace(/&/g, '&').replace(/</g, '<').replace(/>/g, '>');
    }
  }, [content]);

  return (
    <div
      className="markdown-body space-y-1"
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
