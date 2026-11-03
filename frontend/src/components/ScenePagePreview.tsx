interface ParsedPanel {
  number: string;
  shot?: string;
  description: string;
  dialogues: string[];
  sounds: string[];
}

function parseScenePage(scene: string): { title: string; panels: ParsedPanel[] } {
  const title = scene.match(/第\s*\d+\s*页/)?.[0] || '漫画页';
  const panels: ParsedPanel[] = [];
  const panelPattern = /【第\s*(\d+)\s*格(?:（([^）]+)）)?】([\s\S]*?)(?=【第\s*\d+\s*格|$)/g;
  let match: RegExpExecArray | null;

  while ((match = panelPattern.exec(scene)) !== null) {
    const raw = match[3].trim();
    const dialogues = Array.from(raw.matchAll(/对话气泡[：:]\s*「([^」]+)」/g)).map((m) => m[1].trim());
    const sounds = Array.from(raw.matchAll(/音效(?:字)?[：:]\s*([^。；\n]+)/g)).map((m) => m[1].trim());
    const description = raw
      .replace(/对话气泡[：:]\s*「[^」]+」/g, '')
      .replace(/音效(?:字)?[：:]\s*[^。；\n]+[。；]?/g, '')
      .replace(/\s+/g, ' ')
      .trim();

    panels.push({
      number: match[1],
      shot: match[2]?.trim(),
      description,
      dialogues,
      sounds,
    });
  }

  return { title, panels };
}

export function ScenePagePreview({ scene, expanded = false }: { scene: string; expanded?: boolean }) {
  const parsed = parseScenePage(scene);
  if (parsed.panels.length === 0) {
    return (
      <p className={`text-xs text-text-secondary leading-relaxed ${expanded ? '' : 'line-clamp-2'}`}>
        {scene}
      </p>
    );
  }

  const visiblePanels = expanded ? parsed.panels : parsed.panels.slice(0, 2);
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[0.6875rem] font-semibold text-accent-secondary-light">{parsed.title}</span>
        <span className="text-[0.625rem] text-text-secondary">{parsed.panels.length} 格</span>
      </div>
      <div className="space-y-2">
        {visiblePanels.map((panel) => (
          <div key={panel.number} className="rounded-md border border-border bg-bg-base/80 p-2">
            <div className="mb-1 flex items-center gap-1.5">
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-lg  bg-bg-raised text-[0.625rem] font-mono text-text-secondary">
                {panel.number}
              </span>
              {panel.shot && (
                <span className="rounded border border-accent/20 bg-accent/10 px-1.5 py-0.5 text-[0.625rem] text-accent-hover">
                  {panel.shot}
                </span>
              )}
            </div>
            {panel.description && (
              <p className="text-xs leading-relaxed text-text-secondary">{panel.description}</p>
            )}
            {(panel.dialogues.length > 0 || panel.sounds.length > 0) && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {panel.dialogues.map((line, idx) => (
                  <span key={`d-${idx}`} className="rounded-full border border-accent-tertiary/30 bg-accent-tertiary/10/50 px-2 py-0.5 text-[0.625rem] text-accent-tertiary">
                    {line}
                  </span>
                ))}
                {panel.sounds.map((sound, idx) => (
                  <span key={`s-${idx}`} className="rounded-full border border-amber-accent/20 bg-accent-secondary/10 px-2 py-0.5 text-[0.625rem] text-accent-secondary-light">
                    音效：{sound}
                  </span>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
      {!expanded && parsed.panels.length > visiblePanels.length && (
        <div className="text-[0.625rem] text-text-muted">还有 {parsed.panels.length - visiblePanels.length} 格，展开查看完整分镜</div>
      )}
    </div>
  );
}

