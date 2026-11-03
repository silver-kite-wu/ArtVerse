import { TYPES, chapterLabel, labels } from './knowledgeModel';
import { useState } from 'react';
import { refImageUrl } from '../../api/authoring';
import type { Data, KnowledgeOptions, KnowledgeRecord } from '../../api/knowledgeWorkspace';
import { readableKnowledgeText } from './knowledgeFormValues';

const text = (value: unknown): string => value === null || value === undefined || value === '' ? '--' : typeof value === 'string' ? labels[value] ?? value : Array.isArray(value) ? value.map(text).join('、') || '--' : typeof value === 'object' ? readableKnowledgeText(value) || '--' : String(value);
export function RecordCard({ item, onSelect, options, pending = false }: { item: KnowledgeRecord; onSelect: (item: KnowledgeRecord) => void; options: KnowledgeOptions; pending?: boolean }) {
  const data = item.structuredData; const cover = item.cover;
  return <button className={`kb-card ${pending ? 'kb-candidate-card' : ''}`} onClick={() => onSelect(item)}><div className="kb-card-heading">{item.type === 'CHARACTER_CARD' && <span className="kb-avatar">{cover?.object_key ? <img src={refImageUrl(String(cover.object_key))} alt={`${item.title}参考图`} loading="lazy" /> : <span>{item.title.slice(0, 1) || '人'}</span>}</span>}<div><span className="kb-type-badge">{TYPES.find(t => t.id === item.type)?.label}{pending ? ' · 待确认' : ''}</span><h3>{item.title}</h3>{Array.isArray(data.aliases) && <p className="kb-muted">{data.aliases.filter(Boolean).join(' / ')}</p>}</div>{item.scope === 'LEGACY_UNSCOPED' && <span className="kb-tag">范围待整理</span>}</div><p className="kb-card-summary">{item.summary || item.body}</p>
    {item.type === 'CHARACTER_CARD' && <><div className="kb-tags">{item.levels?.map(f => <span className="kb-tag" key={f.id}>{text(f.payload.dimension)} · {text(f.payload.label)}</span>)}{item.identities?.map(f => <span className="kb-tag" key={f.id}>{text(f.payload.label)}</span>)}</div><dl className="kb-card-meta"><div><dt>首次出场</dt><dd>{chapterLabel(options, data.first_appearance_chapter_id)}</dd></div><div><dt>退场</dt><dd>{chapterLabel(options, data.exit_chapter_id)}</dd></div></dl></>}
    {item.type === 'CHARACTER_RELATION' && <p>{options.characters.find(c => c.id === Number(data.source_character_id))?.name ?? String(data.left_character ?? '待绑定')} {data.direction === 'SYMMETRIC' ? '↔' : '→'} {options.characters.find(c => c.id === Number(data.target_character_id))?.name ?? String(data.right_character ?? '待绑定')} · {text(data.label ?? data.relation_type)}</p>}
    {item.type === 'FORESHADOWING' && <span className="kb-tag">{text(data.status ?? 'UNKNOWN')}</span>}{item.type === 'WORLDVIEW' && <span className="kb-tag">{text(data.subtype)}</span>}{item.type === 'TIMELINE' && <p className="kb-muted">{text(data.story_time)} · {chapterLabel(options, item.revealChapterId)}</p>}
    {item.reminders?.map(message => <p className="kb-warning" key={message}>{message}</p>)}
    {pending && item.issues.length > 0 && <p className="kb-warning">{item.issues.length} 项需要补充 · 点击查看</p>}
  </button>;
}
export function TimelineView({ items, options, order, setOrder, onSelect }: { items: KnowledgeRecord[]; options: KnowledgeOptions; order: string; setOrder: (order: string) => void; onSelect: (item: KnowledgeRecord) => void }) {
  const [lanes, setLanes] = useState(false);
  const groups = lanes ? Array.from(new Set(items.map(i => String(i.structuredData.storyline || '主线 / 未归类')))).map(name => ({name, items: items.filter(i => String(i.structuredData.storyline || '主线 / 未归类') === name)})) : [{name: '', items}];
  return <section><div className="kb-view-tools"><span>统一事件中心 · 人物经历与回收引用同一事件</span><label>排序方式<select value={order} onChange={e => setOrder(e.target.value)}><option value="NARRATIVE">叙事顺序</option><option value="STORY">故事发生顺序</option></select></label><label><input type="checkbox" checked={lanes} onChange={e => setLanes(e.target.checked)} />按故事线分栏</label></div><p className="kb-muted">未知时间保留原文；切换顺序不改变章节可见范围。</p><div className={lanes ? 'kb-timeline-lanes' : ''}>{groups.map(group => <section key={group.name}>{group.name && <h3>{group.name}</h3>}<div className="kb-timeline">{group.items.map(item => <div className="kb-event-row" key={item.id}><div className="kb-event-date"><span>{chapterLabel(options, item.revealChapterId)}</span><small>{String(item.structuredData.story_time ?? '时间未知')}</small></div><RecordCard item={item} options={options} onSelect={onSelect} /></div>)}</div></section>)}</div></section>;
}
const COLUMNS = [{ title: '作者构思', states: ['DRAFT_PLAN'] }, { title: '已埋设', states: ['SEEDED'] }, { title: '推进 / 部分回收', states: ['DEVELOPING', 'PARTIALLY_RESOLVED'] }, { title: '已回收', states: ['RESOLVED'] }, { title: '弃用 / 未知', states: ['ABANDONED', 'UNKNOWN', 'PENDING'] }];
export function ForeshadowingBoard({ items, options, onSelect }: { items: KnowledgeRecord[]; options: KnowledgeOptions; onSelect: (item: KnowledgeRecord) => void }) {
  return <section aria-label="伏笔状态看板" className="kb-board">{COLUMNS.map(column => { const cards = items.filter(i => column.states.includes(String(i.structuredData.status ?? 'UNKNOWN')));return <div className="kb-board-column" key={column.title}><h3>{column.title}<span>{cards.length}</span></h3>{cards.map(item => <RecordCard key={item.id} item={item} options={options} onSelect={onSelect} />)}{!cards.length && <p className="kb-muted">暂无伏笔</p>}</div>; })}</section>;
}
export function DefinitionList({ data, options, fieldLabels = {} }: { data: Data; options: KnowledgeOptions; fieldLabels?: Record<string, string> }) {
  return <dl className="kb-definition-list">{Object.entries(data).filter(([key, value]) => ['first_appearance_chapter_id', 'exit_chapter_id'].includes(key) || (value !== null && value !== undefined && value !== '' && key !== 'character_id' && key !== 'entity_id')).map(([key, value]) => {
    const eventName = (id: unknown) => `${String(options.events.find(f => f.id === Number(id))?.payload.title ?? '关联事件')} #${id}`;
    const shown = key === 'participant_roles' && typeof value === 'object' ? Object.entries(value as Data).map(([id, role]) => `${options.characters.find(c => c.id === Number(id))?.name ?? `#${id}`}：${String(role)}`).join('；')
      : (key.endsWith('_character_ids') || key === 'known_by') && Array.isArray(value) ? value.map(id => options.characters.find(c => c.id === Number(id))?.name ?? `#${id}`).join('、')
        : key.endsWith('_entity_ids') && Array.isArray(value) ? value.map(id => options.entities.find(e => e.id === Number(id))?.name ?? `#${id}`).join('、')
          : key.endsWith('_event_ids') && Array.isArray(value) ? value.map(eventName).join('、')
            : key.endsWith('chapter_id') ? chapterLabel(options, value) : key.endsWith('event_id') ? eventName(value) : key.endsWith('character_id') ? options.characters.find(c => c.id === Number(value))?.name ?? `#${value}` : key.endsWith('entity_id') ? options.entities.find(e => e.id === Number(value))?.name ?? `#${value}` : text(value);
    return <div key={key}><dt>{fieldLabels[key] ?? ({ reveal_chapter_id: '首次揭示', effective_from_chapter_id: '阶段开始', effective_to_chapter_id: '阶段截止（不含）', evidence: '来源证据', certainty: '事实属性' } as Record<string, string>)[key] ?? key}</dt><dd>{shown}</dd></div>;
  })}</dl>;
}
