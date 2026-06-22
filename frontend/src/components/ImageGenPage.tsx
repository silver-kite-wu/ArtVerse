import { useEffect, useMemo, useRef, useState } from 'react';
import { Copy, Download, ImagePlus, Loader2, Send, Trash2, X } from 'lucide-react';
import { generateImage, listImageGenHistory, deleteImageGenRecord, imageGenUrl, type ImageGenRecord } from '../api';

interface Message {
  id: string;
  type: 'user' | 'ai';
  prompt?: string;
  refThumbnails?: string[];
  record?: ImageGenRecord;
}

export default function ImageGenPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [prompt, setPrompt] = useState('');
  const [refFiles, setRefFiles] = useState<{ file: File; preview: string }[]>([]);
  const [generating, setGenerating] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    (async () => {
      try {
        const r = await listImageGenHistory(0, 50);
        const msgs: Message[] = [];
        for (const record of [...r.content].reverse()) {
          msgs.push({ id: 'u-' + record.id, type: 'user', prompt: record.prompt });
          msgs.push({ id: 'a-' + record.id, type: 'ai', record });
        }
        setMessages(msgs);
      } catch {
        // Empty history is fine.
      }
      setLoading(false);
    })();
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, generating]);

  const hasMessages = messages.length > 0;
  const canSend = useMemo(
    () => !generating && (prompt.trim().length > 0 || refFiles.length > 0),
    [generating, prompt, refFiles.length],
  );

  const handleAddRef = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    const remaining = 3 - refFiles.length;
    const toAdd = Math.min(files.length, remaining);
    const newRefs: { file: File; preview: string }[] = [];
    for (let i = 0; i < toAdd; i++) {
      const f = files[i];
      if (f.size > 10 * 1024 * 1024) {
        alert(f.name + ': max 10MB');
        continue;
      }
      newRefs.push({ file: f, preview: URL.createObjectURL(f) });
    }
    setRefFiles((prev) => [...prev, ...newRefs].slice(0, 3));
    e.target.value = '';
  };

  const removeRef = (idx: number) => {
    setRefFiles((prev) => {
      URL.revokeObjectURL(prev[idx].preview);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const handleSend = async () => {
    if (!prompt.trim() && refFiles.length === 0) return;

    const userMsg: Message = {
      id: 'u-temp-' + Date.now(),
      type: 'user',
      prompt: prompt.trim() || '(image only)',
      refThumbnails: refFiles.map((f) => f.preview),
    };
    setMessages((prev) => [...prev, userMsg]);

    const promptText = prompt.trim();
    const filesToSend = refFiles;
    setPrompt('');
    setRefFiles([]);
    setGenerating(true);

    try {
      const refBase64: string[] = [];
      for (const rf of filesToSend) {
        const b64 = await new Promise<string>((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve((reader.result as string).split(',')[1]);
          reader.readAsDataURL(rf.file);
        });
        refBase64.push(b64);
      }

      const record = await generateImage(promptText, refBase64.length > 0 ? refBase64 : undefined);
      setMessages((prev) => [...prev, { id: 'a-' + record.id, type: 'ai', record }]);
    } catch (e: any) {
      setMessages((prev) => [
        ...prev,
        { id: 'err-' + Date.now(), type: 'ai', prompt: '生成失败: ' + (e.message || '未知错误') },
      ]);
    } finally {
      setGenerating(false);
    }
  };

  const handleDelete = async (id: number, msgId: string) => {
    try {
      await deleteImageGenRecord(id);
      setMessages((prev) => prev.filter((m) => m.id !== 'u-' + id && m.id !== 'a-' + id && m.id !== msgId));
    } catch (e: any) {
      alert('删除失败: ' + (e.message || '未知错误'));
    }
  };

  const Composer = ({ compact = false }: { compact?: boolean }) => (
    <div className={compact ? 'w-full' : 'w-full max-w-5xl mx-auto'}>
      <div className="overflow-hidden rounded-2xl border border-ink-border bg-ink-light/85 shadow-2xl shadow-coral/5">
        <div className="p-4 sm:p-5">
          {refFiles.length > 0 && (
            <div className="mb-3 flex flex-wrap gap-2">
              {refFiles.map((rf, i) => (
                <div key={i} className="relative h-14 w-14 shrink-0 overflow-hidden rounded-xl border border-ink-border bg-ink-lighter">
                  <img src={rf.preview} alt={'ref ' + (i + 1)} className="h-full w-full object-cover" />
                  <button
                    onClick={() => removeRef(i)}
                    className="absolute right-0 top-0 rounded-bl-md bg-black/70 p-0.5 text-cream"
                  >
                    <X size={10} />
                  </button>
                </div>
              ))}
            </div>
          )}

          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder="描述你想要生成的内容"
            disabled={generating}
            rows={compact ? 4 : 5}
            className="w-full resize-none bg-transparent text-[17px] leading-7 text-cream outline-none placeholder:text-cream-dim"
          />
        </div>

        <div className="flex items-center gap-2 border-t border-ink-border px-3 py-3 sm:px-4">
          <label className={'flex cursor-pointer items-center gap-2 rounded-xl border border-ink-border px-3 py-2 text-cream-dim transition-colors hover:bg-ink-lighter ' + (refFiles.length >= 3 ? 'pointer-events-none opacity-40' : '')}>
            <ImagePlus size={16} />
            <span className="text-sm">图片</span>
            <input type="file" accept="image/*" multiple onChange={handleAddRef} className="hidden" />
          </label>

          <button
            type="button"
            onClick={handleSend}
            disabled={!canSend}
            className="ml-auto inline-flex h-10 w-10 items-center justify-center rounded-xl bg-coral text-cream transition-colors hover:bg-coral-light disabled:cursor-not-allowed disabled:opacity-30"
          >
            {generating ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
          </button>
        </div>
      </div>
    </div>
  );

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center bg-ink">
        <Loader2 size={28} className="animate-spin text-coral" />
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 bg-ink text-cream flex flex-col">
      {!hasMessages ? (
        <div className="flex-1 flex items-center justify-center px-4">
          <div className="w-full max-w-5xl">
            <div className="mb-10 text-center">
              <h2 className="text-4xl font-semibold tracking-tight text-cream sm:text-5xl">即刻创作 图片</h2>
            </div>
            <Composer />
          </div>
        </div>
      ) : (
        <div className="flex-1 min-h-0 flex flex-col">
          <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 lg:px-10">
            <div className="mx-auto w-full max-w-6xl space-y-8">
              {messages.map((msg) => {
                if (msg.type === 'user') {
                  return (
                    <div key={msg.id} className="flex justify-end">
                      <div className="max-w-[78%]">
                        <div className="inline-flex items-center gap-2 rounded-2xl border border-ink-border bg-ink-lighter px-4 py-3 text-sm text-cream shadow-sm">
                          {msg.refThumbnails && msg.refThumbnails.length > 0 && (
                            <div className="flex gap-1">
                              {msg.refThumbnails.map((src, i) => (
                                <img key={i} src={src} alt={'ref ' + (i + 1)} className="h-10 w-10 rounded-lg object-cover" />
                              ))}
                            </div>
                          )}
                          <span className="whitespace-pre-wrap break-words">{msg.prompt}</span>
                        </div>
                      </div>
                    </div>
                  );
                }

                const record = msg.record;
                if (record) {
                  const imageUrl = imageGenUrl(record.image_url);
                  return (
                    <div key={msg.id} className="space-y-3">
                      <div className="flex items-center justify-between text-xs text-cream-dim">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-cream">gpt-image-2</span>
                          <span>1 张图片</span>
                        </div>
                        <span>{new Date(record.created_at).toLocaleString()}</span>
                      </div>

                      <div className="flex justify-start">
                        <div className="inline-flex max-w-full items-center justify-center overflow-hidden rounded-2xl border border-ink-border bg-ink-light shadow-sm">
                          <img
                            src={imageUrl}
                            alt={record.prompt}
                            className="block h-auto max-h-[75vh] max-w-full object-contain"
                            loading="lazy"
                          />
                        </div>
                      </div>

                      <div className="flex items-center gap-2 text-cream-dim">
                        <button className="rounded-lg p-2 hover:bg-ink-lighter" title="复制">
                          <Copy size={14} />
                        </button>
                        <a href={imageUrl} download className="rounded-lg p-2 hover:bg-ink-lighter" title="下载">
                          <Download size={14} />
                        </a>
                        <button onClick={() => handleDelete(record.id, msg.id)} className="rounded-lg p-2 hover:bg-ink-lighter" title="删除">
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  );
                }

                return (
                  <div key={msg.id} className="rounded-2xl border border-red-800/40 bg-red-900/20 px-4 py-3 text-sm text-red-300">
                    {msg.prompt}
                  </div>
                );
              })}

              {generating && (
                <div className="flex justify-start">
                  <div className="inline-flex items-center gap-2 rounded-2xl border border-ink-border bg-ink-light px-4 py-3 text-sm text-cream-dim shadow-sm">
                    <Loader2 size={16} className="animate-spin text-coral" />
                    生成中...
                  </div>
                </div>
              )}
            </div>
          </div>

          <div className="border-t border-ink-border glass px-4 py-5 sm:px-6 lg:px-10">
            <Composer compact />
          </div>
        </div>
      )}
    </div>
  );
}
