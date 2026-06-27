import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronUp, ChevronDown, Download, ImageIcon, Loader2, Sparkles, Pencil, RefreshCw, Check, X, Square, Users, ImagePlus, Trash2 } from 'lucide-react';
import {
  generateMangaStream,
  generateScenes,
  getScenes,
  updateScenes,
  regenerateImage,
  getChapterAssetGroup,
  setChapterAssetGroup,
  getColorMode,
  setColorMode,
  getImageCount,
  setImageCount,
  getMangaStyle,
  setMangaStyle,
  ALLOWED_IMAGE_COUNTS,
  MANGA_STYLE_LABELS,
  COLOR_MODE_LABELS,
  mangaImageUrl,
  
  mangaThumbUrl,
  type Chapter,
  type MangaProgress,
  type ColorMode,
  type MangaStyle,
  type AssetGroup,
  type AssetGroupCharacter,
  type CharRefImage,
  listCharRefImages,
  updateCharacterProfile,
  addCharRefImage,
  deleteCharRefImage,
} from '../api';
import { genStore } from '../genStore';

interface Props {
  chapter: Chapter | null;
  onChapterRefresh?: (chapterId: number) => void;
}

interface ImageItem {
  image_number: number;
  image_path: string;
  prompt: string;
}

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

function ScenePagePreview({ scene, expanded = false }: { scene: string; expanded?: boolean }) {
  const parsed = parseScenePage(scene);
  if (parsed.panels.length === 0) {
    return (
      <p className={`text-xs text-cream-dim leading-relaxed ${expanded ? '' : 'line-clamp-2'}`}>
        {scene}
      </p>
    );
  }

  const visiblePanels = expanded ? parsed.panels : parsed.panels.slice(0, 2);
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-amber-accent-light">{parsed.title}</span>
        <span className="text-[10px] text-cream-dim">{parsed.panels.length} 格</span>
      </div>
      <div className="space-y-2">
        {visiblePanels.map((panel) => (
          <div key={panel.number} className="rounded-md border border-ink-border bg-ink/80 p-2">
            <div className="mb-1 flex items-center gap-1.5">
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-ink-lighter text-[10px] font-mono text-cream-dim">
                {panel.number}
              </span>
              {panel.shot && (
                <span className="rounded border border-coral/20 bg-coral/10 px-1.5 py-0.5 text-[10px] text-coral-light">
                  {panel.shot}
                </span>
              )}
            </div>
            {panel.description && (
              <p className="text-xs leading-relaxed text-cream-dim">{panel.description}</p>
            )}
            {(panel.dialogues.length > 0 || panel.sounds.length > 0) && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {panel.dialogues.map((line, idx) => (
                  <span key={`d-${idx}`} className="rounded-full border border-aizuri/30 bg-aizuri-light/50 px-2 py-0.5 text-[10px] text-aizuri">
                    {line}
                  </span>
                ))}
                {panel.sounds.map((sound, idx) => (
                  <span key={`s-${idx}`} className="rounded-full border border-amber-accent/20 bg-amber-accent/10 px-2 py-0.5 text-[10px] text-amber-accent-light">
                    音效：{sound}
                  </span>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
      {!expanded && parsed.panels.length > visiblePanels.length && (
        <div className="text-[10px] text-warm-gray">还有 {parsed.panels.length - visiblePanels.length} 格，展开查看完整分镜</div>
      )}
    </div>
  );
}

type Phase = 'idle' | 'generating-scenes' | 'editing-scenes' | 'generating-images';
const DEFAULT_IMAGE_COUNT = 10;

export default function MangaPanel({ chapter, onChapterRefresh }: Props) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [progress, setProgress] = useState({ current: 0, total: DEFAULT_IMAGE_COUNT });
  const [statusMsg, setStatusMsg] = useState('');
  const [images, setImages] = useState<ImageItem[]>([]);
  const [lightboxIdx, setLightboxIdx] = useState<number>(-1);
  const [errorMsg, setErrorMsg] = useState('');
  const [scenes, setScenes] = useState<string[]>([]);
  const [expandedScenes, setExpandedScenes] = useState<Set<number>>(() => new Set());
  const [editingIdx, setEditingIdx] = useState<number>(-1);
  const [editText, setEditText] = useState('');
  const [savingScenes, setSavingScenes] = useState(false);
  const [regenIdx, setRegenIdx] = useState<number>(-1);
  const [assetGroups, setAssetGroups] = useState<AssetGroup[]>([]);
  const [selectedAssetGroupId, setSelectedAssetGroupId] = useState<number | null>(null);
  const [selectedGroupCharacters, setSelectedGroupCharacters] = useState<AssetGroupCharacter[]>([]);
  const [charThumbnails, setCharThumbnails] = useState<Record<number, string>>({});
  const [charCardsExpanded, setCharCardsExpanded] = useState(true);
  const [editingCharacter, setEditingCharacter] = useState<AssetGroupCharacter | null>(null);
  const [editCharName, setEditCharName] = useState('');
  const [editCharDesc, setEditCharDesc] = useState('');
  const [editCharRefs, setEditCharRefs] = useState<CharRefImage[]>([]);
  const [editCharRefUploading, setEditCharRefUploading] = useState(false);
  const [editCharSaving, setEditCharSaving] = useState(false);
  const editCharFileRef = useRef<HTMLInputElement>(null);
  const [assetGroupSaving, setAssetGroupSaving] = useState(false);
  const [colorMode, setColorModeState] = useState<ColorMode>('bw');
  const [mangaStyle, setMangaStyleState] = useState<MangaStyle>('japanese_manga');
  const [showMangaStyleMenu, setShowMangaStyleMenu] = useState(false);
  const mangaStyleMenuRef = useRef<HTMLDivElement>(null);
  const [imageCount, setImageCountState] = useState(DEFAULT_IMAGE_COUNT);
  const [showColorMenu, setShowColorMenu] = useState(false);
  const colorMenuRef = useRef<HTMLDivElement>(null);
  const lightboxRef = useRef<HTMLDivElement>(null);
  const chapterLoadRequestRef = useRef(0);
  const sceneAbortRef = useRef<AbortController | null>(null);
  const mangaAbortRef = useRef<Map<number, AbortController>>(new Map());

  // Subscribe to module-level generation store so we re-render when any chapter's gen state changes
  const [, setStoreTick] = useState(0);
  useEffect(() => genStore.subscribe(() => setStoreTick((t) => t + 1)), []);
  const liveGen = chapter ? genStore.get(chapter.id) : undefined;
  const isLiveGenerating = !!(liveGen && liveGen.active);

  // Reset state when chapter changes
  useEffect(() => {
    // Abort any in-flight scene generation when switching chapters
    sceneAbortRef.current?.abort();
    sceneAbortRef.current = null;
    const requestId = ++chapterLoadRequestRef.current;
    setImages([]);
    // Don't downgrade phase if there's an ongoing generation for this new chapter;
    // the derived `generating` flag below will reflect it via genStore.
    setPhase('idle');
    setProgress({ current: 0, total: DEFAULT_IMAGE_COUNT });
    setStatusMsg('');
    setErrorMsg('');
    setLightboxIdx(-1);
    setScenes([]);
    setExpandedScenes(new Set());
    setEditingIdx(-1);
    setRegenIdx(-1);
    setAssetGroups([]);
    setSelectedAssetGroupId(null);
    setSelectedGroupCharacters([]);
    setCharThumbnails({});
    setAssetGroupSaving(false);
    setColorModeState('bw');
    setImageCountState(DEFAULT_IMAGE_COUNT);
    setShowColorMenu(false);
    // Load existing scenes and characters if available
    if (chapter) {
      getChapterAssetGroup(chapter.id).then((r) => {
        if (chapterLoadRequestRef.current !== requestId) return;
        setAssetGroups(r.groups);
        setSelectedAssetGroupId(r.selected_group_id ?? null);
        const selGroup = r.groups.find((g: any) => g.id === r.selected_group_id);
        const chars = selGroup?.characters || [];
        setSelectedGroupCharacters(chars);
        if (chars.length > 0 && chapter) {
          const thumbs: Record<number, string> = {};
          Promise.all(chars.map(async (ch: any) => {
            try {
              const refs = await listCharRefImages(chapter.story_id, ch.id);
              if (refs.length > 0) thumbs[ch.id] = mangaThumbUrl(refs[0].object_key, 200) || '';
            } catch (_e) {}
          })).then(() => setCharThumbnails(thumbs));
        }
      }).catch((err: any) => {
        console.error('Failed to load asset group:', err);
        setErrorMsg('加载设定组失败: ' + (err.message || '未知错误'));
      });

      getColorMode(chapter.id).then((m) => {
        if (chapterLoadRequestRef.current === requestId) setColorModeState(m);
      }).catch(() => {});
      getImageCount(chapter.id).then((c) => {
        if (chapterLoadRequestRef.current === requestId) setImageCountState(c);
      }).catch(() => {});
      getMangaStyle(chapter.story_id).then((s) => {
        if (chapterLoadRequestRef.current === requestId) setMangaStyleState(s || 'japanese');
      }).catch(() => {});
      getScenes(chapter.id).then((s) => {
        if (chapterLoadRequestRef.current !== requestId) return;
        if (s.length > 0) {
          setScenes(s);
          setPhase('editing-scenes');
        } else if (chapter.images && chapter.images.length > 0) {
          const imgPrompts = chapter.images
            .sort((a, b) => a.image_number - b.image_number)
            .map(img => img.prompt || '');
          if (imgPrompts.some(p => p)) {
            setScenes(imgPrompts);
            setPhase('editing-scenes');
          }
        }
      }).catch(() => {});
    }
  }, [chapter?.id]);

  // Load existing images from chapter
  const existingImages: ImageItem[] =
    (chapter?.images ?? []).map((img) => ({
      image_number: img.image_number,
      image_path: img.image_path,
      prompt: img.prompt || '',
    }));

  // While generation is running for this chapter, prefer live images from the store
  const displayImages = isLiveGenerating
    ? liveGen!.images
    : images.length > 0
      ? images
      : existingImages;
  const imageByNumber = new Map(displayImages.map((img) => [img.image_number, img]));
  const imageIndexByNumber = new Map(displayImages.map((img, idx) => [img.image_number, idx]));
  const gallerySlots = scenes.length > 0
    ? scenes.map((scene, idx) => ({
      image_number: idx + 1,
      scene,
      img: imageByNumber.get(idx + 1),
    }))
    : displayImages.map((img) => ({
      image_number: img.image_number,
      scene: img.prompt || '',
      img,
    }));
  const lightboxImg = lightboxIdx >= 0 ? displayImages[lightboxIdx] : null;
  const hasSourceContent = !!chapter && ((chapter.messages?.length ?? 0) > 0 || !!chapter.novel_content?.trim());

  const openCharEditor = async (ch: AssetGroupCharacter) => {
    setEditingCharacter(ch);
    setEditCharName(ch.name);
    setEditCharDesc(ch.description || '');
    setEditCharRefs([]);
    if (chapter) {
      try {
        const refs = await listCharRefImages(chapter.story_id, ch.id);
        setEditCharRefs(refs);
      } catch {}
    }
  };

  const handleEditCharSave = async () => {
    if (!editingCharacter || !chapter) return;
    setEditCharSaving(true);
    try {
      await updateCharacterProfile(chapter.story_id, editingCharacter.id, editCharName, editCharDesc);
      // Refresh characters and thumbnails
      setSelectedGroupCharacters(prev =>
        prev.map(c => c.id === editingCharacter.id ? { ...c, name: editCharName, description: editCharDesc } : c)
      );
      setEditingCharacter(null);
    } catch (err: any) {
      setErrorMsg(err.message || '保存失败');
    } finally {
      setEditCharSaving(false);
    }
  };

  const handleEditCharRefUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !editingCharacter || !chapter) return;
    setEditCharRefUploading(true);
    try {
      const reader = new FileReader();
      const b64 = await new Promise<string>((resolve) => {
        reader.onload = () => resolve((reader.result as string).split(',')[1]);
        reader.readAsDataURL(file);
      });
      const ref = await addCharRefImage(chapter.story_id, editingCharacter.id, b64);
      setEditCharRefs(prev => [...prev, ref]);
      // Update thumbnail
      setCharThumbnails(prev => ({ ...prev, [editingCharacter.id]: mangaThumbUrl(ref.object_key, 200) || '' }));
    } catch (err: any) {
      setErrorMsg(err.message || '上传失败');
    } finally {
      setEditCharRefUploading(false);
      e.target.value = '';
    }
  };

  const handleEditCharRefDelete = async (filename: string) => {
    if (!editingCharacter || !chapter) return;
    try {
      await deleteCharRefImage(chapter.story_id, editingCharacter.id, filename);
      setEditCharRefs(prev => {
        const remaining = prev.filter(r => r.filename !== filename);
        if (remaining.length > 0) {
          setCharThumbnails(prev2 => ({ ...prev2, [editingCharacter.id]: mangaThumbUrl(remaining[0].object_key, 200) || '' }));
        } else {
          setCharThumbnails(prev2 => {
            const next = { ...prev2 };
            delete next[editingCharacter.id];
            return next;
          });
        }
        return remaining;
      });
    } catch (err: any) {
      setErrorMsg(err.message || '删除失败');
    }
  };

  const refreshChapterAssetFallback = async (chapterId: number) => {
    try {
      const r = await getChapterAssetGroup(chapterId);
      setAssetGroups(r.groups);
      setSelectedAssetGroupId(r.selected_group_id ?? null);
      // Load characters for selected group
      const selGroup = r.groups.find((g: any) => g.id === r.selected_group_id);
      const chars = selGroup?.characters || [];
      setSelectedGroupCharacters(chars);
      // Load thumbnails for characters
      if (chars.length > 0 && chapter) {
        const thumbs: Record<number, string> = {};
        const storyId = chapter.story_id;
        await Promise.all(chars.map(async (c: AssetGroupCharacter) => {
          try {
            const refs = await listCharRefImages(storyId, c.id);
            if (refs.length > 0) thumbs[c.id] = mangaThumbUrl(refs[0].object_key, 200) || '';
          } catch (_e) {}
        }));
        setCharThumbnails(thumbs);
      }
    } catch (err: any) {
      setErrorMsg(err.message);
    }
  };

  const handleSelectAssetGroup = async (value: string) => {
    if (!chapter) return;
    const nextGroupId = value === '' ? null : Number(value);
    const previous = selectedAssetGroupId;
    setSelectedAssetGroupId(nextGroupId);
    setAssetGroupSaving(true);
    try {
      const result = await setChapterAssetGroup(chapter.id, nextGroupId);
      setAssetGroups(result.groups);
      setSelectedAssetGroupId(result.selected_group_id ?? null);
      // Immediately extract characters from result
      const selGroup = result.groups.find((g: any) => g.id === result.selected_group_id);
      const chars = selGroup?.characters || [];
      setSelectedGroupCharacters(chars);
      // Load thumbnails right away
      if (chars.length > 0) {
        const thumbs: Record<number, string> = {};
        const storyId = chapter.story_id;
        Promise.all(chars.map(async (c: AssetGroupCharacter) => {
          try {
            const refs = await listCharRefImages(storyId, c.id);
            if (refs.length > 0) thumbs[c.id] = mangaThumbUrl(refs[0].object_key, 200) || '';
          } catch (_e) {}
        })).then(() => setCharThumbnails(thumbs));
      }
      // Still refresh in background for consistency
      refreshChapterAssetFallback(chapter.id).catch(() => {});
    } catch (err: any) {
      setSelectedAssetGroupId(previous);
      setErrorMsg(`切换设定组失败: ${err.message}`);
    } finally {
      setAssetGroupSaving(false);
    }
  };

  // ── Scene generation ──
  const handleGenerateScenes = async () => {
    if (!chapter) return;
    if (!hasSourceContent) {
      alert('请先在左侧进行对话或导入小说');
      return;
    }
    setPhase('generating-scenes');
    setStatusMsg('正在生成分镜…');
    setErrorMsg('');
    const controller = new AbortController();
    sceneAbortRef.current = controller;
    try {
      const generatedScenes = await generateScenes(chapter.id, controller.signal);
      if (controller.signal.aborted) return;
      setScenes(generatedScenes);
      setPhase('editing-scenes');
    } catch (err: any) {
      if (err.name === 'AbortError') return;
      setErrorMsg(`分镜生成失败: ${err.message}`);
      setPhase('idle');
    } finally {
      sceneAbortRef.current = null;
    }
  };

  const handleAbortScenes = () => {
    sceneAbortRef.current?.abort();
    sceneAbortRef.current = null;
  };

  // ── Scene editing ──
  const handleSceneEdit = (idx: number) => {
    setEditingIdx(idx);
    setEditText(scenes[idx]);
  };

  const handleSceneSave = async (idx: number) => {
    const updated = [...scenes];
    while (updated.length <= idx) updated.push('');
    updated[idx] = editText;
    setScenes(updated);
    setEditingIdx(-1);
    if (chapter) {
      try {
        await updateScenes(chapter.id, updated);
      } catch (err: any) {
        setErrorMsg(`保存分镜失败: ${err.message}`);
      }
    }
  };

  const toggleSceneExpanded = (idx: number) => {
    setExpandedScenes((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  };

  const handleSaveAllScenes = async () => {
    if (!chapter) return;
    setSavingScenes(true);
    try {
      await updateScenes(chapter.id, scenes);
    } catch (err: any) {
      setErrorMsg(err.message);
    } finally {
      setSavingScenes(false);
    }
  };

  // ── Single image regeneration ──
  const handleRegenImage = async (imageNumber: number) => {
    if (!chapter) return;
    const prompt = scenes[imageNumber - 1] || imageByNumber.get(imageNumber)?.prompt;
    if (!prompt) return;
    setRegenIdx(imageNumber);
    setErrorMsg('');
    try {
      // Save scenes first
      await updateScenes(chapter.id, scenes);
      const result = await regenerateImage(chapter.id, imageNumber, prompt);
      // Update in images list
      const newItem: ImageItem = {
        image_number: result.image_number,
        image_path: result.image_path,
        prompt: result.prompt,
      };
      setImages((prev) => {
        const updated = prev.length > 0 ? [...prev] : [...existingImages];
        const idx = updated.findIndex((i) => i.image_number === imageNumber);
        if (idx >= 0) updated[idx] = newItem;
        else updated.push(newItem);
        return updated.sort((a, b) => a.image_number - b.image_number);
      });
    } catch (err: any) {
      setErrorMsg(`第${imageNumber}张重新生成失败: ${err.message}`);
    } finally {
      setRegenIdx(-1);
    }
  };

  // ── Close color menu on outside click ──
  useEffect(() => {
    if (!showColorMenu) return;
    const handler = (e: MouseEvent) => {
      if (colorMenuRef.current && !colorMenuRef.current.contains(e.target as Node)) {
        setShowColorMenu(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showColorMenu]);

  // ── Close manga style menu on outside click ──
  useEffect(() => {
    if (!showMangaStyleMenu) return;
    const handler = (e: MouseEvent) => {
      if (mangaStyleMenuRef.current && !mangaStyleMenuRef.current.contains(e.target as Node)) {
        setShowMangaStyleMenu(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showMangaStyleMenu]);

  // ── Color mode selection ──
  const handleSelectColorMode = async (mode: ColorMode) => {
    if (!chapter) return;
    setShowColorMenu(false);
    await setColorMode(chapter.id, mode);
    setColorModeState(mode);
  };

  // ── Image generation ──

  const startImageGeneration = async (sourceScenes: string[]) => {
    if (!chapter) return;
    if (genStore.get(chapter.id)?.active) return;
    if (sourceScenes.length > 0) {
      try {
        await updateScenes(chapter.id, sourceScenes);
      } catch (err: any) {
        setErrorMsg(`保存分镜失败: ${err.message}`);
        setStatusMsg('');
        setPhase('editing-scenes');
        return;
      }
    }

    setImages([]);
    setErrorMsg('');
    setPhase('generating-images');

    const targetId = chapter.id;
    const targetTotal = imageCount;
    setProgress({ current: 0, total: targetTotal });
    setStatusMsg('正在生成漫画…');
    genStore.start(targetId, targetTotal);

    const controller = generateMangaStream(targetId, selectedAssetGroupId, (event: MangaProgress) => {
      switch (event.type) {
        case 'status':
          genStore.patch(targetId, { statusMsg: event.data.message });
          break;
        case 'progress':
          genStore.patch(targetId, {
            current: event.data.image_number,
            statusMsg: `正在生成第 ${event.data.image_number}/${event.data.total} 张漫画…`,
          });
          break;
        case 'image':
          genStore.pushImage(targetId, {
            image_number: event.data.image_number,
            image_path: event.data.image_path,
            prompt: event.data.prompt ?? sourceScenes[event.data.image_number - 1] ?? '',
          });
          if (event.data.image_number >= targetTotal) {
            mangaAbortRef.current.delete(targetId);
            const imgState = genStore.get(targetId);
            if (imgState?.images.length) {
              setImages(imgState.images);
              if (sourceScenes.length > 0) setScenes(sourceScenes);
            }
            genStore.finish(targetId);
            setPhase('editing-scenes');
          }
          break;
        case 'image_error':
          // A single image failed but the job continues to the next image.
          genStore.patch(targetId, {
            statusMsg: `第 ${event.data.image_number} 张生成失败: ${event.data.error || '未知错误'}，继续下一张…`,
          });
          break;
        case 'done': {
          mangaAbortRef.current.delete(targetId);
          const doneState = genStore.get(targetId);
          if (doneState?.images.length) {
            setImages(doneState.images);
            if (sourceScenes.length > 0) setScenes(sourceScenes);
          }
          genStore.finish(targetId);
          setPhase('editing-scenes');
          onChapterRefresh?.(targetId);
          setTimeout(() => genStore.clear(targetId), 800);
          break;
        }
        case 'error':
          mangaAbortRef.current.delete(targetId);
          genStore.finish(targetId, event.data.detail || event.data.error || '未知错误');
          setPhase('editing-scenes');
          break;
      }
    });
    mangaAbortRef.current.set(targetId, controller);
  };

  const handleGenerateImages = async () => {
    await startImageGeneration(scenes);
  };

  const handleAbortManga = () => {
    if (!chapter) return;
    const targetId = chapter.id;
    const state = genStore.get(targetId);
    mangaAbortRef.current.get(targetId)?.abort();
    mangaAbortRef.current.delete(targetId);
    if (state?.images.length) {
      setImages(state.images);
    }
    setPhase(state?.images.length ? 'editing-scenes' : 'idle');
    genStore.finish(targetId, '已中止生成');
    window.setTimeout(() => onChapterRefresh?.(targetId), 700);
  };

  // ── Lightbox keyboard/scroll navigation ──
  const handleLightboxNav = useCallback((dir: 'prev' | 'next') => {
    setLightboxIdx((cur) => {
      if (dir === 'prev' && cur > 0) return cur - 1;
      if (dir === 'next' && cur < displayImages.length - 1) return cur + 1;
      return cur;
    });
  }, [displayImages.length]);

  useEffect(() => {
    if (lightboxIdx < 0) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { e.preventDefault(); handleLightboxNav('prev'); }
      if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { e.preventDefault(); handleLightboxNav('next'); }
      if (e.key === 'Escape') setLightboxIdx(-1);
    };
    const wheelHandler = (e: WheelEvent) => {
      e.preventDefault();
      if (e.deltaY < 0) handleLightboxNav('prev');
      if (e.deltaY > 0) handleLightboxNav('next');
    };
    window.addEventListener('keydown', handler);
    const lb = lightboxRef.current;
    lb?.addEventListener('wheel', wheelHandler, { passive: false });
    return () => {
      window.removeEventListener('keydown', handler);
      lb?.removeEventListener('wheel', wheelHandler);
    };
  }, [lightboxIdx, handleLightboxNav]);

  const generating = isLiveGenerating || phase === 'generating-images';

  // Tick every 1s while generating so the stall indicator re-renders.
  const [, setNowTick] = useState(0);
  useEffect(() => {
    if (!generating) return;
    const id = window.setInterval(() => setNowTick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [generating]);

  const stallSeconds = isLiveGenerating && liveGen
    ? Math.max(0, Math.floor((Date.now() - liveGen.lastEventAt) / 1000))
    : 0;
  const isStalled = isLiveGenerating && stallSeconds >= 30;

  const liveProgress = isLiveGenerating
    ? { current: liveGen!.current, total: liveGen!.total }
    : progress;
  const liveStatusMsg = isLiveGenerating ? liveGen!.statusMsg : statusMsg;
  const liveErrorMsg = liveGen?.errorMsg ?? errorMsg;
  const hasImages = displayImages.length > 0;
  const canChangeImageCount = !generating && !hasImages;
  const activeImageCount = isLiveGenerating ? liveProgress.total : (scenes.length > 0 ? scenes.length : (hasImages ? displayImages.length : imageCount));

  return (
    <div className="flex flex-col h-full bg-paper-base">
      {/* Header */}
      <div className="px-3 md:px-4 py-2.5 border-b border-paper-border flex items-center justify-between gap-2 flex-wrap">
        <h2 className="text-sm font-semibold text-sumi-dim tracking-wide shrink-0 hidden md:block">
          第 {chapter?.chapter_number ?? '–'} 话 · 漫画
        </h2>
        <div className="flex items-center gap-1.5 md:gap-2 flex-wrap justify-end">
          {assetGroups.length > 0 && (
            <select
              value={selectedAssetGroupId ?? ''}
              onChange={(e) => handleSelectAssetGroup(e.target.value)}
              disabled={!chapter || generating || assetGroupSaving}
              className="max-w-[160px] px-2 py-1.5 text-xs font-medium rounded-md bg-paper-surface text-sumi-dim border border-paper-border outline-none focus:border-vermilion transition-colors disabled:opacity-50"
              title="选择本话继承的全局角色卡和垫图组"
            >
              {assetGroups.map((group) => (
                <option key={group.id ?? 'default'} value={group.id ?? ''}>
                  {group.is_default ? '默认组' : group.name}
                </option>
              ))}
            </select>
          )}
          {/* Image count selector */}
          {!generating && (phase === 'idle' || phase === 'editing-scenes') && (
            <select
              value={imageCount}
              onChange={async (e) => {
                const count = Number(e.target.value);
                const previous = imageCount;
                setImageCountState(count);
                if (chapter) {
                  try {
                    await setImageCount(chapter.id, count);
                  } catch (err: any) {
                    setImageCountState(previous);
                    setErrorMsg(`保存生成张数失败: ${err.message}`);
                  }
                }
              }}
              disabled={!canChangeImageCount}
              className="px-2 py-1.5 text-xs font-medium rounded-md bg-paper-surface text-sumi-dim border border-paper-border outline-none focus:border-vermilion transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              title={canChangeImageCount ? '生成张数' : '已有图片时不能修改生成张数'}
            >
              {ALLOWED_IMAGE_COUNTS.map((n) => (
                <option key={n} value={n}>{n}张</option>
              ))}
            </select>
          )}
          {hasImages && (
            <button
              onClick={() => {
                displayImages.forEach((img) => {
                  const a = document.createElement('a');
                  a.href = mangaImageUrl(img.image_path);
                  a.download = `panel_${img.image_number.toString().padStart(2, '0')}.png`;
                  a.click();
                });
              }}
              className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-md
                         bg-paper-surface hover:bg-paper-border text-sumi-dim transition-colors"
            >
              <Download size={13} />
              下载
            </button>
          )}
          {!generating && (phase === 'idle' || phase === 'editing-scenes') && (
            <button
              onClick={handleGenerateScenes}
              disabled={!hasSourceContent}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md
                         bg-vermilion hover:bg-vermilion-hover text-white disabled:opacity-40
                         disabled:cursor-not-allowed transition-colors"
            >
              <RefreshCw size={13} />
              AI 重写分镜
            </button>
          )}
          {!generating && phase === 'editing-scenes' && scenes.length > 0 && (
            <>
              {/* Manga style selector */}
              <div className="relative" ref={mangaStyleMenuRef}>
                <button
                  onClick={() => setShowMangaStyleMenu((v) => !v)}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md bg-paper-surface hover:bg-paper-border text-sumi-dim transition-colors"
                  title="漫画风格"
                >
                  {MANGA_STYLE_LABELS[mangaStyle]}
                  <ChevronDown size={11} className={`transition-transform ${showMangaStyleMenu ? 'rotate-180' : ''}`} />
                </button>
                {showMangaStyleMenu && (
                  <div className="absolute right-0 top-full mt-1 w-40 rounded-lg border border-paper-border bg-paper-raised shadow-dropdown z-50 overflow-hidden">
                    {(Object.keys(MANGA_STYLE_LABELS) as MangaStyle[]).map((style) => (
                      <button
                        key={style}
                        onClick={async () => {
                          setShowMangaStyleMenu(false);
                          if (!chapter || style === mangaStyle) return;
                          setMangaStyleState(style);
                          try {
                            await setMangaStyle(chapter.story_id, style);
                          } catch (err: any) {
                            setErrorMsg(`保存漫画风格失败: ${err.message}`);
                            setMangaStyleState(mangaStyle);
                          }
                        }}
                        className={`w-full flex items-center gap-2 px-3 py-2 text-xs transition-colors hover:bg-paper-surface
                          ${style === mangaStyle ? 'text-kinpaku font-semibold' : 'text-sumi-dim'}`}
                      >
                        {MANGA_STYLE_LABELS[style]}
                        {style === mangaStyle && <Check size={12} className="ml-auto text-kinpaku" />}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              {/* Color mode selector */}
              <div className="relative" ref={colorMenuRef}>
                <button
                  onClick={() => setShowColorMenu((v) => !v)}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md bg-paper-surface hover:bg-paper-border text-sumi-dim transition-colors"
                  title="色彩模式"
                >
                  {COLOR_MODE_LABELS[colorMode]}
                  <ChevronDown size={11} className={`transition-transform ${showColorMenu ? 'rotate-180' : ''}`} />
                </button>
                {showColorMenu && (
                  <div className="absolute right-0 top-full mt-1 w-36 rounded-lg border border-paper-border bg-paper-raised shadow-dropdown z-50 overflow-hidden">
                    {(Object.keys(COLOR_MODE_LABELS) as ColorMode[]).map((mode) => (
                      <button
                        key={mode}
                        onClick={() => handleSelectColorMode(mode)}
                        className={`w-full flex items-center gap-2 px-3 py-2 text-xs transition-colors hover:bg-paper-surface
                          ${mode === colorMode ? 'text-kinpaku font-semibold' : 'text-sumi-dim'}`}
                      >
                        {COLOR_MODE_LABELS[mode]}
                        {mode === colorMode && <Check size={12} className="ml-auto text-kinpaku" />}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              {/* Generate button */}
              <button
                onClick={handleGenerateImages}
                className="flex items-center gap-1.5 px-4 py-1.5 text-xs font-medium rounded-md
                           bg-kinpaku hover:bg-kinpaku/80 text-white transition-colors"
              >
                <Sparkles size={13} />
                {existingImages.length > 0 ? '重新生成漫画' : '生成漫画'}
              </button>
            </>
          )}
          {phase === 'generating-scenes' && (
            <div className="flex items-center gap-2">
              <span className="flex items-center gap-1.5 text-xs text-cream-dim">
                <Loader2 size={13} className="animate-spin" />
                AI 生成分镜中…
              </span>
              <button
                onClick={handleAbortScenes}
                className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium rounded-md
                           bg-vermilion-light/30 hover:bg-vermilion-light/50 text-vermilion border border-vermilion/20 transition-colors"
                title="停止生成分镜"
              >
                <Square size={11} />
                停止
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Progress bar — segmented: N+1 dots with N segments for N images */}
      {generating && (
        <div className="px-5 py-4 border-b border-paper-border bg-paper-surface/50">
          <div className="flex items-center justify-between text-xs mb-3 gap-3">
            <div className="flex items-center gap-2 min-w-0">
              <span className="text-sumi-dim font-medium truncate">{liveStatusMsg}</span>
              {isStalled && (
                <span
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-kinpaku-light/50 border border-kinpaku/30 text-kinpaku text-[10px] font-medium whitespace-nowrap"
                  title="图片服务响应较慢，正在继续等待（上游可能在排队）"
                >
                  <Loader2 size={10} className="animate-spin" />
                  等待中 {stallSeconds}s
                </span>
              )}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button
                onClick={handleAbortManga}
                className="flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium rounded-md
                           bg-vermilion-light/30 hover:bg-vermilion-light/50 text-vermilion border border-vermilion/20 transition-colors"
                title="停止生成漫画"
              >
                <Square size={10} />
                停止
              </button>
              <span className="text-kinpaku font-mono font-bold">
                {Math.round((liveProgress.current / liveProgress.total) * 100)}%
              </span>
            </div>
          </div>
          {/* Segmented bar: N segments between N+1 dot markers */}
          <div className="flex items-center w-full" style={{ height: 24 }}>
            {Array.from({ length: liveProgress.total }, (_, i) => {
              const segFilled = i < liveProgress.current;
              const segActive = i === liveProgress.current && isLiveGenerating;
              return (
                <div key={i} className="flex items-center flex-1" style={{ minWidth: 0 }}>
                  {/* Dot marker at segment start */}
                  <div
                    className={`shrink-0 rounded-full transition-all duration-500 ${
                      segFilled
                        ? 'bg-kinpaku shadow-[0_0_6px_rgba(184,149,58,0.4)]'
                        : 'bg-paper-border'
                    }`}
                    style={{ width: 10, height: 10 }}
                  />
                  {/* Segment bar */}
                  <div className="flex-1 mx-0.5 h-1.5 rounded-full bg-paper-border overflow-hidden">
                    <div
                      className={`h-full rounded-full transition-all duration-700 ease-out ${
                        segFilled
                          ? 'w-full bg-gradient-to-r from-kinpaku to-kinpaku/70'
                          : segActive
                            ? 'w-1/2 bg-kinpaku/60 animate-pulse'
                            : 'w-0'
                      }`}
                    />
                  </div>
                </div>
              );
            })}
            {/* Final dot (N+1) */}
            <div
              className={`shrink-0 rounded-full transition-all duration-500 ${
                liveProgress.current >= liveProgress.total
                  ? 'bg-kinpaku shadow-[0_0_6px_rgba(184,149,58,0.4)]'
                  : 'bg-paper-border'
              }`}
              style={{ width: 10, height: 10 }}
            />
          </div>
          {/* Number labels under dots */}
          <div className="flex justify-between mt-1 text-[10px] text-sumi-faint">
            {Array.from({ length: liveProgress.total + 1 }, (_, i) => (
              <span
                key={i}
                className={i <= liveProgress.current && liveProgress.current > 0 ? 'text-kinpaku font-medium' : ''}
              >
                {i}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Error banner */}
      {liveErrorMsg && (
        <div className="mx-5 mt-3 px-4 py-3 rounded-lg bg-vermilion-light/20 border border-vermilion/20 text-vermilion text-sm flex items-start gap-2">
          <span className="shrink-0 mt-0.5">⚠</span>
          <div>
            <div className="font-medium mb-0.5">生成出错</div>
            <div className="text-xs text-vermilion/80">{liveErrorMsg}</div>
          </div>
        </div>
      )}

      {/* Main content area */}
      <div className="flex-1 overflow-y-auto px-3 md:px-5 py-4">
        {/* Character cards from selected asset group */}
        {(phase === 'idle' || phase === 'editing-scenes') && (
          <div className="mb-4 rounded-lg border border-paper-border bg-paper-surface/60 overflow-hidden">
            <button
              onClick={() => setCharCardsExpanded((v) => !v)}
              className="w-full flex items-center justify-between px-3 py-2 text-xs font-semibold text-sumi-dim uppercase tracking-wide hover:bg-paper-surface transition-colors"
            >
              <span className="flex items-center gap-1.5">
                🎭 角色外貌卡
                {selectedGroupCharacters.length > 0 && (
                  <span className="text-[10px] font-normal normal-case px-1.5 py-0.5 rounded bg-vermilion-light/30 text-vermilion border border-vermilion/20">
                    {selectedGroupCharacters.length} 个角色
                  </span>
                )}
                {selectedAssetGroupId && selectedGroupCharacters.length === 0 && '（该设定组暂无角色卡）'}
                {!selectedAssetGroupId && '（未选择设定组）'}
              </span>
              {charCardsExpanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            </button>
            {charCardsExpanded && (
              <div className="px-3 pb-3">
                {selectedGroupCharacters.length > 0 ? (
                  <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 gap-3">
                    {selectedGroupCharacters.map((ch) => {
                      const thumb = charThumbnails[ch.id];
                      return (
                        <div
                          key={ch.id}
                          className="rounded-lg border border-paper-border bg-paper-raised overflow-hidden cursor-pointer hover:border-vermilion transition-colors group shadow-card"
                          onClick={() => openCharEditor(ch)}
                        >
                          <div className="aspect-square bg-paper-surface flex items-center justify-center">
                            {thumb ? (
                              <img
                                src={thumb}
                                alt={ch.name}
                                className="w-full h-full object-cover"
                                loading="lazy"
                              />
                            ) : (
                              <Users size={24} className="text-sumi-faint" />
                            )}
                          </div>
                          <div className="px-2 py-1.5 text-center">
                            <span className="text-xs text-sumi-dim truncate block">{ch.name}</span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                ) : selectedAssetGroupId ? (
                  <p className="text-xs text-sumi-faint py-6 text-center">该设定组暂无角色卡，请在小说卡片处添加</p>
                ) : (
                  <p className="text-xs text-sumi-faint py-6 text-center">请在上方选择一个设定组</p>
                )}
              </div>
            )}
          </div>
        )}

        {/* Scene-only editor (when no images yet) */}
        {scenes.length > 0 && displayImages.length === 0 && !generating && (
          <div className="mb-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-semibold text-sumi-dim uppercase tracking-wide">分镜脚本（可编辑）</h3>
              <button
                onClick={handleSaveAllScenes}
                disabled={savingScenes}
                className="text-xs text-vermilion hover:text-vermilion-hover transition-colors"
              >
                {savingScenes ? '保存中…' : '保存修改'}
              </button>
            </div>
            <div className="space-y-2">
              {scenes.map((scene, idx) => (
                <div key={idx} className="panel-frame overflow-hidden">
                  <div className="flex items-start gap-2 p-3">
                    <span className="shrink-0 w-6 h-6 flex items-center justify-center rounded bg-paper-surface text-[10px] text-sumi-dim font-mono mt-0.5">
                      {idx + 1}
                    </span>
                    {editingIdx === idx ? (
                      <textarea
                        value={editText}
                        onChange={(e) => setEditText(e.target.value)}
                        className="flex-1 bg-paper-surface text-sm text-sumi rounded p-2 resize-none outline-none border border-paper-border focus:border-vermilion"
                        rows={4}
                        autoFocus
                      />
                    ) : (
                      <div className="flex-1 min-w-0">
                        <ScenePagePreview scene={scene} expanded={expandedScenes.has(idx)} />
                        <button
                          type="button"
                          onClick={() => toggleSceneExpanded(idx)}
                          className="mt-1 text-xs text-vermilion hover:text-vermilion-hover transition-colors"
                        >
                          {expandedScenes.has(idx) ? '收起' : '展开'}
                        </button>
                      </div>
                    )}
                    <div className="shrink-0 flex gap-1">
                      {editingIdx === idx ? (
                        <>
                          <button onClick={() => handleSceneSave(idx)} className="p-1 rounded hover:bg-paper-surface text-success transition-colors"><Check size={14} /></button>
                          <button onClick={() => setEditingIdx(-1)} className="p-1 rounded hover:bg-paper-surface text-sumi-dim transition-colors"><X size={14} /></button>
                        </>
                      ) : (
                        <button onClick={() => handleSceneEdit(idx)} className="p-1 rounded hover:bg-paper-surface text-sumi-faint hover:text-sumi-dim transition-colors"><Pencil size={12} /></button>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Empty state */}
        {displayImages.length === 0 && !generating && scenes.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full text-sumi-faint gap-3">
            <ImageIcon size={48} strokeWidth={1} />
            <span className="text-sm">对话或导入小说后点击上方按钮生成分镜</span>
          </div>
        )}

        {/* Images gallery with inline scene editor */}
        <div className="space-y-6">
          {gallerySlots.map(({ image_number, scene, img }) => {
            const sceneIdx = image_number - 1;
            const isEditing = editingIdx === sceneIdx;
            const isRegenerating = regenIdx === image_number;
            return (
              <div key={image_number} className="group">
                {img ? (
                <div
                  className="relative rounded-lg overflow-hidden border border-paper-border bg-paper-surface cursor-pointer
                             hover:border-sumi-faint transition-colors shadow-sm"
                  onClick={() => {
                    const idx = imageIndexByNumber.get(image_number);
                    if (idx !== undefined) setLightboxIdx(idx);
                  }}
                >
                  <img
                    src={mangaThumbUrl(img.image_path, 1280, isRegenerating ? Date.now() : undefined)!}
                    alt={`Panel ${image_number}`}
                    className={`w-full object-contain ${isRegenerating ? 'opacity-30' : ''}`}
                    loading="lazy"
                    decoding="async"
                  />
                  <div className="absolute top-3 left-3 z-10 px-2 py-0.5 bg-sumi/70 rounded text-[10px] text-white font-mono">
                    {image_number}/{activeImageCount}
                  </div>
                  {!isRegenerating && !generating && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleRegenImage(image_number);
                      }}
                      className="absolute top-3 right-3 z-10 p-1.5 rounded-md bg-sumi/70 hover:bg-kinpaku text-white hover:text-white transition-colors"
                      title="重新生成此图"
                    >
                      <RefreshCw size={12} />
                    </button>
                  )}
                  {isRegenerating && (
                    <div className="absolute inset-0 flex items-center justify-center bg-paper-base/60">
                      <div className="flex flex-col items-center gap-2">
                        <Loader2 size={32} className="animate-spin text-kinpaku" />
                        <span className="text-sm text-sumi-dim">重新生成中…</span>
                      </div>
                    </div>
                  )}
                  {!isRegenerating && (
                    <div className="absolute inset-0 bg-transparent hover:bg-sumi/5 transition-colors flex items-center justify-center opacity-0 hover:opacity-100">
                      <span className="bg-sumi/60 text-white text-xs px-3 py-1 rounded-full">点击放大</span>
                    </div>
                  )}
                </div>
                ) : (
                <div className="relative rounded-lg overflow-hidden border border-dashed border-paper-border bg-paper-surface/60 h-64 flex items-center justify-center">
                  <div className="absolute top-3 left-3 px-2 py-0.5 bg-sumi/50 rounded text-[10px] text-white font-mono">
                    {image_number}/{activeImageCount}
                  </div>
                  {!isRegenerating && !generating && (
                    <button
                      onClick={() => handleRegenImage(image_number)}
                      className="absolute top-3 right-3 p-1.5 rounded-md bg-sumi/70 hover:bg-kinpaku text-white transition-colors"
                      title="生成此图"
                    >
                      <RefreshCw size={12} />
                    </button>
                  )}
                  {isRegenerating && (
                    <div className="absolute inset-0 flex items-center justify-center bg-ink/70">
                      <div className="flex flex-col items-center gap-2">
                        <Loader2 size={32} className="animate-spin text-amber-accent" />
                        <span className="text-sm text-cream-dim">生成中…</span>
                      </div>
                    </div>
                  )}
                  {!isRegenerating && (
                    <div className="flex flex-col items-center gap-2 text-warm-gray">
                      {generating ? <Loader2 size={24} className="animate-spin" /> : <ImageIcon size={28} strokeWidth={1.5} />}
                      <span className="text-xs">{generating ? '等待生成…' : '未生成'}</span>
                    </div>
                  )}
                </div>
                )}
                {scene && (
                  <div className="mt-2 rounded-lg border border-paper-border bg-paper-surface/60 p-2.5">
                    <div className="flex items-start gap-2">
                      {isEditing ? (
                        <textarea
                          value={editText}
                          onChange={(e) => setEditText(e.target.value)}
                          className="flex-1 bg-paper-base text-xs text-sumi rounded p-2 resize-none outline-none border border-paper-border focus:border-vermilion leading-relaxed"
                          rows={3}
                          autoFocus
                        />
                      ) : (
                        <div className="flex-1 min-w-0">
                          <ScenePagePreview scene={scene} expanded={expandedScenes.has(sceneIdx)} />
                          <button
                            type="button"
                            onClick={() => toggleSceneExpanded(sceneIdx)}
                            className="mt-1 text-xs text-coral hover:text-coral-light transition-colors"
                          >
                            {expandedScenes.has(sceneIdx) ? '收起' : '展开'}
                          </button>
                        </div>
                      )}
                      <div className="shrink-0 flex items-center gap-1">
                        {isEditing ? (
                          <>
                            <button onClick={() => handleSceneSave(sceneIdx)} className="p-1 rounded hover:bg-paper-surface text-success transition-colors" title="保存"><Check size={13} /></button>
                            <button onClick={() => setEditingIdx(-1)} className="p-1 rounded hover:bg-paper-surface text-sumi-dim transition-colors" title="取消"><X size={13} /></button>
                          </>
                        ) : (
                          <button onClick={() => handleSceneEdit(sceneIdx)} className="p-1 rounded hover:bg-paper-surface text-sumi-faint hover:text-sumi-dim transition-colors" title="编辑分镜"><Pencil size={12} /></button>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* Loading placeholders */}
        {generating && scenes.length === 0 && displayImages.length < activeImageCount && (
          <div className="mt-6 space-y-6">
            {Array.from({ length: Math.max(activeImageCount - displayImages.length, 0) }, (_, i) => (
              <div
                key={`placeholder-${i}`}
                className="rounded-lg border border-paper-border bg-paper-surface/50 h-64 flex items-center justify-center"
              >
                <div className="flex flex-col items-center gap-2 text-sumi-faint">
                  <Loader2 size={24} className="animate-spin" />
                  <span className="text-xs">等待生成…</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Lightbox with scroll navigation */}
      {lightboxImg && (
        <div
          ref={lightboxRef}
          className="fixed inset-0 z-50 bg-sumi/95 flex flex-col items-center justify-center cursor-pointer select-none"
          onClick={() => setLightboxIdx(-1)}
        >
          {/* Close */}
          <button
            className="absolute top-4 right-4 p-2 rounded-full bg-white/10 hover:bg-white/20 text-cream transition-colors z-10"
            onClick={(e) => { e.stopPropagation(); setLightboxIdx(-1); }}
          >
            <X size={24} />
          </button>
          {/* Counter */}
          <div className="absolute top-4 left-4 px-3 py-1 bg-white/10 rounded-full text-sm text-cream font-mono">
            {lightboxImg.image_number} / {activeImageCount}
          </div>
          {/* Nav up */}
          {lightboxIdx > 0 && (
            <button
              className="absolute top-16 left-1/2 -translate-x-1/2 p-2 rounded-full bg-white/10 hover:bg-white/20 text-cream transition-colors z-10"
              onClick={(e) => { e.stopPropagation(); handleLightboxNav('prev'); }}
            >
              <ChevronUp size={20} />
            </button>
          )}
          {/* Image */}
          <img
            src={mangaImageUrl(lightboxImg.image_path)}
            alt={`Panel ${lightboxImg.image_number}`}
            className="max-w-[90%] max-h-[75vh] object-contain rounded-lg"
            onClick={(e) => e.stopPropagation()}
          />
          {/* Nav down */}
          {lightboxIdx < displayImages.length - 1 && (
            <button
              className="absolute bottom-16 left-1/2 -translate-x-1/2 p-2 rounded-full bg-white/10 hover:bg-white/20 text-cream transition-colors z-10"
              onClick={(e) => { e.stopPropagation(); handleLightboxNav('next'); }}
            >
              <ChevronDown size={20} />
            </button>
          )}
        </div>
      )}

      {/* Character edit modal */}
      {editingCharacter !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/30 backdrop-blur-sm p-3 sm:p-4" onClick={() => setEditingCharacter(null)}>
          <div className="bg-paper-raised border border-paper-border rounded-xl w-full max-w-lg h-[580px] shadow-modal flex flex-col" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-paper-border flex-shrink-0">
              <h3 className="text-sm font-semibold flex items-center gap-2 text-sumi">
                <Users size={16} className="text-vermilion" />
                编辑角色卡
              </h3>
              <button onClick={() => setEditingCharacter(null)} className="p-1 text-sumi-faint hover:text-sumi-dim transition-colors">
                <X size={16} />
              </button>
            </div>

            {/* Body */}
            <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
              <div>
                <label className="block text-xs text-sumi-dim mb-1.5">角色名称</label>
                <input
                  value={editCharName}
                  onChange={(e) => setEditCharName(e.target.value)}
                  className="w-full px-3 py-2 bg-paper-surface border border-paper-border rounded-md text-sm text-sumi focus:outline-none focus:border-vermilion"
                  placeholder="输入角色名称"
                />
              </div>

              <div>
                <label className="block text-xs text-sumi-dim mb-1.5">角色描述</label>
                <textarea
                  value={editCharDesc}
                  onChange={(e) => setEditCharDesc(e.target.value)}
                  rows={4}
                  className="w-full bg-paper-surface text-sm text-sumi rounded-md p-3 resize-none outline-none border border-paper-border focus:border-vermilion"
                  placeholder="描述角色的性格、外貌、背景等..."
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="text-xs text-sumi-dim">人物参考图 ({editCharRefs.length}/5)</label>
                  <input
                    ref={editCharFileRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={handleEditCharRefUpload}
                  />
                  <button
                    onClick={() => editCharFileRef.current?.click()}
                    disabled={editCharRefUploading || editCharRefs.length >= 5}
                    className="flex items-center gap-1 px-2.5 py-1.5 text-xs rounded-md bg-vermilion hover:bg-vermilion-hover text-white disabled:opacity-40 transition-colors"
                  >
                    {editCharRefUploading ? <Loader2 size={12} className="animate-spin" /> : <ImagePlus size={12} />}
                    上传图片
                  </button>
                </div>
                {editCharRefs.length === 0 ? (
                  <div className="flex flex-col items-center justify-center py-8 text-sumi-faint text-sm gap-2 border border-dashed border-paper-border rounded-md">
                    <ImagePlus size={28} className="opacity-50" />
                    <span>暂无参考图</span>
                  </div>
                ) : (
                  <div className="grid grid-cols-5 gap-2">
                    {editCharRefs.map((ref) => (
                      <div key={ref.filename} className="relative group aspect-square rounded-md overflow-hidden border border-paper-border bg-paper-surface">
                        <img
                          src={mangaThumbUrl(ref.object_key, 200) || ""}
                          alt={ref.filename}
                          className="w-full h-full object-cover"
                          loading="lazy"
                        />
                        <button
                          onClick={() => handleEditCharRefDelete(ref.filename)}
                          className="absolute top-1 right-1 p-1 rounded-md bg-vermilion hover:bg-vermilion-hover text-white shadow-lg transition-colors opacity-0 group-hover:opacity-100"
                          title="删除"
                        >
                          <Trash2 size={10} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="px-5 py-3 border-t border-paper-border flex-shrink-0 flex justify-end">
              <button
                onClick={handleEditCharSave}
                disabled={editCharSaving || !editCharName.trim()}
                className="px-5 py-2 bg-vermilion hover:bg-vermilion-hover text-white text-sm font-medium rounded-md transition-colors disabled:opacity-40"
              >
                {editCharSaving ? "保存中..." : "保存"}
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
