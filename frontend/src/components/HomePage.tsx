import { useEffect, useState, useRef } from 'react';
import {
  Plus,
  BookOpenText,
  Pencil,
  Trash2,
  ImagePlus,
  ChevronRight,
  X,
  Check,
  Sparkles,
  Users,
  Loader2,
  Download,
  Upload,
  Layers,
} from 'lucide-react';
import {
  listStories,
  createStory,
  updateStory,
  deleteStory,
  exportStory,
  importStoryPackage,
  uploadStoryCover,
  mangaThumbUrl,
  refImageUrl,
  addStoryRefImage,
  deleteStoryRefImage,
  getStoryAssetGroups,
  createStoryAssetGroup,
  updateStoryAssetGroup,
  deleteStoryAssetGroup,
  type Story,
  type RefImage,
  type AssetGroup,
  type AssetGroupCharacter,
  type CharacterProfile,
  type CharRefImage,
  listCharacterProfiles,
  createCharacterProfile,
  updateCharacterProfile,
  deleteCharacterProfile,
  listCharRefImages,
  addCharRefImage,
  deleteCharRefImage,
} from '../api';

interface Props {
  onSelectStory: (story: Story) => void;
}

export default function HomePage({ onSelectStory }: Props) {
  const [stories, setStories] = useState<Story[]>([]);
  const [loading, setLoading] = useState(true);

  // New story dialog
  const [showNew, setShowNew] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [newCoverPreview, setNewCoverPreview] = useState<string | null>(null);
  const [newCoverBase64, setNewCoverBase64] = useState<string | null>(null);
  const newCoverInputRef = useRef<HTMLInputElement>(null);

  // Edit mode
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editDesc, setEditDesc] = useState('');

  const fileRef = useRef<HTMLInputElement>(null);
  const importFileRef = useRef<HTMLInputElement>(null);
  const [uploadingCover, setUploadingCover] = useState<number | null>(null);
  const [importingStory, setImportingStory] = useState(false);
  const [importProgress, setImportProgress] = useState<{ message: string; percent?: number } | null>(null);
  const [exportingStoryId, setExportingStoryId] = useState<number | null>(null);

  // Character card modal (profile-based)
  const [charModalStoryId, setCharModalStoryId] = useState<number | null>(null);
  const [characters, setCharacters] = useState<CharacterProfile[]>([]);
  const [editingCharId, setEditingCharId] = useState<number | null>(null);
  const [charFormName, setCharFormName] = useState('');
  const [charFormDesc, setCharFormDesc] = useState('');
  const [charFormSaving, setCharFormSaving] = useState(false);
  const [charRefImages, setCharRefImages] = useState<CharRefImage[]>([]);
  const [charRefUploading, setCharRefUploading] = useState(false);
  const charFileRef = useRef<HTMLInputElement>(null);
  const [storyCharFlags, setStoryCharFlags] = useState<Record<number, boolean>>({});

  // Ref images modal (multi)
  const [refModalStoryId, setRefModalStoryId] = useState<number | null>(null);
  const [refModalImages, setRefModalImages] = useState<RefImage[]>([]);
  const [refModalMax, setRefModalMax] = useState(4);
  const [refModalLoading] = useState(false);
  const [refModalUploading, setRefModalUploading] = useState(false);
  const [storyRefFlags, setStoryRefFlags] = useState<Record<number, boolean>>({});
  const refModalFileRef = useRef<HTMLInputElement>(null);

  // Story global asset groups
  const [assetModalStoryId, setAssetModalStoryId] = useState<number | null>(null);
  const [assetGroups, setAssetGroups] = useState<AssetGroup[]>([]);
  const [assetSelectedKey, setAssetSelectedKey] = useState<string>("");
  const [assetDraftName, setAssetDraftName] = useState("");
  const [assetDraftDesc, setAssetDraftDesc] = useState("");
  const [assetDraftCharIds, setAssetDraftCharIds] = useState<Set<number>>(new Set());
  const [allStoryCharacters, setAllStoryCharacters] = useState<CharacterProfile[]>([]);
  const [charThumbnails, setCharThumbnails] = useState<Record<number, string>>({});
  const [assetModalLoading, setAssetModalLoading] = useState(false);
  const [assetModalSaving, setAssetModalSaving] = useState(false);
  const assetModalRequestRef = useRef(0);

  const activeAssetGroup = assetGroups.find((g) => String(g.id) === assetSelectedKey);


  const syncActiveAssetDraft = (group: AssetGroup | undefined) => {
    setAssetDraftName(group?.name ?? '');
    setAssetDraftDesc(group?.description ?? '');
    setAssetDraftCharIds(new Set((group?.characters ?? []).map((ch: AssetGroupCharacter) => ch.id)));
  };

  const openCharModal = async (storyId: number) => {
    setCharModalStoryId(storyId);
    setCharacters([]);
    setEditingCharId(null);
    try {
      const list = await listCharacterProfiles(storyId);
      setCharacters(list);
      if (list.length > 0) {
        setEditingCharId(list[0].id);
        setCharFormName(list[0].name);
        setCharFormDesc(list[0].description || '');
        // Load ref images for first character
        try {
          const imgs = await listCharRefImages(storyId, list[0].id);
          setCharRefImages(imgs);
        } catch { setCharRefImages([]); }
      } else {
        setCharFormName('');
        setCharFormDesc('');
        setCharRefImages([]);
      }
    } catch (err: any) {
      alert('加载角色卡失败: ' + (err.message || ''));
    }
  };

  const selectCharForEdit = async (ch: CharacterProfile) => {
    setEditingCharId(ch.id);
    setCharFormName(ch.name);
    setCharFormDesc(ch.description || '');
    try {
      const imgs = await listCharRefImages(charModalStoryId!, ch.id);
      setCharRefImages(imgs);
    } catch { setCharRefImages([]); }
  };

  const addCharacter = async () => {
    if (charModalStoryId === null) return;
    try {
      const created = await createCharacterProfile(charModalStoryId, '新角色', '');
      setCharacters(prev => [...prev, created]);
      setEditingCharId(created.id);
      setCharFormName(created.name);
      setCharFormDesc(created.description || '');
      setCharRefImages([]);
      setStoryCharFlags(prev => ({ ...prev, [charModalStoryId!]: true }));
    } catch (err: any) {
      alert('添加角色卡失败: ' + (err.message || ''));
    }
  };


  const openAssetGroupModal = async (storyId: number) => {
    const requestId = ++assetModalRequestRef.current;
    setAssetModalStoryId(storyId);
    setAssetGroups([]);
    setAssetSelectedKey('');
    setAssetModalLoading(true);
    try {
      const [groups, characters] = await Promise.all([
        getStoryAssetGroups(storyId),
        listCharacterProfiles(storyId),
      ]);
      if (assetModalRequestRef.current !== requestId) return;
      setAssetGroups(groups);
      setAllStoryCharacters(characters);

      // Load thumbnails for all characters
      const thumbnails: Record<number, string> = {};
      await Promise.all(characters.map(async (ch) => {
        try {
          const images = await listCharRefImages(storyId, ch.id);
          thumbnails[ch.id] = images.length > 0 ? refImageUrl(images[0].object_key) : '';
        } catch {
          thumbnails[ch.id] = '';
        }
      }));
      setCharThumbnails(thumbnails);
      if (groups.length > 0) {
        setAssetSelectedKey(String(groups[0].id));
        syncActiveAssetDraft(groups[0]);
      }
      setStoryRefFlags((prev) => ({ ...prev, [storyId]: groups.length > 0 }));
    } catch (err: any) {
      if (assetModalRequestRef.current !== requestId) return;
      alert('加载设定组失败: ' + (err.message || ''));
    } finally {
      if (assetModalRequestRef.current !== requestId) return;
      setAssetModalLoading(false);
    }
  };

  const selectAssetGroup = (group: AssetGroup) => {
    setAssetSelectedKey(String(group.id));
    syncActiveAssetDraft(group);
  };

  const saveAssetGroup = async () => {
    if (assetModalStoryId === null || !activeAssetGroup) return;
    setAssetModalSaving(true);
    try {
      const charIds = Array.from(assetDraftCharIds);
      if (activeAssetGroup.id) {
        const updated = await updateStoryAssetGroup(activeAssetGroup.id, {
          name: assetDraftName,
          description: assetDraftDesc,
          characterIds: charIds,
        });
        setAssetGroups((prev) => prev.map((g) => (g.id === updated.id ? updated : g)));
        syncActiveAssetDraft(updated);
      } else {
        const created = await createStoryAssetGroup(assetModalStoryId, assetDraftName, assetDraftDesc, charIds);
        setAssetGroups((prev) => [...prev, created]);
        setAssetSelectedKey(String(created.id));
        syncActiveAssetDraft(created);
      }
      // Refresh groups list
      const groups = await getStoryAssetGroups(assetModalStoryId);
      setAssetGroups(groups);
      if (activeAssetGroup.id) {
        const updated = groups.find((g) => g.id === activeAssetGroup.id);
        if (updated) syncActiveAssetDraft(updated);
      }
      setStoryRefFlags((prev) => ({ ...prev, [assetModalStoryId]: groups.length > 0 }));
    } catch (err: any) {
      alert('保存设定组失败: ' + (err.message || ''));
    } finally {
      setAssetModalSaving(false);
    }
  };

  const addAssetGroup = async () => {
    if (assetModalStoryId === null) return;
    try {
      const created = await createStoryAssetGroup(assetModalStoryId, '新设定组', '');
      const groups = await getStoryAssetGroups(assetModalStoryId);
      setAssetGroups(groups);
      setAssetSelectedKey(String(created.id));
      syncActiveAssetDraft(created);
      setStoryRefFlags((prev) => ({ ...prev, [assetModalStoryId]: groups.length > 0 }));
    } catch (err: any) {
      alert('新增设定组失败: ' + (err.message || ''));
    }
  };

  const removeAssetGroup = async () => {
    if (assetModalStoryId === null || !activeAssetGroup?.id) return;
    if (!confirm('删除"' + activeAssetGroup.name + '"?已选择该组的章节会恢复为未选择状态。')) return;
    try {
      await deleteStoryAssetGroup(assetModalStoryId, activeAssetGroup.id);
      const groups = await getStoryAssetGroups(assetModalStoryId);
      setAssetGroups(groups);
      if (groups.length > 0) {
        setAssetSelectedKey(String(groups[0].id));
        syncActiveAssetDraft(groups[0]);
      } else {
        setAssetSelectedKey('');
        setAssetDraftName('');
        setAssetDraftDesc('');
        setAssetDraftCharIds(new Set());
      }
      setStoryRefFlags((prev) => ({ ...prev, [assetModalStoryId]: groups.length > 0 }));
    } catch (err: any) {
      alert('删除设定组失败: ' + (err.message || ''));
    }
  };


  const handleRefUpload = async (file: File) => {
    if (refModalStoryId === null) return;
    setRefModalUploading(true);
    try {
      const reader = new FileReader();
      const b64 = await new Promise<string>((resolve) => {
        reader.onload = () => resolve((reader.result as string).split(',')[1]);
        reader.readAsDataURL(file);
      });
      const r = await addStoryRefImage(refModalStoryId, b64);
      setRefModalImages(r.images);
      setRefModalMax(r.max);
      setStoryRefFlags((prev) => ({ ...prev, [refModalStoryId]: r.images.length > 0 }));
    } catch (err: any) {
      alert('上传垫图失败: ' + (err.message || ''));
    } finally {
      setRefModalUploading(false);
    }
  };

  const handleRefDelete = async (filename: string) => {
    if (refModalStoryId === null) return;
    try {
      const r = await deleteStoryRefImage(refModalStoryId, filename);
      setRefModalImages(r.images);
      setStoryRefFlags((prev) => ({ ...prev, [refModalStoryId]: r.images.length > 0 }));
    } catch (err: any) {
      alert('删除垫图失败: ' + (err.message || ''));
    }
  };


  useEffect(() => {
    loadStories();
  }, []);

  const loadStories = async () => {
    try {
      const list = await listStories();
      setStories(list);
      const charFlags: Record<number, boolean> = {};
      await Promise.all(list.map(async (s) => {
        try {
          const chars = await listCharacterProfiles(s.id);
          charFlags[s.id] = chars.length > 0;
        } catch {
          charFlags[s.id] = false;
        }
      }));
      setStoryCharFlags(charFlags);
      const refFlags: Record<number, boolean> = {};
      await Promise.all(list.map(async (s) => {
        try {
          const groups = await getStoryAssetGroups(s.id);
          refFlags[s.id] = groups.length > 0;
        } catch {
          refFlags[s.id] = false;
        }
      }));
      setStoryRefFlags(refFlags);
    } finally {
      setLoading(false);
    }
  };

  const handleNewCoverFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      setNewCoverPreview(dataUrl);
      setNewCoverBase64(dataUrl.split(',')[1]);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  const handleCreate = async () => {
    const title = newTitle.trim() || '未命名故事';
    const desc = newDesc.trim();
    const s = await createStory(title, desc);
    setStories((prev) => [s, ...prev]);
    setStoryCharFlags((prev) => ({ ...prev, [s.id]: false }));
    setStoryRefFlags((prev) => ({ ...prev, [s.id]: false }));
    // Upload cover if selected
    if (newCoverBase64) {
      try {
        const coverPath = await uploadStoryCover(s.id, newCoverBase64);
        setStories((prev) =>
          prev.map((st) => (st.id === s.id ? { ...st, cover_image: coverPath } : st))
        );
      } catch (err: any) {
        console.error('Cover upload failed:', err);
      }
    }
    setShowNew(false);
    setNewTitle('');
    setNewDesc('');
    setNewCoverPreview(null);
    setNewCoverBase64(null);
  };

  const handleDelete = async (id: number) => {
    if (!confirm('确定要删除这本小说吗？所有章节、对话、漫画都将被永久删除！')) return;
    await deleteStory(id);
    setStories((prev) => prev.filter((s) => s.id !== id));
  };

  const handleExport = async (s: Story) => {
    setExportingStoryId(s.id);
    try {
      await exportStory(s);
    } catch (err: any) {
      alert(`导出失败: ${err.message}`);
    } finally {
      setExportingStoryId(null);
    }
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setImportingStory(true);
    setImportProgress({ message: '准备上传作品包...', percent: 0 });
    try {
      const imported = await importStoryPackage(file, (progress) => {
        setImportProgress({ message: progress.message, percent: progress.percent });
      });
      await loadStories();
      onSelectStory(imported);
    } catch (err: any) {
      alert(`导入失败: ${err.message}`);
    } finally {
      setImportingStory(false);
      setImportProgress(null);
    }
  };

  const startEdit = (s: Story) => {
    setEditingId(s.id);
    setEditTitle(s.title);
    setEditDesc(s.description || '');
  };

  const saveEdit = async () => {
    if (editingId === null) return;
    const updated = await updateStory(editingId, {
      title: editTitle.trim() || '未命名故事',
      description: editDesc.trim(),
    });
    setStories((prev) => prev.map((s) => (s.id === updated.id ? updated : s)));
    setEditingId(null);
  };

  const handleCoverClick = (storyId: number) => {
    setUploadingCover(storyId);
    fileRef.current?.click();
  };

  const handleCoverFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || uploadingCover === null) return;
    const storyId = uploadingCover;
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const b64 = (reader.result as string).split(',')[1];
        const coverPath = await uploadStoryCover(storyId, b64);
        setStories((prev) =>
          prev.map((s) => (s.id === storyId ? { ...s, cover_image: coverPath } : s))
        );
      } catch (err: any) {
        alert(`上传封面失败: ${err.message}`);
      } finally {
        setUploadingCover(null);
      }
    };
    reader.onerror = () => {
      alert('读取封面文件失败');
      setUploadingCover(null);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  if (loading) {
    return (
      <div className="h-screen bg-ink flex items-center justify-center text-cream-dim">
        <div className="flex flex-col items-center gap-3">
          <BookOpenText size={40} className="animate-pulse" />
          <span className="text-sm">加载中…</span>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-ink text-cream">
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleCoverFile}
      />
      <input
        ref={importFileRef}
        type="file"
        accept=".zip,application/zip"
        className="hidden"
        onChange={handleImportFile}
      />
      {importProgress && (
        <div className="fixed inset-x-0 top-4 z-[70] mx-auto w-[calc(100%-32px)] max-w-md rounded-xl border border-ink-border glass p-4 shadow-2xl backdrop-blur">
          <div className="mb-2 flex items-center gap-2 text-sm font-medium text-cream">
            <Loader2 size={16} className="animate-spin text-coral" />
            <span>{importProgress.message}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-ink-lighter">
            <div
              className="h-full rounded-full bg-violet-500 transition-all duration-200"
              style={{ width: `${importProgress.percent ?? 100}%` }}
            />
          </div>
          <p className="mt-2 text-xs text-cream-dim">
            上传完成后服务器还需要解压图片并写入数据库，大作品会多等一会儿。
          </p>
        </div>
      )}

      {/* Header */}
      <header className="border-b border-ink-border glass backdrop-blur-sm sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-coral flex items-center justify-center">
              <Sparkles size={18} className="text-cream" />
            </div>
            <div>
              <h1 className="text-lg font-bold tracking-tight">ArtVerse</h1>
              <p className="text-xs text-cream-dim">AI 小说 · 漫画工坊</p>
            </div>
          </div>
          <button
            onClick={() => importFileRef.current?.click()}
            disabled={importingStory}
            className="ml-auto mr-2 flex items-center gap-2 px-4 py-2.5 bg-ink-lighter hover:bg-ink-surface
                       text-cream text-sm font-medium rounded-lg transition-colors disabled:opacity-40"
            title="导入整本作品"
          >
            {importingStory ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
            导入
          </button>
          <button
            onClick={() => setShowNew(true)}
            className="flex items-center gap-2 px-4 py-2.5 bg-coral hover:bg-coral-light
                       text-cream text-sm font-medium rounded-lg transition-colors shadow-lg shadow-violet-900/30"
          >
            <Plus size={16} />
            新建小说
          </button>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-6xl mx-auto px-6 py-8">
        {/* New story modal */}
        {showNew && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-3 sm:p-4" onClick={() => setShowNew(false)}>
            <div className="bg-ink-light border border-ink-border rounded-xl w-full max-w-md shadow-2xl" onClick={(e) => e.stopPropagation()}>
              {/* Header */}
              <div className="flex items-center justify-between px-5 py-4 border-b border-ink-border">
                <h3 className="text-sm font-semibold flex items-center gap-2">
                  <Plus size={16} className="text-coral" />
                  创建新小说
                </h3>
                <button onClick={() => setShowNew(false)} className="p-1 text-cream-dim hover:text-cream-dim transition-colors">
                  <X size={16} />
                </button>
              </div>

              {/* Body */}
              <div className="p-5 space-y-4">
                <div>
                  <label className="block text-xs text-cream-dim mb-1.5">小说名称</label>
                  <input
                    autoFocus
                    placeholder="输入小说名称"
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
                    className="w-full px-3 py-2.5 bg-ink-lighter border border-ink-border rounded-lg text-sm placeholder-ink-muted focus:outline-none focus:ring-2 focus:border-coral focus:border-transparent"
                  />
                </div>

                <div>
                  <label className="block text-xs text-cream-dim mb-1.5">简短描述（可选）</label>
                  <textarea
                    placeholder="描述..."
                    value={newDesc}
                    onChange={(e) => setNewDesc(e.target.value)}
                    rows={2}
                    className="w-full px-3 py-2.5 bg-ink-lighter border border-ink-border rounded-lg text-sm placeholder-ink-muted focus:outline-none focus:ring-2 focus:border-coral focus:border-transparent resize-none"
                  />
                </div>

                {/* Cover upload */}
                <div>
                  <label className="block text-xs text-cream-dim mb-1.5">小说封面（可选）</label>
                  <div
                    onClick={() => { newCoverInputRef.current?.click(); }}
                    className="relative w-full h-40 bg-ink-lighter border border-dashed border-ink-muted hover:border-coral rounded-lg cursor-pointer flex flex-col items-center justify-center overflow-hidden transition-colors group"
                  >
                    {newCoverPreview ? (
                      <img
                        src={newCoverPreview}
                        alt="封面预览"
                        className="w-full h-full object-cover group-hover:opacity-80 transition-opacity"
                      />
                    ) : (
                      <div className="flex flex-col items-center gap-1.5 text-cream-dim group-hover:text-cream-dim transition-colors">
                        <ImagePlus size={28} />
                        <span className="text-xs">点击上传封面</span>
                      </div>
                    )}
                    {newCoverPreview && (
                      <div className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity bg-black/40">
                        <span className="text-xs text-cream font-medium">点击更换封面</span>
                      </div>
                    )}
                  </div>
                  {newCoverPreview && (
                    <button
                      onClick={(e) => { e.stopPropagation(); setNewCoverPreview(null); setNewCoverBase64(null); }}
                      className="mt-1.5 text-xs text-red-400 hover:text-red-300 transition-colors"
                    >
                      移除封面
                    </button>
                  )}
                  <input
                    ref={newCoverInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={handleNewCoverFile}
                  />
                </div>
              </div>

              {/* Footer */}
              <div className="px-5 py-3 border-t border-ink-border flex justify-end gap-2">
                <button
                  onClick={() => setShowNew(false)}
                  className="px-4 py-2 text-sm text-cream-dim hover:text-cream transition-colors"
                >
                  取消
                </button>
                <button
                  onClick={handleCreate}
                  className="px-5 py-2 bg-coral hover:bg-coral-light text-cream text-sm font-medium rounded-lg transition-colors"
                >
                  创建
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Empty state */}
        {stories.length === 0 && !showNew && (
          <div className="flex flex-col items-center justify-center py-32 text-cream-dim">
            <BookOpenText size={56} className="mb-4 text-ink-muted" />
            <p className="text-lg font-medium mb-2">还没有小说</p>
            <p className="text-sm mb-6">点击"新建小说"开始你的创作之旅</p>
            <button
              onClick={() => setShowNew(true)}
              className="px-5 py-2.5 bg-coral hover:bg-coral-light text-cream text-sm font-medium rounded-lg transition-colors"
            >
              <Plus size={16} className="inline mr-1" />
              新建小说
            </button>
          </div>
        )}

        {/* Story cards grid */}
        {stories.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
            {stories.map((s) => (
              <div
                key={s.id}
                className="group bg-ink-light border border-ink-border rounded-xl overflow-hidden
                           hover:border-violet-600/50 hover:shadow-xl hover:shadow-violet-900/10
                           transition-all duration-200"
              >
                {/* Cover */}
                <div
                  className="relative h-48 bg-gradient-to-br from-gray-800 to-gray-900 cursor-pointer overflow-hidden"
                  onClick={() => handleCoverClick(s.id)}
                >
                  {s.cover_image ? (
                    <img
                      src={mangaThumbUrl(s.cover_image, 720)!}
                      alt={s.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                      loading="lazy"
                      decoding="async"
                    />
                  ) : (
                    <div className="flex flex-col items-center justify-center h-full text-warm-gray group-hover:text-cream-dim transition-colors">
                      <ImagePlus size={32} className="mb-2" />
                      <span className="text-xs">点击上传封面</span>
                    </div>
                  )}
                  <div className="absolute inset-0 bg-transparent group-hover:bg-ink/60 transition-colors" />
                </div>

                {/* Info */}
                <div className="p-4">
                  {editingId === s.id ? (
                    <div className="space-y-2">
                      <input
                        autoFocus
                        value={editTitle}
                        onChange={(e) => setEditTitle(e.target.value)}
                        onKeyDown={(e) => e.key === 'Enter' && saveEdit()}
                        className="w-full px-3 py-1.5 bg-ink-lighter border border-ink-border rounded text-sm
                                   focus:outline-none focus:ring-2 focus:border-coral"
                      />
                      <textarea
                        value={editDesc}
                        onChange={(e) => setEditDesc(e.target.value)}
                        rows={2}
                        className="w-full px-3 py-1.5 bg-ink-lighter border border-ink-border rounded text-sm
                                   focus:outline-none focus:ring-2 focus:border-coral resize-none"
                        placeholder="简短描述（可选）"
                      />
                      <div className="flex gap-1 justify-end">
                        <button
                          onClick={() => setEditingId(null)}
                          className="p-1.5 text-cream-dim hover:text-cream-dim"
                        >
                          <X size={14} />
                        </button>
                        <button
                          onClick={saveEdit}
                          className="p-1.5 text-coral hover:text-coral-light"
                        >
                          <Check size={14} />
                        </button>
                      </div>
                    </div>
                  ) : (
                    <>
                      <h3 className="font-semibold text-sm mb-1 line-clamp-1">{s.title}</h3>
                      {s.description && (
                        <p className="text-xs text-cream-dim mb-3 line-clamp-2">{s.description}</p>
                      )}
                      {!s.description && <div className="mb-3" />}
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-warm-gray">
                          {new Date(s.created_at).toLocaleDateString('zh-CN')}
                        </span>
                        <div className="flex items-center gap-1">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              openCharModal(s.id);
                            }}
                            className={`p-1.5 transition-colors rounded ${
                              storyCharFlags[s.id]
                                ? 'text-emerald-400 hover:text-emerald-300'
                                : 'text-warm-gray hover:text-cream-dim'
                            }`}
                            title={storyCharFlags[s.id] ? '角色卡（已设定）' : '设置角色卡'}
                          >
                            <Users size={13} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              openAssetGroupModal(s.id);
                            }}
                            className={`p-1.5 transition-colors rounded ${
                              storyRefFlags[s.id]
                                ? 'text-amber-accent hover:text-amber-accent-light'
                                : 'text-warm-gray hover:text-cream-dim'
                            }`}
                            title={storyRefFlags[s.id] ? '设定组（已设定）' : '设置设定组'}
                          >
                            <Layers size={13} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleExport(s);
                            }}
                            disabled={exportingStoryId === s.id}
                            className="p-1.5 text-warm-gray hover:text-sky-300 transition-colors rounded disabled:opacity-40"
                            title="导出整本作品"
                          >
                            {exportingStoryId === s.id ? <Loader2 size={13} className="animate-spin" /> : <Download size={13} />}
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              startEdit(s);
                            }}
                            className="p-1.5 text-warm-gray hover:text-cream-dim transition-colors rounded"
                            title="编辑"
                          >
                            <Pencil size={13} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDelete(s.id);
                            }}
                            className="p-1.5 text-warm-gray hover:text-red-400 transition-colors rounded"
                            title="删除"
                          >
                            <Trash2 size={13} />
                          </button>
                          <button
                            onClick={() => onSelectStory(s)}
                            className="flex items-center gap-1 px-3 py-1.5 bg-coral/15 hover:bg-coral
                                       text-coral hover:text-cream text-xs font-medium rounded-lg transition-colors"
                          >
                            进入
                            <ChevronRight size={13} />
                          </button>
                        </div>
                      </div>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>

      {/* Asset groups modal */}
      {assetModalStoryId !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-3 sm:p-4" onClick={() => setAssetModalStoryId(null)}>
          <div className="bg-ink-light border border-ink-border rounded-xl w-full max-w-6xl h-[640px] max-h-[88vh] shadow-2xl flex flex-col" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-ink-border flex-shrink-0">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <Layers size={16} className="text-coral" />
                设置设定组
              </h3>
              <button onClick={() => setAssetModalStoryId(null)} className="p-1 text-cream-dim hover:text-cream-dim transition-colors">
                <X size={16} />
              </button>
            </div>

            {assetModalLoading ? (
              <div className="flex items-center justify-center flex-1 text-cream-dim">
                <Loader2 size={24} className="animate-spin" />
              </div>
            ) : (
              <div className="grid grid-cols-[220px_1fr] min-h-0 flex-1">
                {/* Left sidebar */}
                <div className="border-r border-ink-border p-3 overflow-y-auto">
                  <button
                    onClick={addAssetGroup}
                    className="w-full flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg bg-coral hover:bg-coral-light text-cream transition-colors"
                  >
                    <Plus size={13} />
                    添加设定组
                  </button>
                  <div className="mt-3 space-y-1">
                    {assetGroups.map((group) => {
                      const key = String(group.id);
                      const active = key === assetSelectedKey;
                      return (
                        <button
                          key={key}
                          onClick={() => selectAssetGroup(group)}
                          className={`w-full text-left px-3 py-2 rounded-lg border transition-colors ${
                            active
                              ? 'bg-coral/15 border-coral text-cream'
                              : 'bg-gray-950/40 border-ink-border text-cream-dim hover:text-cream hover:border-ink-border'
                          }`}
                        >
                          <span className="text-xs font-medium truncate block">{group.name}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Right panel */}
                <div className="min-h-0 flex flex-col">
                  {activeAssetGroup ? (
                    <>
                      <div className="flex-1 overflow-y-auto p-4 space-y-4">
                        {/* Name */}
                        <div>
                          <label className="block text-xs text-cream-dim mb-1.5">设定组名称</label>
                          <input
                            value={assetDraftName}
                            onChange={(e) => setAssetDraftName(e.target.value)}
                            className="w-full px-3 py-2 bg-ink-lighter border border-ink-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:border-coral"
                            placeholder="输入设定组名称"
                          />
                        </div>

                        {/* Description */}
                        <div>
                          <label className="block text-xs text-cream-dim mb-1.5">描述</label>
                          <textarea
                            value={assetDraftDesc}
                            onChange={(e) => setAssetDraftDesc(e.target.value)}
                            rows={4}
                            className="w-full bg-ink-lighter text-sm text-cream rounded-lg p-3 resize-none outline-none border border-ink-border focus:border-coral leading-relaxed"
                            placeholder="设定组描述..."
                          />
                        </div>

                        {/* Character selection */}
                        <div>
                          <label className="block text-xs text-cream-dim mb-2">
                            选择角色卡 ({assetDraftCharIds.size} 个已选)
                          </label>
                          {allStoryCharacters.length === 0 ? (
                            <p className="text-xs text-warm-gray py-4 text-center border border-dashed border-ink-border rounded-lg">
                              暂无角色卡，请先在小说卡片处添加角色卡
                            </p>
                          ) : (
                            <div className="grid grid-cols-4 gap-3">
                              {allStoryCharacters.map((ch) => {
                                const checked = assetDraftCharIds.has(ch.id);
                                const thumb = charThumbnails[ch.id];
                                const toggle = () => {
                                  const next = new Set(assetDraftCharIds);
                                  if (checked) next.delete(ch.id);
                                  else next.add(ch.id);
                                  setAssetDraftCharIds(next);
                                };
                                return (
                                  <div
                                    key={ch.id}
                                    onClick={toggle}
                                    className={`relative rounded-lg border-2 cursor-pointer transition-all overflow-hidden ${
                                      checked
                                        ? 'border-coral bg-violet-600/10'
                                        : 'border-ink-border hover:border-gray-500 bg-gray-950/40'
                                    }`}
                                  >
                                    {/* Checkmark */}
                                    {checked && (
                                      <div className="absolute top-1.5 right-1.5 z-10 w-5 h-5 rounded-full bg-violet-500 flex items-center justify-center shadow">
                                        <Check size={11} className="text-cream" />
                                      </div>
                                    )}
                                    {/* Thumbnail */}
                                    <div className="aspect-square bg-ink-lighter flex items-center justify-center">
                                      {thumb ? (
                                        <img
                                          src={thumb}
                                          alt={ch.name}
                                          className="w-full h-full object-contain"
                                          loading="lazy"
                                        />
                                      ) : (
                                        <Users size={24} className="text-warm-gray" />
                                      )}
                                    </div>
                                    {/* Name */}
                                    <div className="px-2 py-1.5 text-center">
                                      <span className="text-xs text-cream-dim truncate block">{ch.name}</span>
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Bottom actions */}
                      <div className="px-5 py-3 border-t border-ink-border flex-shrink-0">
                        <div className="flex justify-end gap-2">
                          {activeAssetGroup.id && (
                            <button
                              onClick={removeAssetGroup}
                              className="px-4 py-2 bg-red-950/40 border border-red-900 text-red-300 hover:bg-red-900/60 text-sm font-medium rounded-lg transition-colors"
                            >
                              删除此设定组
                            </button>
                          )}
                          <button
                            onClick={saveAssetGroup}
                            disabled={assetModalSaving}
                            className="px-5 py-2 bg-coral hover:bg-coral-light text-cream text-sm font-medium rounded-lg transition-colors disabled:opacity-40"
                          >
                            {assetModalSaving ? '保存中...' : '保存'}
                          </button>
                        </div>
                      </div>
                    </>
                  ) : (
                    <div className="flex items-center justify-center flex-1 text-cream-dim text-sm">
                      请选择或添加一个设定组
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Character card modal (profile-based) */}
      {charModalStoryId !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-3 sm:p-4" onClick={() => setCharModalStoryId(null)}>
          <div className="bg-ink-light border border-ink-border rounded-xl w-full max-w-6xl h-[640px] max-h-[88vh] shadow-2xl flex flex-col" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-ink-border flex-shrink-0">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <Users size={16} className="text-coral" />
        角色卡管理
              </h3>
              <button onClick={() => setCharModalStoryId(null)} className="p-1 text-cream-dim hover:text-cream-dim transition-colors">
                <X size={16} />
              </button>
            </div>
            <div className="flex flex-1 min-h-0">
              <div className="w-[250px] flex-shrink-0 border-r border-ink-border flex flex-col">
                <div className="p-3 border-b border-ink-border">
                  <button
                    onClick={addCharacter}
                    className="w-full flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg bg-coral hover:bg-coral-light text-cream transition-colors"
                  >
                    <Plus size={14} />
        添加角色卡
                  </button>
                </div>
                <div className="flex-1 overflow-y-auto [&::-webkit-scrollbar]:hidden" style={{ scrollbarWidth: "none" }}>
                  {characters.map(ch => (
                    <button
                      key={ch.id}
                      onClick={() => selectCharForEdit(ch)}
                      className={`w-full text-left px-4 py-3 text-sm transition-colors border-b border-ink-border/50 ${
                        editingCharId === ch.id ? 'bg-coral/15 text-coral-light border-l-2 border-l-violet-500' : 'text-cream-dim hover:bg-ink-lighter/50'
                      }`}
                    >
                      <div className="truncate font-medium">{ch.name}</div>
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex flex-col flex-1 min-h-0">
                <div className="flex-1 overflow-y-auto px-5 pt-5 [&::-webkit-scrollbar]:hidden" style={{ scrollbarWidth: "none" }}>
                  {editingCharId === null ? (
                    <div className="flex flex-col items-center justify-center h-full text-warm-gray">
                      <Users size={40} className="mb-3 opacity-30" />
                      <p className="text-sm">选择一个角色卡或点击添加</p>
                    </div>
                  ) : (
                    <>
                      <div className="space-y-5">
                        <div>
                          <label className="block text-xs font-medium text-cream-dim mb-1.5">角色名称</label>
                          <input
                            value={charFormName}
                            onChange={e => setCharFormName(e.target.value)}
                            className="w-full bg-ink-lighter text-sm text-cream rounded-lg px-3 py-2 outline-none border border-ink-border focus:border-coral"
                            placeholder="角色名称"
                          />
                        </div>
                        <div>
                          <label className="block text-xs font-medium text-cream-dim mb-1.5">角色描述</label>
                          <textarea
                            value={charFormDesc}
                            onChange={e => setCharFormDesc(e.target.value)}
                            className="w-full bg-ink-lighter text-sm text-cream rounded-lg p-3 resize-none outline-none border border-ink-border focus:border-coral leading-relaxed"
                            rows={5}
                            placeholder="描述角色的性格、外貌、背景等..."
                          />
                        </div>
                        <div>
                          <div className="flex items-center justify-between mb-2">
                            <label className="text-xs font-medium text-cream-dim">人物参考图</label>
                            <button
                              onClick={() => charFileRef.current?.click()}
                              disabled={charRefUploading || charRefImages.length >= 5}
                              className="flex items-center gap-1 px-2.5 py-1.5 text-xs rounded-lg bg-coral hover:bg-coral-light text-cream disabled:opacity-40 transition-colors"
                            >
                              {charRefUploading ? <Loader2 size={12} className="animate-spin" /> : <ImagePlus size={12} />}
                              上传图片
                            </button>
                          </div>
                          <input
                            ref={charFileRef}
                            type="file"
                            accept="image/*"
                            className="hidden"
                            onChange={e => {
                              const file = e.target.files?.[0];
                              if (!file) return;
                              const reader = new FileReader();
                              reader.onload = () => {
                                const b64 = (reader.result as string).split(',')[1];
                                setCharRefUploading(true);
                                addCharRefImage(charModalStoryId!, editingCharId!, b64)
                                  .then(img => setCharRefImages(prev => [...prev, img]))
                                  .catch(err => alert('上传失败: ' + err.message))
                                  .finally(() => setCharRefUploading(false));
                              };
                              reader.readAsDataURL(file);
                              e.target.value = '';
                            }}
                          />
                          {charRefImages.length === 0 ? (
                            <div className="w-full flex flex-col items-center justify-center aspect-[5/1] border-2 border-dashed border-ink-border rounded-lg text-warm-gray text-xs">
                              <ImagePlus size={20} className="mb-1 opacity-40" />
                              暂无参考图，点击上方按钮上传（最多5张）
                            </div>
                          ) : (
                            <div className="grid grid-cols-5 gap-2">
                              {charRefImages.map(img => (
                                <div key={img.filename} className="relative group aspect-square rounded-lg overflow-hidden border border-ink-border bg-ink">
                                  <img
                                    src={refImageUrl(img.object_key)}
                                    alt={img.filename}
                                    className="w-full h-full object-cover"
                                    loading="lazy"
                                  />
                                  <button
                                    onClick={() => {
                                      deleteCharRefImage(charModalStoryId!, editingCharId!, img.filename)
                                        .then(() => setCharRefImages(prev => prev.filter(x => x.filename !== img.filename)))
                                        .catch(err => alert('删除失败: ' + err.message));
                                    }}
                                    className="absolute top-1 right-1 p-1 rounded-md bg-red-600 hover:bg-red-500 text-cream shadow-lg transition-colors"
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
                      <div className="flex-shrink-0 px-5 py-3 border-t border-ink-border mt-auto">
                        <div className="flex justify-end gap-2">
                          <button
                            onClick={async () => {
                              const ch = characters.find(c => c.id === editingCharId);
                              if (!confirm('确定删除角色"' + (ch?.name || '') + '"吗？')) return;
                              try {
                                await deleteCharacterProfile(charModalStoryId!, editingCharId!);
                                setCharacters(prev => prev.filter(x => x.id !== editingCharId));
                                setEditingCharId(null);
                                setStoryCharFlags(prev => ({ ...prev, [charModalStoryId!]: characters.length <= 1 ? false : true }));
                              } catch (err: any) {
                                alert('删除失败: ' + err.message);
                              }
                            }}
                            className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-lg bg-red-600/20 border border-red-600/30 text-red-400 hover:bg-red-600 hover:text-cream transition-colors"
                          >
                            <Trash2 size={12} />
                            删除此角色
                          </button>
                          <button
                            onClick={async () => {
                              if (!charFormName.trim()) { alert('请输入角色名称'); return; }
                              setCharFormSaving(true);
                              try {
                                const updated = await updateCharacterProfile(charModalStoryId!, editingCharId!, charFormName, charFormDesc);
                                setCharacters(prev => prev.map(ch => ch.id === editingCharId ? updated : ch));
                              } catch (err: any) {
                                alert('保存失败: ' + err.message);
                              } finally {
                                setCharFormSaving(false);
                              }
                            }}
                            disabled={charFormSaving}
                            className="px-5 py-2 bg-coral hover:bg-coral-light text-cream text-sm font-medium rounded-lg transition-colors disabled:opacity-40"
                          >
                            {charFormSaving ? '保存中…' : '保存'}
                          </button>
                        </div>
                      </div>
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>

      )}

      {/* Ref images modal (multi) */}
      {refModalStoryId !== null && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-3 sm:p-4"
          onClick={() => setRefModalStoryId(null)}
        >
          <div
            className="bg-ink-light border border-ink-border rounded-xl w-full max-w-2xl shadow-2xl flex flex-col max-h-[85vh]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-ink-border">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <ImagePlus size={16} className="text-amber-accent" />
                全局默认垫图
                <span className="text-xs font-normal text-cream-dim">
                  {refModalImages.length}/{refModalMax} 张
                </span>
              </h3>
              <button
                onClick={() => setRefModalStoryId(null)}
                className="p-1 text-cream-dim hover:text-cream-dim transition-colors"
              >
                <X size={16} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <p className="text-xs text-cream-dim mb-4 leading-relaxed">
                上传默认垫图（最多 {refModalMax} 张），所有章节默认继承，用作人物外貌和画面参考。章节内也可单独覆盖。
              </p>
              {refModalLoading ? (
                <div className="flex items-center justify-center py-12 text-cream-dim">
                  <Loader2 size={24} className="animate-spin" />
                </div>
              ) : refModalImages.length === 0 ? (
                <button
                  onClick={() => refModalFileRef.current?.click()}
                  disabled={refModalUploading}
                  className="w-full flex flex-col items-center justify-center py-12 border-2 border-dashed border-ink-border
                             hover:border-amber-accent/40 rounded-lg text-cream-dim hover:text-cream-dim transition-colors
                             disabled:opacity-40 cursor-pointer"
                >
                  {refModalUploading ? (
                    <Loader2 size={28} className="animate-spin mb-2" />
                  ) : (
                    <ImagePlus size={28} className="mb-2" />
                  )}
                  <span className="text-sm">{refModalUploading ? '上传中…' : '点击上传第一张垫图'}</span>
                </button>
              ) : (
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                  {refModalImages.map((img) => (
                    <div
                      key={img.filename}
                      className="relative group aspect-square rounded-lg overflow-hidden border border-ink-border bg-ink"
                    >
                      <img
                        src={refImageUrl(img.image_path)}
                        alt={img.filename}
                        className="w-full h-full object-cover"
                        loading="lazy"
                        decoding="async"
                      />
                      <div className="absolute inset-0 bg-transparent group-hover:bg-black/40 transition-colors flex items-end p-2 pointer-events-none">
                        <span className="text-[10px] text-white/80 bg-black/60 px-1.5 py-0.5 rounded">
                          {img.size_kb} KB
                        </span>
                      </div>
                      <button
                        onClick={() => handleRefDelete(img.filename)}
                        className="absolute top-1.5 right-1.5 p-1 rounded-md bg-red-600 hover:bg-red-500 text-cream shadow-lg transition-colors"
                        title="删除"
                      >
                        <Trash2 size={12} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <input
                ref={refModalFileRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) handleRefUpload(file);
                  e.target.value = '';
                }}
              />
            </div>
            <div className="flex justify-between items-center px-5 py-3 border-t border-ink-border">
              <button
                onClick={() => refModalFileRef.current?.click()}
                disabled={refModalUploading || refModalImages.length >= refModalMax}
                className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-lg
                           bg-coral hover:bg-coral-light text-cream
                           disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                title={refModalImages.length >= refModalMax ? `已达上限 ${refModalMax} 张` : '上传一张垫图'}
              >
                {refModalUploading ? <Loader2 size={13} className="animate-spin" /> : <ImagePlus size={13} />}
                {refModalImages.length === 0 ? '上传垫图' : '添加一张'}
              </button>
              <button
                onClick={() => setRefModalStoryId(null)}
                className="px-4 py-2 text-sm text-cream-dim hover:text-cream transition-colors"
              >
                关闭
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
