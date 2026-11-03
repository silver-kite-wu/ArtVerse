import type { KnowledgeUnitInput, KnowledgeUnitType } from '../../api/knowledgeAndImage';
import type { KnowledgeOptions, KnowledgeRecord } from '../../api/knowledgeWorkspace';
export const labels: Record<string, string> = {
  UNKNOWN: '未知', DRAFT_PLAN: '作者构思', SEEDED: '已埋设', DEVELOPING: '推进中', PARTIALLY_RESOLVED: '部分回收', RESOLVED: '已回收', ABANDONED: '已弃用',
  PLACE: '地点', ORGANIZATION: '组织 / 势力', ITEM: '物品 / 装备', CURRENCY: '货币 / 资源', POWER_SYSTEM: '境界 / 等级体系', RULE: '世界规则', HISTORY: '历史背景',
  DIRECTED: '单向 →', SYMMETRIC: '对称 ↔', ACTIVE: '活跃', ABSENT: '暂离', EXITED: '退场', DEAD: '已确认死亡',
  OCCURRENCE: '发生', MENTION: '提及', FLASHBACK: '回忆 / 倒叙', DREAM: '梦境', RUMOR: '传闻', FACT: '确认事实', PLAN: '作者计划', BELIEF: '人物认知 / 误解',
  GAIN: '获得', LOSS: '失去', TRANSFER: '转赠', CONSUME: '消耗', EQUIP: '装备', UNEQUIP: '卸下', OBSERVED_BALANCE: '明确余额',
  OWNED: '拥有', BORROWED: '借用', CUSTODY: '保管',
  STACKABLE: '可堆叠', UNIQUE: '独立唯一物品', STRICT: '严格前后约束', TIME_LOOP: '明确的时间循环剧情',
};

export const FACT_NAMES: Record<string, string> = { CHARACTER_CORE: '人物档案阶段', LEVEL_STATE: '境界 / 等级', IDENTITY_STATE: '身份 / 职务', HOLDING_STATE: '明确持有状态', ASSET_CHANGE: '外物变动', RELATION_STATE: '关系阶段', EVENT: '事件', FORESHADOWING: '伏笔阶段', FORESHADOWING_CLUE: '推进线索', WORLD_DEFINITION: '世界观定义' };

export const TYPES = [{ id: 'CHARACTER_CARD', label: '角色卡', mark: '人' }, { id: 'CHARACTER_RELATION', label: '人物关系', mark: '↗' }, { id: 'WORLDVIEW', label: '世界观', mark: '界' }, { id: 'TIMELINE', label: '时间线', mark: '时' }, { id: 'FORESHADOWING', label: '伏笔', mark: '线' }] as const;
export const chapterLabel = (options: KnowledgeOptions, value: unknown) => { const chapter = options.chapters.find(c => c.id === Number(value)); return chapter ? `第 ${chapter.chapter_number} 章` : '--'; };

export const blankKnowledge = (type: KnowledgeUnitType, chapter: number | null): KnowledgeUnitInput => ({ type, title: '', body: '', summary: '', importance: 3, structuredData: { reveal_chapter_id: chapter, ...(type === 'FORESHADOWING' ? { status: 'UNKNOWN' } : type === 'CHARACTER_RELATION' ? { direction: 'DIRECTED' } : {}) } });
export const inputOf = (item: KnowledgeRecord): KnowledgeUnitInput => ({ type: item.type, title: item.title, body: item.body, summary: item.summary, importance: item.importance, structuredData: { ...item.structuredData } });
export const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试';

