import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { CalendarClock, Check, ChevronDown, ImagePlus, Loader2, X } from 'lucide-react';
import { listStories, listChapters, type Story, type Chapter } from '../../api/authoring';
import { getUserProviderConfigs, parseProviderModels } from '../../api/providerSettings';
import { saveAutomation, previewAutomation, uploadAutomationReference, type AutomationPlan, type AutomationSeed, type AutomationExecutorKey, type AutomationSchedule, type AutomationDefinition } from '../../api/automations';
import OverlayPortal from '../../ui/OverlayPortal';
import { useToast } from '../../ui/toast/useToast';
import { randomUUID } from '../../utils/browserSecurity';

interface Props { plan?: AutomationPlan; seed?: AutomationSeed; onClose: () => void; onSaved: (plan: AutomationPlan) => void }
const field = 'w-full rounded-xl border border-border bg-bg-base px-3 py-2.5 text-sm text-text-primary outline-none focus:border-accent focus:ring-2 focus:ring-accent/15';
const label = 'grid gap-2 text-sm font-medium text-text-primary';
const names: Record<AutomationExecutorKey, string> = { MANGA_AGENT: '漫画创作助手', STORY_CHAT: '小说创作助手', MANGA_IMAGE: '章节缺失图片', IMAGE_GEN: '独立图片生成' };
interface SelectOption { value: string; label: string }
function Select({ value, options, onValue, placeholder = '请选择', className = '' }: {
  value: string; options: SelectOption[]; onValue: (value: string) => void; placeholder?: string; className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [dropUp, setDropUp] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const selected = options.find(option => option.value === value);
  useEffect(() => {
    if (!open) return;
    const outside = (event: MouseEvent) => { if (!wrapRef.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', outside); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  const toggle = () => {
    if (!open) {
      const rect = wrapRef.current?.getBoundingClientRect();
      const bounds = wrapRef.current?.closest('dialog')?.getBoundingClientRect();
      const below = (bounds?.bottom ?? window.innerHeight) - (rect?.bottom ?? 0);
      setDropUp(below < 260 && (rect?.top ?? 0) > below);
    }
    setOpen(!open);
  };
  return <div ref={wrapRef} className={`relative ${className}`}>
    <button type="button" onClick={toggle} aria-haspopup="listbox" aria-expanded={open} className={`${field} flex cursor-pointer items-center justify-between gap-2 text-left transition-colors hover:border-accent/40 ${open ? 'border-accent ring-2 ring-accent/15' : ''}`}>
      <span className={`truncate ${selected ? '' : 'text-text-muted'}`}>{selected?.label ?? placeholder}</span>
      <ChevronDown size={16} className={`shrink-0 text-text-muted transition-transform ${open ? 'rotate-180' : ''}`} />
    </button>
    {open && <div role="listbox" className={`absolute inset-x-0 z-30 max-h-64 overflow-y-auto rounded-2xl border border-border bg-bg-surface p-1.5 shadow-xl ${dropUp ? 'bottom-full mb-1.5' : 'top-full mt-1.5'}`}>
      {options.map(option => <button type="button" key={option.value} role="option" aria-selected={option.value === value} onClick={() => { onValue(option.value); setOpen(false); }} className={`flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left text-sm transition-colors ${option.value === value ? 'bg-accent/10 font-medium text-accent' : 'text-text-primary hover:bg-bg-base'}`}>
        <span className="min-w-0 flex-1 truncate">{option.label}</span>
        {option.value === value && <Check size={15} className="shrink-0" />}
      </button>)}
    </div>}
  </div>;
}
function defaultDate() { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(Date.now() + 3600_000)).replace(' ', 'T'); }

export default function AutomationComposer({ plan, seed = {}, onClose, onSaved }: Props) {
  const toast = useToast();
  const initial = plan?.definition;
  const dialog = useRef<HTMLDialogElement>(null);
  const nameInput = useRef<HTMLInputElement>(null);
  const key = useRef(randomUUID());
  const uploaded = useRef(new Map<File, string>());
  const [executorKey, setExecutorKey] = useState<AutomationExecutorKey>(initial?.executorKey ?? seed.executorKey ?? 'MANGA_AGENT');
  const [name, setName] = useState(initial?.name ?? '');
  const [instruction, setInstruction] = useState(initial?.instruction ?? seed.instruction ?? '');
  const [storyId, setStoryId] = useState(initial?.payload.storyId ?? seed.storyId ?? 0);
  const [chapterIds, setChapterIds] = useState<number[]>(initial?.payload.chapterIds ?? (initial?.payload.chapterId ? [initial.payload.chapterId] : seed.chapterId ? [seed.chapterId] : []));
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [models, setModels] = useState<{ value: string; label: string; configId: number; model: string; kind: string }[]>([]);
  const [modelChoice, setModelChoice] = useState(`${initial?.payload.modelConfigId ?? seed.modelConfigId ?? ''}:${initial?.payload.model ?? seed.model ?? ''}`);
  const [repeat, setRepeat] = useState<AutomationSchedule['kind']>(initial?.schedule.kind ?? 'ONCE');
  const [dateTime, setDateTime] = useState(initial?.schedule.localDateTime ?? defaultDate());
  const [time, setTime] = useState(initial?.schedule.localTime ?? '09:00');
  const [timezone, setTimezone] = useState(initial?.schedule.timezone ?? 'Asia/Shanghai');
  const [weekdays, setWeekdays] = useState(initial?.schedule.weekdays ?? [1, 2, 3, 4, 5]);
  const [size, setSize] = useState(initial?.payload.size ?? seed.size ?? '1024x1024');
  const [inputPolicy, setInputPolicy] = useState(initial?.payload.inputPolicy ?? '');
  const [files, setFiles] = useState<File[]>(seed.referenceFiles ?? []);
  const [existingRefs, setExistingRefs] = useState(initial?.payload.referenceImageKeys ?? []);
  const [imageLimit, setImageLimit] = useState(initial?.limits.maxImagesPerRun ?? 20);
  const [modelLimit, setModelLimit] = useState(initial?.limits.maxModelCallsPerRun ?? 10);
  const [duration, setDuration] = useState((initial?.limits.maxDurationSeconds ?? 1800) / 60);
  const [misfire, setMisfire] = useState<'RUN_LATEST' | 'SKIP'>(initial?.misfirePolicy ?? 'RUN_LATEST');
  const [busy, setBusy] = useState(false);
  const [occurrences, setOccurrences] = useState<string[]>([]);
  const isImage = executorKey === 'IMAGE_GEN' || executorKey === 'MANGA_IMAGE';
  const options = models.filter(m => m.kind === (isImage ? 'image' : 'llm'));
  const selected = options.find(m => m.value === modelChoice) ?? options[0];
  const schedule = useMemo<AutomationSchedule>(() => ({ kind: repeat, timezone, ...(repeat === 'ONCE' ? { localDateTime: dateTime } : { localTime: time, weekdays: repeat === 'WEEKLY' ? weekdays : [] }) }), [repeat, timezone, dateTime, time, weekdays]);

  useEffect(() => {
    const node = dialog.current;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    node?.showModal(); nameInput.current?.focus();
    return () => { node?.close(); if (trigger?.isConnected) trigger.focus(); };
  }, []);
  useEffect(() => {
    let live = true;
    void Promise.all([listStories(), getUserProviderConfigs()]).then(([allStories, configs]) => {
      if (!live) return;
      setStories(allStories);
      setModels(Object.entries(configs).flatMap(([kind, entries]) => (entries ?? []).filter(e => e.configId).flatMap(e => parseProviderModels(e.model).map(model => ({ value: `${e.configId}:${model}`, label: `${e.label || e.presetId} · ${model}`, configId: e.configId!, model, kind })))));
    }).catch(e => { if (live) toast.error(String(e.message)); });
    return () => { live = false; };
  }, [toast]);
  useEffect(() => {
    if (!storyId) return;
    let live = true;
    void listChapters(storyId).then(items => { if (live) setChapters(items); }).catch(e => { if (live) toast.error(e.message); });
    return () => { live = false; };
  }, [storyId, toast]);
  useEffect(() => {
    let live = true;
    const timer = window.setTimeout(() => {
      void previewAutomation(schedule).then(result => { if (live) setOccurrences(result.occurrences.map(o => o.localTime)); }).catch(() => { if (live) setOccurrences([]); });
    }, 300);
    return () => { live = false; clearTimeout(timer); };
  }, [schedule]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!selected) { toast.warning('请先在 API 设置中保存相应的模型配置。'); return; }
    if (executorKey !== 'IMAGE_GEN' && (!storyId || chapterIds.length === 0)) { toast.warning('请选择作品和章节。'); return; }
    setBusy(true);
    try {
      const refs = [...existingRefs];
      for (const file of files) { let objectKey = uploaded.current.get(file); if (!objectKey) { objectKey = await uploadAutomationReference(file); uploaded.current.set(file, objectKey); } refs.push(objectKey); }
      const definition: AutomationDefinition = {
        name: name.trim(), taskType: isImage ? 'IMAGE' : 'AGENT', executorKey, instruction: instruction.trim(), schedule,
        payload: { version: 1, modelConfigId: selected.configId, model: selected.model,
          ...(executorKey === 'IMAGE_GEN' ? { size, referenceImageKeys: refs, ...(initial?.payload.conversationId ?? seed.conversationId ? { conversationId: initial?.payload.conversationId ?? seed.conversationId } : {}) } : { storyId, ...(executorKey === 'MANGA_IMAGE' ? { chapterIds, inputPolicy: (inputPolicy || (repeat === 'ONCE' ? 'FIXED_SNAPSHOT' : 'RESOLVE_AT_RUN')) as 'FIXED_SNAPSHOT' | 'RESOLVE_AT_RUN' } : { chapterId: chapterIds[0] }) }),
        }, limits: { maxImagesPerRun: imageLimit, maxModelCallsPerRun: modelLimit, maxDurationSeconds: duration * 60 },
        misfirePolicy: misfire, overlapPolicy: 'SKIP', expectedVersion: plan?.version, origin: initial?.origin ?? seed.origin ?? 'TASK_CENTER',
      };
      const saved = await saveAutomation(definition, plan?.id, key.current);
      window.dispatchEvent(new Event('artverse:automations-changed')); onSaved(saved);
    } catch (e) { toast.error(e instanceof Error ? e.message : '保存失败，请稍后重试'); }
    finally { setBusy(false); }
  }

  return <OverlayPortal><dialog ref={dialog} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }} aria-labelledby="automation-composer-title" className="m-auto flex max-h-[92dvh] w-[min(44rem,calc(100%-2rem))] flex-col overflow-hidden rounded-3xl border border-border bg-bg-surface p-0 text-text-primary shadow-2xl backdrop:bg-black/35 backdrop:backdrop-blur-sm">
    <div className="flex shrink-0 items-center justify-between border-b border-border px-6 py-4 sm:px-8"><h2 id="automation-composer-title" className="flex items-center gap-2 text-lg font-semibold"><CalendarClock size={21} className="text-accent" />{plan ? '编辑定时任务' : '设置定时任务'}</h2><button type="button" onClick={onClose} disabled={busy} aria-label="关闭设置" className="rounded-lg p-2 hover:bg-bg-base"><X size={19} /></button></div>
    <form onSubmit={event => { void submit(event); }} className="grid min-h-0 flex-1 gap-6 overflow-y-auto p-6 [scrollbar-width:none]! [&::-webkit-scrollbar]:hidden! sm:p-8">
      <div className="grid grid-cols-2 gap-2 rounded-2xl bg-bg-base p-1.5">{(['AGENT', 'IMAGE'] as const).map(type => <button type="button" key={type} disabled={busy} onClick={() => { setExecutorKey(type === 'AGENT' ? 'MANGA_AGENT' : 'IMAGE_GEN'); setModelChoice(''); }} aria-pressed={type === (isImage ? 'IMAGE' : 'AGENT')} className={`rounded-xl px-3 py-2.5 text-sm font-medium ${type === (isImage ? 'IMAGE' : 'AGENT') ? 'bg-bg-surface text-accent shadow-sm' : 'text-text-secondary'}`}>{type === 'AGENT' ? 'Agent 任务' : '生图任务'}</button>)}</div>
      <label className={label}>任务名称<input ref={nameInput} value={name} onChange={e => setName(e.target.value)} required maxLength={120} placeholder="例如：每周检查分镜一致性" className={field} /></label>
      <label className={label}>执行内容<Select value={executorKey} options={Object.entries(names).filter(([k]) => isImage ? k.endsWith('IMAGE') || k === 'IMAGE_GEN' : k === 'MANGA_AGENT' || k === 'STORY_CHAT').map(([value, label]) => ({ value, label }))} onValue={value => { setExecutorKey(value as AutomationExecutorKey); setChapterIds([]); }} /></label>
      <label className={label}>{executorKey === 'IMAGE_GEN' ? '图片提示词' : '任务说明'}<textarea value={instruction} onChange={e => setInstruction(e.target.value)} rows={4} maxLength={20000} required={executorKey !== 'MANGA_IMAGE'} placeholder={isImage ? '描述要生成的画面……' : '说明要完成的创作、检查或整理任务……'} className={`${field} resize-y`} /></label>
      {executorKey !== 'IMAGE_GEN' && <div className="grid gap-4 rounded-2xl border border-border p-4"><label className={label}>作品<Select value={String(storyId)} options={[{ value: '0', label: '选择作品' }, ...stories.map(story => ({ value: String(story.id), label: story.title }))]} onValue={value => { setStoryId(Number(value)); setChapterIds([]); setChapters([]); }} /></label>
        <fieldset><legend className="mb-2 text-sm font-medium">{executorKey === 'MANGA_IMAGE' ? '章节（最多 3 章）' : '章节'}</legend><div className="flex max-h-32 flex-wrap gap-2 overflow-y-auto">{chapters.map(chapter => <label key={chapter.id} className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm ${chapterIds.includes(chapter.id) ? 'border-accent bg-accent/5 text-accent' : 'border-border text-text-secondary'}`}><input type={executorKey === 'MANGA_IMAGE' ? 'checkbox' : 'radio'} name="automation-chapter" value={chapter.id} checked={chapterIds.includes(chapter.id)} onChange={e => setChapterIds(executorKey === 'MANGA_IMAGE' ? e.target.checked ? [...chapterIds, chapter.id].slice(0, 3) : chapterIds.filter(id => id !== chapter.id) : [chapter.id])} />第 {chapter.chapter_number} 章</label>)}{storyId > 0 && chapters.length === 0 && <span className="text-xs text-text-muted">此作品暂无可选章节</span>}</div></fieldset></div>}
      {executorKey === 'IMAGE_GEN' && <div className="flex flex-wrap items-center gap-3 text-sm"><label className="flex cursor-pointer items-center gap-2 rounded-xl border border-border px-3 py-2"><ImagePlus size={16} />添加参考图<input type="file" accept="image/png,image/jpeg,image/webp" multiple className="sr-only" onChange={e => { setFiles([...files, ...Array.from(e.target.files ?? [])].slice(0, Math.max(0, 3 - existingRefs.length))); e.target.value = ''; }} /></label>{files.map((file, i) => <button type="button" key={`${file.name}-${i}`} className="rounded-lg bg-bg-base px-2 py-1 text-xs" onClick={() => setFiles(files.filter((_, index) => index !== i))}>{file.name} ×</button>)}{existingRefs.map((_, i) => <button type="button" key={i} onClick={() => setExistingRefs(existingRefs.filter((_, index) => index !== i))} className="text-xs text-text-secondary">已保存参考图 {i + 1} ×</button>)}</div>}
      <div className="grid gap-4 rounded-2xl border border-border p-4"><label className={`${label} sm:grid-cols-[5rem_1fr] sm:items-center`}>重复<Select value={repeat} options={[{ value: 'ONCE', label: '不重复' }, { value: 'DAILY', label: '每天' }, { value: 'WEEKLY', label: '每周' }]} onValue={value => setRepeat(value as AutomationSchedule['kind'])} /></label>
        <label className={`${label} sm:grid-cols-[5rem_1fr] sm:items-center`}>{repeat === 'ONCE' ? '日期时间' : '时间'}<input type={repeat === 'ONCE' ? 'datetime-local' : 'time'} required value={repeat === 'ONCE' ? dateTime : time} onChange={e => repeat === 'ONCE' ? setDateTime(e.target.value) : setTime(e.target.value)} className={field} /></label>
        {repeat === 'WEEKLY' && <fieldset><legend className="mb-2 text-sm">每周执行日</legend><div className="flex flex-wrap gap-2">{['一', '二', '三', '四', '五', '六', '日'].map((day, i) => <label key={day} className="flex items-center gap-1 text-sm"><input type="checkbox" checked={weekdays.includes(i + 1)} onChange={e => setWeekdays(e.target.checked ? [...weekdays, i + 1] : weekdays.filter(d => d !== i + 1))} />{day}</label>)}</div><button type="button" onClick={() => setWeekdays([1, 2, 3, 4, 5])} className="mt-2 text-xs text-accent">选择周一至周五（不含节假日调整）</button></fieldset>}
        <label className={`${label} sm:grid-cols-[5rem_1fr] sm:items-center`}>时区<input value={timezone} onChange={e => setTimezone(e.target.value)} list="automation-timezones" className={field} required /><datalist id="automation-timezones">{['Asia/Shanghai', 'Asia/Tokyo', 'America/New_York', 'America/Los_Angeles', 'Europe/London', 'UTC'].map(zone => <option key={zone} value={zone} />)}</datalist></label>
      </div>
      <div className="text-xs leading-6 text-text-muted"><span className="font-medium text-text-secondary">接下来执行</span>{occurrences.length ? <ol>{occurrences.map(value => <li key={value}>{value.replace('T', ' ').replace(/\[.*\]/, '')}</li>)}</ol> : <p>请填写有效的未来日期、时间和时区</p>}<p className="mt-1">到点开始执行，完成时间受排队及模型响应影响。</p></div>
      <details className="group"><summary className="flex cursor-pointer list-none items-center gap-1 text-sm text-text-secondary">高级设置<ChevronDown size={15} className="group-open:rotate-180" /></summary><div className="mt-4 grid gap-4">
        <label className={label}>模型配置<Select value={selected?.value ?? ''} options={options.length === 0 ? [{ value: '', label: '请先在 API 设置中保存模型配置' }] : options.map(model => ({ value: model.value, label: model.label }))} onValue={setModelChoice} /></label>
        {executorKey === 'MANGA_IMAGE' && <label className={label}>图片输入<Select value={inputPolicy || (repeat === 'ONCE' ? 'FIXED_SNAPSHOT' : 'RESOLVE_AT_RUN')} options={[{ value: 'FIXED_SNAPSHOT', label: '固定当前分镜与素材，变化时提醒我' }, { value: 'RESOLVE_AT_RUN', label: '每次获取所选章节当时缺失的图片' }]} onValue={setInputPolicy} /></label>}
        {executorKey === 'IMAGE_GEN' && <label className={label}>图片尺寸<input className={field} value={size} onChange={e => setSize(e.target.value)} placeholder="1024x1024" /></label>}
        <div className="grid gap-3 sm:grid-cols-2"><label className={label}>{isImage ? '每次最多生成图片' : '每次最多模型调用'}<input className={field} type="number" min={1} max={isImage ? 180 : 50} value={isImage ? imageLimit : modelLimit} onChange={e => isImage ? setImageLimit(Number(e.target.value)) : setModelLimit(Number(e.target.value))} /></label><label className={label}>最长运行（分钟）<input className={field} type="number" min={1} max={120} value={duration} onChange={e => setDuration(Number(e.target.value))} /></label></div>
        <label className={label}>错过执行时<Select value={misfire} options={[{ value: 'RUN_LATEST', label: '恢复后补最近一次（重复任务限 24 小时内）' }, { value: 'SKIP', label: '重复任务跳过错过的时间' }]} onValue={value => setMisfire(value as 'RUN_LATEST' | 'SKIP')} /></label>
        <p className="text-xs leading-5 text-text-muted">上次仍在执行或等待确认时，自动跳过新周期。覆盖正式内容仍需确认。结果与待处理事项会显示在任务中心。</p>
      </div></details>
      <div className="flex justify-end gap-3 border-t border-border pt-5"><button type="button" disabled={busy} onClick={onClose} className="rounded-full bg-bg-base px-5 py-2.5 text-sm">取消</button><button type="submit" disabled={busy || !selected || !name.trim()} className="flex items-center gap-2 rounded-full bg-accent px-6 py-2.5 text-sm font-semibold text-white disabled:opacity-40">{busy && <Loader2 size={16} className="animate-spin" />}{busy ? '保存中' : plan ? '保存设置' : '创建任务'}</button></div>
    </form>
  </dialog></OverlayPortal>;
}
