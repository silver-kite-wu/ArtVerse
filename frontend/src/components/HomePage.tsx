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
  type Story,
  type RefImage,
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

import ImageEditor from './ImageEditor';
import AssetGroupManagerModal from './AssetGroupManagerModal';
import OverlayPortal from '../ui/OverlayPortal';

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

  // Edit mode
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editDesc, setEditDesc] = useState('');

  const importFileRef = useRef<HTMLInputElement>(null);
  const [importingStory, setImportingStory] = useState(false);
  const [, setUploadingCover] = useState<number | null>(null);
  const [importProgress, setImportProgress] = useState<{ message: string; percent?: number } | null>(null);
  const [exportingStoryId, setExportingStoryId] = useState<number | null>(null);

  // Image editor state
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorImageDataUrl, setEditorImageDataUrl] = useState<string | null>(null);
  // 'new' = from new story modal, 'existing' = from story card cover click
  const [editorMode, setEditorMode] = useState<'new' | 'existing' | null>(null);
  const [editorStoryId, setEditorStoryId] = useState<number | null>(null);
  const editorFileInputRef = useRef<HTMLInputElement>(null);

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
  const [refModalUploading, setRefModalUploading] = useState(false);
  const [storyRefFlags, setStoryRefFlags] = useState<Record<number, boolean>>({});
  const refModalFileRef = useRef<HTMLInputElement>(null);

  // Story global asset groups
  const [assetModalStoryId, setAssetModalStoryId] = useState<number | null>(null);

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


  const openAssetGroupModal = (storyId: number) => {
    setAssetModalStoryId(storyId);
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

  // Editor handler: called when explicit edit trigger is used (non-modal cover input)
  const handleEditorFileTrigger = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      setEditorImageDataUrl(dataUrl);
      setEditorOpen(true);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  const handleEditorConfirm = (croppedBase64: string) => {
    const dataUrl = 'data:image/jpeg;base64,' + croppedBase64;
    if (editorMode === 'new') {
      // New story modal
      setNewCoverPreview(dataUrl);
      setNewCoverBase64(croppedBase64);
    } else if (editorMode === 'existing' && editorStoryId !== null) {
      // Existing story cover replace
      uploadStoryCover(editorStoryId, croppedBase64)
        .then((coverPath) => {
          setStories((prev) =>
            prev.map((s) => (s.id === editorStoryId ? { ...s, cover_image: coverPath } : s))
          );
        })
        .catch((err: any) => {
          alert(`上传封面失败: ${err.message}`);
        })
        .finally(() => {
          setUploadingCover(null);
        });
    }
    closeEditor();
  };

  const closeEditor = () => {
    setEditorOpen(false);
    setEditorImageDataUrl(null);
    setEditorMode(null);
    setEditorStoryId(null);
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
    setEditorMode('existing');
    setEditorStoryId(storyId);
    setUploadingCover(storyId);
    editorFileInputRef.current?.click();
  };

  if (loading) {
    return (
      <div className="h-dvh bg-bg-base flex items-center justify-center text-text-secondary">
        <div className="flex flex-col items-center gap-3">
          <BookOpenText size={40} className="animate-pulse text-accent/40" />
          <span className="text-sm">加载中…</span>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto bg-bg-base text-text-primary">
      <input
        ref={editorFileInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleEditorFileTrigger}
      />
      <input
        ref={importFileRef}
        type="file"
        accept=".zip,application/zip"
        className="hidden"
        onChange={handleImportFile}
      />

      {editorOpen && editorImageDataUrl && (
        <ImageEditor
          imageDataUrl={editorImageDataUrl}
          onConfirm={handleEditorConfirm}
          onCancel={closeEditor}
        />
      )}
      {importProgress && (
        <OverlayPortal>
          <div className="absolute inset-x-0 top-4 z-[70] mx-auto w-[calc(100%-2rem)] max-w-md rounded-xl border border-border glass p-4 backdrop-blur">
          <div className="mb-2 flex items-center gap-2 text-sm font-medium text-text-primary">
            <Loader2 size={16} className="animate-spin text-accent" />
            <span>{importProgress.message}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-bg-raised">
            <div
              className="h-full rounded-full bg-accent transition-all duration-200"
              style={{ width: `${importProgress.percent ?? 100}%` }}
            />
          </div>
          <p className="mt-2 text-xs text-text-secondary">
            上传完成后服务器还需要解压图片并写入数据库，大作品会多等一会儿。
          </p>
          </div>
        </OverlayPortal>
      )}

      {/* Header */}
      <header className="sticky top-0 z-10 border-b border-border bg-bg-raised/95 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-accent">
              <Sparkles size={18} className="text-white" />
            </div>
            <div>
              <h1 className="text-sm font-semibold text-text-primary sm:text-base">故事工作区</h1>
              <p className="text-[0.6875rem] text-text-muted">管理故事、角色与创作设定</p>
            </div>
          </div>
          <button
            onClick={() => importFileRef.current?.click()}
            disabled={importingStory}
            className="ml-auto mr-2 flex h-9 items-center gap-2 rounded-lg border border-border bg-bg-raised px-3
                       text-text-secondary text-xs font-medium transition-colors hover:bg-bg-surface disabled:opacity-40 sm:px-4 sm:text-sm"
            title="导入整本作品"
          >
            {importingStory ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
            <span className="hidden sm:inline">导入</span>
          </button>
          <button
            onClick={() => setShowNew(true)}
            className="flex h-9 items-center gap-2 rounded-lg bg-accent px-3 text-xs font-medium
                       text-white transition-colors hover:bg-accent-hover sm:px-4 sm:text-sm"
          >
            <Plus size={16} />
            <span className="hidden sm:inline">新建小说</span><span className="sm:hidden">新建</span>
          </button>
        </div>
      </header>

      {/* Content */}
      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 sm:py-8">
        {/* New story modal */}
        {showNew && (
          <OverlayPortal>
            <div className="absolute inset-0 z-50 flex items-center justify-center bg-bg-base/30 backdrop-blur-sm p-3 sm:p-4" onClick={() => setShowNew(false)}>
            <div className="bg-bg-surface border border-border rounded-xl w-full max-w-md shadow-2xl" onClick={(e) => e.stopPropagation()}>
              {/* Header */}
              <div className="flex items-center justify-between px-5 py-4 border-b border-border">
                <h3 className="text-sm font-semibold flex items-center gap-2">
                  <Plus size={16} className="text-accent" />
                  创建新小说
                </h3>
                <button onClick={() => setShowNew(false)} className="p-1 text-text-secondary hover:text-text-secondary transition-colors">
                  <X size={16} />
                </button>
              </div>

              {/* Body */}
              <div className="p-5 space-y-4">
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">小说名称</label>
                  <input
                    autoFocus
                    placeholder="输入小说名称"
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
                    className="w-full px-3 py-2.5 bg-bg-raised border border-border rounded-lg text-sm placeholder-text-muted focus:outline-none focus:ring-2 focus:border-accent focus:border-transparent"
                  />
                </div>

                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">简短描述（可选）</label>
                  <textarea
                    placeholder="描述..."
                    value={newDesc}
                    onChange={(e) => setNewDesc(e.target.value)}
                    rows={2}
                    className="w-full px-3 py-2.5 bg-bg-raised border border-border rounded-lg text-sm placeholder-text-muted focus:outline-none focus:ring-2 focus:border-accent focus:border-transparent resize-none"
                  />
                </div>

                {/* Cover upload */}
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">小说封面（可选）</label>
                  <div
                    onClick={() => { setEditorMode('new'); editorFileInputRef.current?.click(); }}
                    className="relative w-full aspect-[3/4] bg-bg-raised border border-dashed border-border hover:border-accent rounded-lg cursor-pointer flex flex-col items-center justify-center overflow-hidden transition-colors group"
                  >
                    {newCoverPreview ? (
                      <img
                        src={newCoverPreview}
                        alt="封面预览"
                        className="w-full h-full object-cover group-hover:opacity-80 transition-opacity"
                      />
                    ) : (
                      <div className="flex flex-col items-center gap-1.5 text-text-secondary group-hover:text-text-secondary transition-colors">
                        <ImagePlus size={28} />
                        <span className="text-xs">点击上传封面</span>
                      </div>
                    )}
                    {newCoverPreview && (
                      <div className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity bg-bg-base/20">
                        <span className="text-xs text-text-primary font-medium">点击更换封面</span>
                      </div>
                    )}
                  </div>
                  {newCoverPreview && (
                    <button
                      onClick={(e) => { e.stopPropagation(); setNewCoverPreview(null); setNewCoverBase64(null); }}
                      className="mt-1.5 text-xs text-accent hover:text-accent-hover transition-colors"
                    >
                      移除封面
                    </button>
                  )}
                </div>
              </div>

              {/* Footer */}
              <div className="px-5 py-3 border-t border-border flex justify-end gap-2">
                <button
                  onClick={() => setShowNew(false)}
                  className="px-4 py-2 text-sm text-text-secondary hover:text-text-primary transition-colors"
                >
                  取消
                </button>
                <button
                  onClick={handleCreate}
                  className="px-5 py-2 bg-accent hover:bg-accent-soft text-text-primary text-sm font-medium rounded-lg transition-colors"
                >
                  创建
                </button>
              </div>
            </div>
            </div>
          </OverlayPortal>
        )}

        {/* Empty state */}
        {stories.length === 0 && !showNew && (
          <div className="mx-auto flex max-w-2xl flex-col items-center justify-center py-20 text-center text-text-secondary sm:py-28">
            <div className="mb-5 flex h-14 w-14 items-center justify-center rounded-lg border border-border bg-bg-raised shadow-sm">
              <BookOpenText size={24} className="text-accent" />
            </div>
            <p className="mb-2 font-display text-2xl font-semibold text-text-primary">创建你的第一部故事</p>
            <p className="mb-7 max-w-md text-sm leading-6">从故事名称开始，随后完善人物、章节和视觉设定。</p>
            <button
              onClick={() => setShowNew(true)}
              className="flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
            >
              <Plus size={16} /> 新建故事
            </button>
          </div>
        )}

        {/* Story cards grid */}
        {stories.length > 0 && (
          <>
            <div className="mb-5 flex items-end justify-between">
              <div>
                <h2 className="font-display text-2xl font-semibold text-text-primary">你的故事</h2>
                <p className="mt-1 text-xs text-text-muted">共 {stories.length} 部作品</p>
              </div>
            </div>
            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
            {stories.map((s) => (
              <div
                key={s.id}
                className="group panel-frame flex min-w-0 overflow-hidden transition-all duration-200 hover:-translate-y-0.5 sm:block"
              >
                {/* Cover */}
                <div
                  className="relative aspect-[3/4] w-[120px] shrink-0 cursor-pointer overflow-hidden bg-bg-surface sm:w-auto"
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
                    <div className="flex flex-col items-center justify-center h-full text-text-muted group-hover:text-text-secondary transition-colors">
                      <ImagePlus size={32} className="mb-2" />
                      <span className="text-xs">点击上传封面</span>
                    </div>
                  )}
                  <div className="absolute inset-0 bg-transparent group-hover:bg-bg-base/20 transition-colors" />
                </div>

                {/* Info */}
                <div className="min-w-0 flex-1 p-4">
                  {editingId === s.id ? (
                    <div className="space-y-2">
                      <input
                        autoFocus
                        value={editTitle}
                        onChange={(e) => setEditTitle(e.target.value)}
                        onKeyDown={(e) => e.key === 'Enter' && saveEdit()}
                        className="w-full px-3 py-1.5 bg-bg-surface border border-border rounded-lg  text-sm text-text-primary
                                   focus:outline-none focus:border-accent"
                      />
                      <textarea
                        value={editDesc}
                        onChange={(e) => setEditDesc(e.target.value)}
                        rows={2}
                        className="w-full px-3 py-1.5 bg-bg-surface border border-border rounded-lg  text-sm text-text-primary
                                   focus:outline-none focus:border-accent resize-none"
                        placeholder="简短描述（可选）"
                      />
                      <div className="flex gap-1 justify-end">
                        <button
                          onClick={() => setEditingId(null)}
                          className="p-1.5 text-text-secondary hover:text-text-secondary"
                        >
                          <X size={14} />
                        </button>
                        <button
                          onClick={saveEdit}
                          className="p-1.5 text-accent hover:text-accent-hover"
                        >
                          <Check size={14} />
                        </button>
                      </div>
                    </div>
                  ) : (
                    <>
                      <h3 className="font-semibold text-sm mb-1 line-clamp-1 text-text-primary">{s.title}</h3>
                      {s.description && (
                        <p className="text-xs text-text-secondary mb-3 line-clamp-2">{s.description}</p>
                      )}
                      {!s.description && <div className="mb-3" />}
                      <div className="flex flex-wrap items-end justify-between gap-2">
                        <span className="text-xs text-text-muted">
                          {new Date(s.created_at).toLocaleDateString('zh-CN')}
                        </span>
                        <div className="flex flex-wrap items-center justify-end gap-0.5">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              openCharModal(s.id);
                            }}
                            className={`p-1.5 transition-colors rounded-lg  ${
                              storyCharFlags[s.id]
                                ? 'text-success hover:text-success/80'
                                : 'text-text-muted hover:text-text-secondary'
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
                            className={`p-1.5 transition-colors rounded-lg  ${
                              storyRefFlags[s.id]
                                ? 'text-accent-secondary hover:text-accent-secondary/80'
                                : 'text-text-muted hover:text-text-secondary'
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
                            className="p-1.5 text-text-muted hover:text-accent-tertiary transition-colors rounded-lg  disabled:opacity-40"
                            title="导出整本作品"
                          >
                            {exportingStoryId === s.id ? <Loader2 size={13} className="animate-spin" /> : <Download size={13} />}
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              startEdit(s);
                            }}
                            className="p-1.5 text-text-muted hover:text-text-secondary transition-colors rounded"
                            title="编辑"
                          >
                            <Pencil size={13} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDelete(s.id);
                            }}
                            className="p-1.5 text-text-muted hover:text-accent transition-colors rounded"
                            title="删除"
                          >
                            <Trash2 size={13} />
                          </button>
                          <button
                            onClick={() => onSelectStory(s)}
                            className="flex items-center gap-1 px-3 py-1.5 bg-accent-muted/30 hover:bg-accent
                                       text-accent hover:text-white text-xs font-medium rounded-lg transition-colors"
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
          </>
        )}
      </main>

      {/* Asset groups modal */}
      {assetModalStoryId !== null && (
        <AssetGroupManagerModal
          key={assetModalStoryId}
          storyId={assetModalStoryId}
          onClose={() => setAssetModalStoryId(null)}
          onGroupsChange={(groups) => {
            setStoryRefFlags((previous) => ({ ...previous, [assetModalStoryId]: groups.length > 0 }));
          }}
        />
      )}

      {/* Character card modal (profile-based) */}
      {charModalStoryId !== null && (
        <OverlayPortal>
          <div className="absolute inset-0 z-50 flex items-center justify-center bg-bg-base/30 backdrop-blur-sm p-3 sm:p-4" onClick={() => setCharModalStoryId(null)}>
          <div className="bg-bg-surface border border-border rounded-xl w-full max-w-6xl h-[min(640px,88dvh)] flex flex-col" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-border flex-shrink-0">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <Users size={16} className="text-accent" />
        角色卡管理
              </h3>
              <button onClick={() => setCharModalStoryId(null)} className="p-1 text-text-secondary hover:text-text-secondary transition-colors">
                <X size={16} />
              </button>
            </div>
            <div className="flex flex-col sm:flex-row flex-1 min-h-0">
              <div className="w-full sm:w-[15.5rem] max-h-44 sm:max-h-none shrink-0 border-b sm:border-b-0 sm:border-r border-border flex flex-col">
                <div className="p-3 border-b border-border">
                  <button
                    onClick={addCharacter}
                    className="w-full flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg bg-accent hover:bg-accent-soft text-text-primary transition-colors"
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
                      className={`w-full text-left px-4 py-3 text-sm transition-colors border-b border-border/50 ${
                        editingCharId === ch.id ? 'bg-accent/15 text-accent-hover border-l-2 border-l-accent' : 'text-text-secondary hover:bg-bg-raised/50'
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
                    <div className="flex flex-col items-center justify-center h-full text-text-muted">
                      <Users size={40} className="mb-3 opacity-30" />
                      <p className="text-sm">选择一个角色卡或点击添加</p>
                    </div>
                  ) : (
                    <>
                      <div className="space-y-5">
                        <div>
                          <label className="block text-xs font-medium text-text-secondary mb-1.5">角色名称</label>
                          <input
                            value={charFormName}
                            onChange={e => setCharFormName(e.target.value)}
                            className="w-full bg-bg-raised text-sm text-text-primary rounded-lg px-3 py-2 outline-none border border-border focus:border-accent"
                            placeholder="角色名称"
                          />
                        </div>
                        <div>
                          <label className="block text-xs font-medium text-text-secondary mb-1.5">角色描述</label>
                          <textarea
                            value={charFormDesc}
                            onChange={e => setCharFormDesc(e.target.value)}
                            className="w-full bg-bg-raised text-sm text-text-primary rounded-lg p-3 resize-none outline-none border border-border focus:border-accent leading-relaxed"
                            rows={5}
                            placeholder="描述角色的性格、外貌、背景等..."
                          />
                        </div>
                        <div>
                          <div className="flex items-center justify-between mb-2">
                            <label className="text-xs font-medium text-text-secondary">人物参考图</label>
                            <button
                              onClick={() => charFileRef.current?.click()}
                              disabled={charRefUploading || charRefImages.length >= 5}
                              className="flex items-center gap-1 px-2.5 py-1.5 text-xs rounded-lg bg-accent hover:bg-accent-soft text-text-primary disabled:opacity-40 transition-colors"
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
                            <div className="w-full flex flex-col items-center justify-center aspect-[5/1] border-2 border-dashed border-border rounded-lg text-text-muted text-xs">
                              <ImagePlus size={20} className="mb-1 opacity-40" />
                              暂无参考图，点击上方按钮上传（最多5张）
                            </div>
                          ) : (
                            <div className="grid grid-cols-5 gap-2">
                              {charRefImages.map(img => (
                                <div key={img.filename} className="relative group aspect-square rounded-lg overflow-hidden border border-border bg-bg-base">
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
                                    className="absolute top-1 right-1 p-1 rounded-lg bg-accent hover:bg-accent-hover text-white transition-colors"
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
                      <div className="flex-shrink-0 px-5 py-3 border-t border-border mt-auto">
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
                            className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-muted/30 border border-accent/20 text-accent hover:bg-accent hover:text-white transition-colors"
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
                            className="px-5 py-2 bg-accent hover:bg-accent-soft text-text-primary text-sm font-medium rounded-lg transition-colors disabled:opacity-40"
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
        </OverlayPortal>
      )}

      {/* Ref images modal (multi) */}
      {refModalStoryId !== null && (
        <OverlayPortal>
        <div
          className="absolute inset-0 z-50 flex items-center justify-center bg-bg-base/30 backdrop-blur-sm p-3 sm:p-4"
          onClick={() => setRefModalStoryId(null)}
        >
          <div
            className="bg-bg-surface border border-border rounded-xl w-full max-w-2xl flex flex-col max-h-[85vh]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-border">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <ImagePlus size={16} className="text-accent-secondary" />
                全局默认垫图
                <span className="text-xs font-normal text-text-secondary">
                  {refModalImages.length}/{refModalMax} 张
                </span>
              </h3>
              <button
                onClick={() => setRefModalStoryId(null)}
                className="p-1 text-text-secondary hover:text-text-secondary transition-colors"
              >
                <X size={16} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <p className="text-xs text-text-secondary mb-4 leading-relaxed">
                上传默认垫图（最多 {refModalMax} 张），所有章节默认继承，用作人物外貌和画面参考。章节内也可单独覆盖。
              </p>
              {refModalImages.length === 0 ? (
                <button
                  onClick={() => refModalFileRef.current?.click()}
                  disabled={refModalUploading}
                  className="w-full flex flex-col items-center justify-center py-12 border-2 border-dashed border-border
                             hover:border-amber-accent/40 rounded-lg text-text-secondary hover:text-text-secondary transition-colors
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
                      className="relative group aspect-square rounded-lg overflow-hidden border border-border bg-bg-base"
                    >
                      <img
                        src={refImageUrl(img.image_path)}
                        alt={img.filename}
                        className="w-full h-full object-cover"
                        loading="lazy"
                        decoding="async"
                      />
                      <div className="absolute inset-0 bg-transparent group-hover:bg-bg-base/20 transition-colors flex items-end p-2 pointer-events-none">
                        <span className="text-[0.625rem] text-white bg-bg-base/60 px-1.5 py-0.5 rounded">
                          {img.size_kb} KB
                        </span>
                      </div>
                      <button
                        onClick={() => handleRefDelete(img.filename)}
                        className="absolute top-1.5 right-1.5 p-1 rounded-lg bg-accent hover:bg-accent-hover text-white transition-colors"
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
            <div className="flex justify-between items-center px-5 py-3 border-t border-border">
              <button
                onClick={() => refModalFileRef.current?.click()}
                disabled={refModalUploading || refModalImages.length >= refModalMax}
                className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-lg
                           bg-accent hover:bg-accent-soft text-text-primary
                           disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                title={refModalImages.length >= refModalMax ? `已达上限 ${refModalMax} 张` : '上传一张垫图'}
              >
                {refModalUploading ? <Loader2 size={13} className="animate-spin" /> : <ImagePlus size={13} />}
                {refModalImages.length === 0 ? '上传垫图' : '添加一张'}
              </button>
              <button
                onClick={() => setRefModalStoryId(null)}
                className="px-4 py-2 text-sm text-text-secondary hover:text-text-primary transition-colors"
              >
                关闭
              </button>
            </div>
          </div>
        </div>
        </OverlayPortal>
      )}
    </div>
  );
}
