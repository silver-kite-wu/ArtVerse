import { useEffect, useRef, useState } from 'react';
import { Check, Layers, Loader2, Plus, Users, X } from 'lucide-react';
import OverlayPortal from '../ui/OverlayPortal';
import {
  createStoryAssetGroup,
  deleteStoryAssetGroup,
  getStoryAssetGroups,
  listCharacterProfiles,
  listCharRefImages,
  refImageUrl,
  updateStoryAssetGroup,
  type AssetGroup,
  type AssetGroupCharacter,
  type CharacterProfile,
} from '../api';

interface Props {
  storyId: number;
  onClose: () => void;
  onGroupsChange?: (groups: AssetGroup[]) => void;
}

export default function AssetGroupManagerModal({ storyId, onClose, onGroupsChange }: Props) {
  const [groups, setGroups] = useState<AssetGroup[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [draftName, setDraftName] = useState('');
  const [draftDescription, setDraftDescription] = useState('');
  const [draftCharacterIds, setDraftCharacterIds] = useState<Set<number>>(new Set());
  const [characters, setCharacters] = useState<CharacterProfile[]>([]);
  const [characterThumbnails, setCharacterThumbnails] = useState<Record<number, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const requestRef = useRef(0);
  const onGroupsChangeRef = useRef(onGroupsChange);

  const activeGroup = groups.find((group) => String(group.id) === selectedKey);

  useEffect(() => {
    onGroupsChangeRef.current = onGroupsChange;
  }, [onGroupsChange]);

  const syncDraft = (group: AssetGroup | undefined) => {
    setDraftName(group?.name ?? '');
    setDraftDescription(group?.description ?? '');
    setDraftCharacterIds(new Set((group?.characters ?? []).map((character: AssetGroupCharacter) => character.id)));
  };

  const applyGroups = (nextGroups: AssetGroup[], preferredId?: number | null) => {
    setGroups(nextGroups);
    onGroupsChangeRef.current?.(nextGroups);
    const nextActive = nextGroups.find((group) => group.id === preferredId) ?? nextGroups[0];
    setSelectedKey(nextActive ? String(nextActive.id) : '');
    syncDraft(nextActive);
  };

  useEffect(() => {
    const requestId = ++requestRef.current;

    Promise.all([
      getStoryAssetGroups(storyId),
      listCharacterProfiles(storyId),
    ]).then(async ([loadedGroups, loadedCharacters]) => {
      if (requestRef.current !== requestId) return;
      setCharacters(loadedCharacters);
      setGroups(loadedGroups);
      onGroupsChangeRef.current?.(loadedGroups);
      const firstGroup = loadedGroups[0];
      setSelectedKey(firstGroup ? String(firstGroup.id) : '');
      setDraftName(firstGroup?.name ?? '');
      setDraftDescription(firstGroup?.description ?? '');
      setDraftCharacterIds(new Set((firstGroup?.characters ?? []).map((character) => character.id)));

      const thumbnails: Record<number, string> = {};
      await Promise.all(loadedCharacters.map(async (character) => {
        try {
          const images = await listCharRefImages(storyId, character.id);
          thumbnails[character.id] = images.length > 0 ? refImageUrl(images[0].object_key) : '';
        } catch {
          thumbnails[character.id] = '';
        }
      }));
      if (requestRef.current === requestId) setCharacterThumbnails(thumbnails);
    }).catch((loadError: unknown) => {
      if (requestRef.current !== requestId) return;
      setError(`加载设定组失败: ${loadError instanceof Error ? loadError.message : '未知错误'}`);
    }).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });

    return () => {
      if (requestRef.current === requestId) requestRef.current += 1;
    };
  }, [storyId]);

  const selectGroup = (group: AssetGroup) => {
    setSelectedKey(String(group.id));
    syncDraft(group);
    setError('');
  };

  const addGroup = async () => {
    setSaving(true);
    setError('');
    try {
      const created = await createStoryAssetGroup(storyId, '新设定组', '');
      const nextGroups = await getStoryAssetGroups(storyId);
      applyGroups(nextGroups, created.id);
    } catch (actionError: unknown) {
      setError(`新增设定组失败: ${actionError instanceof Error ? actionError.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  const saveGroup = async () => {
    if (!activeGroup?.id) return;
    setSaving(true);
    setError('');
    try {
      const updated = await updateStoryAssetGroup(activeGroup.id, {
        name: draftName,
        description: draftDescription,
        characterIds: Array.from(draftCharacterIds),
      });
      const nextGroups = await getStoryAssetGroups(storyId);
      applyGroups(nextGroups, updated.id);
    } catch (actionError: unknown) {
      setError(`保存设定组失败: ${actionError instanceof Error ? actionError.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  const removeGroup = async () => {
    if (!activeGroup?.id) return;
    if (!confirm(`删除"${activeGroup.name}"? 已选择该组的章节会恢复为未选择状态。`)) return;
    setSaving(true);
    setError('');
    try {
      await deleteStoryAssetGroup(storyId, activeGroup.id);
      applyGroups(await getStoryAssetGroups(storyId));
    } catch (actionError: unknown) {
      setError(`删除设定组失败: ${actionError instanceof Error ? actionError.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <OverlayPortal>
      <div className="absolute inset-0 z-50 flex items-center justify-center bg-bg-base/30 p-3 backdrop-blur-sm sm:p-4" onClick={onClose}>
        <div className="flex h-[min(640px,88dvh)] w-full max-w-6xl flex-col overflow-hidden rounded-lg border border-border bg-bg-surface shadow-2xl" onClick={(event) => event.stopPropagation()}>
        <div className="flex shrink-0 items-center justify-between border-b border-border px-4 py-3 sm:px-5 sm:py-4">
          <h3 className="flex items-center gap-2 text-sm font-semibold">
            <Layers size={16} className="text-accent" />
            设置设定组
          </h3>
          <button onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-lg text-text-secondary transition-colors hover:bg-bg-raised hover:text-text-primary" aria-label="关闭设定组管理">
            <X size={16} />
          </button>
        </div>

        {error && (
          <div className="mx-4 mt-3 rounded-lg border border-accent/20 bg-accent-soft px-3 py-2 text-xs text-accent sm:mx-5">
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex flex-1 items-center justify-center text-text-secondary">
            <Loader2 size={24} className="animate-spin" />
          </div>
        ) : (
          <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-[220px_1fr]">
            <div className="max-h-44 overflow-y-auto border-b border-border p-3 md:max-h-none md:border-b-0 md:border-r">
              <button
                onClick={addGroup}
                disabled={saving}
                className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-xs font-medium text-text-primary transition-colors hover:bg-accent-soft disabled:opacity-40"
              >
                {saving && !activeGroup ? <Loader2 size={13} className="animate-spin" /> : <Plus size={13} />}
                添加设定组
              </button>
              <div className="mt-3 grid grid-cols-2 gap-1 md:grid-cols-1">
                {groups.map((group) => {
                  const key = String(group.id);
                  const active = key === selectedKey;
                  return (
                    <button
                      key={key}
                      onClick={() => selectGroup(group)}
                      className={`rounded-md border px-3 py-2 text-left transition-colors ${
                        active
                          ? 'border-accent bg-accent/15 text-text-primary'
                          : 'border-border bg-bg-surface text-text-secondary hover:border-accent/20 hover:text-text-primary'
                      }`}
                    >
                      <span className="block truncate text-xs font-medium">{group.name}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="flex min-h-0 flex-col">
              {activeGroup ? (
                <>
                  <div className="flex-1 space-y-4 overflow-y-auto p-4">
                    <div>
                      <label className="mb-1.5 block text-xs text-text-secondary">设定组名称</label>
                      <input
                        value={draftName}
                        onChange={(event) => setDraftName(event.target.value)}
                        className="w-full rounded-lg border border-border bg-bg-raised px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-2"
                        placeholder="输入设定组名称"
                      />
                    </div>

                    <div>
                      <label className="mb-1.5 block text-xs text-text-secondary">描述</label>
                      <textarea
                        value={draftDescription}
                        onChange={(event) => setDraftDescription(event.target.value)}
                        rows={4}
                        className="w-full resize-none rounded-lg border border-border bg-bg-raised p-3 text-sm leading-relaxed text-text-primary outline-none focus:border-accent"
                        placeholder="设定组描述..."
                      />
                    </div>

                    <div>
                      <label className="mb-2 block text-xs text-text-secondary">
                        选择角色卡 ({draftCharacterIds.size} 个已选)
                      </label>
                      {characters.length === 0 ? (
                        <p className="rounded-md border border-dashed border-border py-4 text-center text-xs text-text-muted">
                          暂无角色卡
                        </p>
                      ) : (
                        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                          {characters.map((character) => {
                            const checked = draftCharacterIds.has(character.id);
                            const thumbnail = characterThumbnails[character.id];
                            const toggle = () => {
                              const nextIds = new Set(draftCharacterIds);
                              if (checked) nextIds.delete(character.id);
                              else nextIds.add(character.id);
                              setDraftCharacterIds(nextIds);
                            };
                            return (
                              <button
                                type="button"
                                key={character.id}
                                onClick={toggle}
                                className={`relative overflow-hidden rounded-lg border-2 text-left transition-colors ${
                                  checked
                                    ? 'border-accent bg-accent-muted/30'
                                    : 'border-border bg-bg-surface hover:border-accent/20'
                                }`}
                              >
                                {checked && (
                                  <span className="absolute right-1.5 top-1.5 z-10 flex h-5 w-5 items-center justify-center rounded-full bg-accent shadow">
                                    <Check size={11} className="text-text-primary" />
                                  </span>
                                )}
                                <span className="flex aspect-square items-center justify-center bg-bg-raised">
                                  {thumbnail ? (
                                    <img src={thumbnail} alt={character.name} className="h-full w-full object-contain" loading="lazy" />
                                  ) : (
                                    <Users size={24} className="text-text-muted" />
                                  )}
                                </span>
                                <span className="block truncate px-2 py-1.5 text-center text-xs text-text-secondary">{character.name}</span>
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="flex shrink-0 justify-end gap-2 border-t border-border px-4 py-3 sm:px-5">
                    <button
                      onClick={removeGroup}
                      disabled={saving}
                      className="rounded-md border border-accent/20 bg-accent-muted/30 px-4 py-2 text-sm font-medium text-accent transition-colors hover:bg-accent-muted/50 disabled:opacity-40"
                    >
                      删除
                    </button>
                    <button
                      onClick={saveGroup}
                      disabled={saving}
                      className="flex min-w-20 items-center justify-center gap-1.5 rounded-lg bg-accent px-5 py-2 text-sm font-medium text-text-primary transition-colors hover:bg-accent-soft disabled:opacity-40"
                    >
                      {saving && <Loader2 size={14} className="animate-spin" />}
                      {saving ? '保存中...' : '保存'}
                    </button>
                  </div>
                </>
              ) : (
                <div className="flex flex-1 items-center justify-center p-6 text-center text-sm text-text-secondary">
                  点击“添加设定组”开始配置
                </div>
              )}
            </div>
          </div>
        )}
        </div>
      </div>
    </OverlayPortal>
  );
}
