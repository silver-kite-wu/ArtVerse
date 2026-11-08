import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import { refImageUrl } from '../../api/authoring';
import type { KnowledgeGraph } from '../../api/knowledgeWorkspace';

type GNode = KnowledgeGraph['nodes'][number];
type GEdge = KnowledgeGraph['edges'][number];
type Ring = 0 | 1 | 2;
type CatId = 'kin' | 'love' | 'sect' | 'ally' | 'foe' | 'other';
interface Point { x: number; y: number; ring: Ring; angle: number }

const CX = 480;
const CY = 315;
const RING1 = { rx: 292, ry: 178 };
const RING2 = { rx: 388, ry: 238 };
const WIDE_RING1 = { rx: 336, ry: 208 };
const CAT_ORDER: CatId[] = ['kin', 'love', 'sect', 'ally', 'foe', 'other'];
const CAT_WORDS: Array<[CatId, string[]]> = [
  ['kin', ['亲', '父', '母', '子', '女', '兄', '弟', '姐', '妹', '妻', '夫', '家', '族', '养']],
  ['sect', ['师', '徒', '门', '宗', '派', '长老', '掌门', '弟子']],
  ['foe', ['敌', '仇', '对立', '监', '叛', '追杀', '对手', '竞争']],
  ['ally', ['盟', '友', '同伴', '同袍', '合作', '伙伴', '麾下', '效忠']],
  ['love', ['爱', '恋', '情', '婚', '道侣', '青梅', '伴侣', '心']],
];

function categorize(value: string): CatId {
  for (const [id, words] of CAT_WORDS) if (words.some(word => value.includes(word))) return id;
  return 'other';
}
const nodeCatClass = (cat: CatId) => `cat-${cat}`;

const edgeLabel = (e: GEdge) => String(e.data.label ?? e.data.relation_type ?? '关系');
const edgeType = (e: GEdge) => String(e.data.relation_type ?? '');
const isSymmetric = (e: GEdge) => e.data.direction === 'SYMMETRIC';

function edgeCategory(e: GEdge): CatId {
  return categorize(`${edgeType(e)}${edgeLabel(e)}`);
}

function edgeGeometry(e: GEdge, positions: Record<string, Point>) {
  const a = positions[String(e.source)];
  const b = positions[String(e.target)];
  const padA = a.ring === 0 ? 44 : (a.ring === 1 ? 30 : 28);
  const padB = b.ring === 0 ? 44 : (b.ring === 1 ? 30 : 28);
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len;
  const uy = dy / len;
  const sx = a.x + ux * padA;
  const sy = a.y + uy * padA;
  const tx = b.x - ux * padB;
  const ty = b.y - uy * padB;
  const curve = Math.min(26, 8 + len * 0.03) * (e.source < e.target ? 1 : -1);
  const cx = (sx + tx) / 2 - uy * curve;
  const cy = (sy + ty) / 2 + ux * curve;
  /* 外圈连线标签靠近外侧节点，避免所有标签挤在中心人物附近 */
  const t = Math.max(a.ring, b.ring) >= 2 ? 0.74 : 0.5;
  const mt = 1 - t;
  const lx = mt * mt * sx + 2 * mt * t * cx + t * t * tx;
  const ly = mt * mt * sy + 2 * mt * t * cy + t * t * ty;
  return {
    d: `M ${sx.toFixed(1)} ${sy.toFixed(1)} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${tx.toFixed(1)} ${ty.toFixed(1)}`,
    lx,
    ly,
  };
}

interface Layout {
  positions: Record<string, Point>;
  innerRing: { rx: number; ry: number };
  nodes: GNode[];
  edges: GEdge[];
  centerId: number | null;
  cats: Record<string, CatId>;
}

function buildLayout(allNodes: GNode[], allEdges: GEdge[], centerProp: number | undefined, hops: number): Layout {
  const involved = new Set<number>();
  allEdges.forEach(e => { involved.add(e.source); involved.add(e.target); });
  const nodes = allNodes.filter(n => involved.has(n.id));
  const edges = allEdges.filter(e => involved.has(e.source) && involved.has(e.target));
  if (!nodes.length) return { positions: {}, innerRing: RING1, nodes: [], edges: [], centerId: null, cats: {} };

  const adj = new Map<number, GEdge[]>();
  nodes.forEach(n => adj.set(n.id, []));
  edges.forEach(e => { adj.get(e.source)?.push(e); adj.get(e.target)?.push(e); });

  const catOf = (id: number): CatId => {
    const counts = new Map<CatId, number>();
    (adj.get(id) ?? []).forEach(e => {
      const c = edgeCategory(e);
      counts.set(c, (counts.get(c) ?? 0) + 1);
    });
    let best: CatId = 'other';
    let bestCount = 0;
    CAT_ORDER.forEach(c => {
      const n = counts.get(c) ?? 0;
      if (n > bestCount) { best = c; bestCount = n; }
    });
    return best;
  };
  const cats: Record<string, CatId> = {};
  nodes.forEach(n => { cats[String(n.id)] = catOf(n.id); });

  let center: number;
  if (centerProp != null && adj.has(centerProp)) center = centerProp;
  else {
    center = nodes[0].id;
    nodes.forEach(n => { if (adj.get(n.id)!.length > adj.get(center)!.length) center = n.id; });
  }

  const depth = new Map<number, number>([[center, 0]]);
  let frontier = [center];
  for (let d = 1; d <= hops; d += 1) {
    const next: number[] = [];
    frontier.forEach(id => (adj.get(id) ?? []).forEach(e => {
      const other = e.source === id ? e.target : e.source;
      if (!depth.has(other)) { depth.set(other, d); next.push(other); }
    }));
    frontier = next;
  }

  const order = new Map(allNodes.map((n, i) => [n.id, i]));
  const rank = (id: number) => CAT_ORDER.indexOf(cats[String(id)]) * allNodes.length + (order.get(id) ?? 0);
  const ring1: number[] = [];
  const ring2: number[] = [];
  nodes.forEach(n => {
    const d = depth.get(n.id);
    if (d === 1) ring1.push(n.id);
    else if (d === 2) ring2.push(n.id);
    else if (d === undefined) ring2.push(n.id);
  });
  ring1.sort((a, b) => rank(a) - rank(b));
  ring2.sort((a, b) => rank(a) - rank(b));

  const positions: Record<string, Point> = {};
  positions[String(center)] = { x: CX, y: CY, ring: 0, angle: -90 };

  const innerRing = ring1.length > 8 ? WIDE_RING1 : RING1;
  const step = ring1.length ? 360 / ring1.length : 0;
  /* 让连接数最多的人物（通常是上一个中心）落在正上方，便于对照 */
  let hubIdx = 0;
  ring1.forEach((id, i) => {
    if (adj.get(id)!.length > adj.get(ring1[hubIdx])!.length) hubIdx = i;
  });
  const startDeg = -90 - step * hubIdx;
  ring1.forEach((id, i) => {
    const deg = startDeg + step * i;
    const rad = deg * Math.PI / 180;
    positions[String(id)] = { x: CX + innerRing.rx * Math.cos(rad), y: CY + innerRing.ry * Math.sin(rad), ring: 1, angle: deg };
  });

  /* 外圈节点靠近它在内圈的父节点，避免与内圈重叠 */
  const buckets = new Map<number, number[]>();
  const loose: number[] = [];
  ring2.forEach(id => {
    const parent = (adj.get(id) ?? [])
      .map(e => (e.source === id ? e.target : e.source))
      .find(other => positions[String(other)]?.ring === 1);
    if (parent != null) {
      buckets.set(parent, [...(buckets.get(parent) ?? []), id]);
    } else loose.push(id);
  });
  const placeOuter = (id: number, deg: number) => {
    const rad = deg * Math.PI / 180;
    positions[String(id)] = { x: CX + RING2.rx * Math.cos(rad), y: CY + RING2.ry * Math.sin(rad), ring: 2, angle: deg };
  };
  buckets.forEach((children, parentId) => {
    children.sort((a, b) => rank(a) - rank(b));
    const base = positions[String(parentId)].angle;
    const n = children.length;
    if (n === 1) { placeOuter(children[0], base + 16); return; }
    /* 以父节点方向为中心左右对称展开，留出 22° 空档避免压住父节点 */
    const perSide = Math.ceil(n / 2);
    const span = Math.min(85, 22 + Math.max(0, perSide - 1) * 21);
    const gap = perSide > 1 ? (span - 22) / (perSide - 1) : 0;
    children.forEach((id, i) => {
      const bucketRank = Math.floor(i / 2);
      placeOuter(id, base + (i % 2 === 0 ? -1 : 1) * (22 + bucketRank * gap));
    });
  });
  loose.forEach((id, i) => {
    const gap = loose.length > 1 ? 360 / loose.length : 0;
    placeOuter(id, -90 + gap * i);
  });

  return {
    positions,
    innerRing,
    nodes: nodes.slice().sort((a, b) => (positions[String(b.id)]?.ring ?? 0) - (positions[String(a.id)]?.ring ?? 0)),
    edges,
    centerId: center,
    cats,
  };
}

export default function RelationshipGraph({ graph, hops = 1, centerId, onNode, onEdge, onFocus, onBack, backLabel }: {
  graph: KnowledgeGraph;
  hops?: number;
  centerId?: number;
  onNode: (node: GNode) => void;
  onEdge: (edge: GEdge) => void;
  onFocus?: (id: number) => void;
  onBack?: () => void;
  backLabel?: string;
}) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const [list, setList] = useState(() => window.matchMedia('(max-width:600px)').matches);
  const [relationType, setRelationType] = useState('');
  const [visibility, setVisibility] = useState('');
  const [zoom, setZoom] = useState(1);
  const [dragging, setDragging] = useState(false);
  const [dragState, setDragState] = useState<{ sig: string; values: Record<string, { x: number; y: number }> }>({ sig: '', values: {} });

  useEffect(() => {
    const media = window.matchMedia('(max-width:600px)');
    const adapt = () => { if (media.matches) setList(true); };
    media.addEventListener('change', adapt);
    return () => media.removeEventListener('change', adapt);
  }, []);

  const visibleEdges = useMemo(() => graph.edges.filter(e => (!visibility || (visibility === 'HIDDEN' ? Boolean(e.data.hidden_information) : !e.data.hidden_information))), [graph.edges, visibility]);
  const layout = useMemo(() => buildLayout(graph.nodes, visibleEdges, centerId, hops), [graph.nodes, visibleEdges, centerId, hops]);
  const layoutSig = useMemo(() => `${layout.centerId}|${hops}|${layout.nodes.map(n => n.id).join(',')}|${layout.edges.map(e => String(e.id)).join(',')}`, [layout, hops]);

  const positions = useMemo(() => {
    if (dragState.sig !== layoutSig) return layout.positions;
    const merged: Record<string, Point> = { ...layout.positions };
    Object.entries(dragState.values).forEach(([key, v]) => { const p = merged[key]; if (p) merged[key] = { ...p, x: v.x, y: v.y }; });
    return merged;
  }, [layout.positions, layoutSig, dragState]);

  const types = useMemo(() => {
    const counts = new Map<string, number>();
    visibleEdges.forEach(e => { const t = edgeType(e); if (t) counts.set(t, (counts.get(t) ?? 0) + 1); });
    return Array.from(counts, ([type, count]) => ({ type, count, cat: categorize(type) })).sort((a, b) => b.count - a.count || a.type.localeCompare(b.type, 'zh-Hans-CN'));
  }, [visibleEdges]);
  const activeType = types.some(t => t.type === relationType) ? relationType : '';

  const centerNode = layout.nodes.find(n => n.id === layout.centerId) ?? null;
  const centerName = centerNode?.name ?? '中心人物';
  const outerEdgeCount = layout.edges.filter(e => Math.max(positions[String(e.source)]?.ring ?? 0, positions[String(e.target)]?.ring ?? 0) >= 2).length;

  const sceneRef = useRef<SVGGElement>(null);
  const nodeRefs = useRef(new Map<string, SVGGElement>());
  const edgeRefs = useRef(new Map<string, SVGGElement>());
  const posRef = useRef<Record<string, Point>>({});
  const dragRef = useRef<{ id: number; startX: number; startY: number; offX: number; offY: number; moved: boolean } | null>(null);
  const suppressClick = useRef(0);
  posRef.current = positions;

  const scenePoint = (clientX: number, clientY: number) => {
    const scene = sceneRef.current;
    const matrix = scene?.getScreenCTM();
    if (!matrix) return { x: clientX, y: clientY };
    return new DOMPoint(clientX, clientY).matrixTransform(matrix.inverse());
  };

  const refreshEdge = (e: GEdge) => {
    const group = edgeRefs.current.get(String(e.id));
    if (!group) return;
    const geo = edgeGeometry(e, posRef.current);
    group.querySelectorAll('path').forEach(path => path.setAttribute('d', geo.d));
    group.querySelector('.kb-edge-label')?.setAttribute('transform', `translate(${geo.lx.toFixed(1)} ${geo.ly.toFixed(1)})`);
  };

  const moveNode = (id: number, x: number, y: number) => {
    const key = String(id);
    const current = posRef.current[key];
    if (!current) return;
    posRef.current[key] = { ...current, x, y };
    const el = nodeRefs.current.get(key);
    if (el) el.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`;
    layout.edges.forEach(e => { if (e.source === id || e.target === id) refreshEdge(e); });
  };

  const commitNode = (id: number) => {
    const key = String(id);
    const point = posRef.current[key];
    if (!point) return;
    setDragState(prev => ({
      sig: layoutSig,
      values: { ...(prev.sig === layoutSig ? prev.values : {}), [key]: { x: point.x, y: point.y } },
    }));
  };

  const onPointerDown = (event: ReactPointerEvent<SVGGElement>, id: number) => {
    if (event.button !== 0 || id === layout.centerId) return;
    const point = scenePoint(event.clientX, event.clientY);
    const current = posRef.current[String(id)];
    if (!current) return;
    dragRef.current = { id, startX: event.clientX, startY: event.clientY, offX: point.x - current.x, offY: point.y - current.y, moved: false };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const onPointerMove = (event: ReactPointerEvent<SVGGElement>, id: number) => {
    const drag = dragRef.current;
    if (!drag || drag.id !== id) return;
    if (!drag.moved) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < 4) return;
      drag.moved = true;
      setDragging(true);
    }
    event.preventDefault();
    const point = scenePoint(event.clientX, event.clientY);
    moveNode(id, point.x - drag.offX, point.y - drag.offY);
  };

  const onPointerUp = (event: ReactPointerEvent<SVGGElement>, id: number) => {
    const drag = dragRef.current;
    if (!drag || drag.id !== id) return;
    if (drag.moved) {
      commitNode(id);
      suppressClick.current = Date.now() + 320;
    }
    dragRef.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  const activate = (node: GNode, ring: Ring) => {
    if (Date.now() < suppressClick.current) return;
    if (ring === 0) onNode(node);
    else onFocus?.(node.id);
  };

  const keyActivate = (event: ReactKeyboardEvent<SVGGElement>, action: () => void) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    if (Date.now() < suppressClick.current) return;
    action();
  };

  const coverUrl = (node: GNode) => node.cover?.object_key ? refImageUrl(String(node.cover.object_key)) : '';

  const tableEdges = activeType ? layout.edges.filter(e => edgeType(e) === activeType) : layout.edges;
  const name = (id: number) => graph.nodes.find(n => n.id === id)?.name ?? `人物 #${id}`;
  const labelWidth = (label: string, hidden: boolean) => Math.max(30, Array.from(label).length * 11.5 + (hidden ? 20 : 12));

  return (
    <section className="kb-relationships" aria-label="人物有向关系">
      <div className="kb-view-tools">
        <span>{layout.nodes.length} 位人物 · {layout.edges.length} 条关系{centerName ? ` · 以 ${centerName} 为中心` : ''}</span>
        {backLabel && onBack && <button className="kb-center-back" onClick={onBack}>↩ 回到 {backLabel}</button>}
        <label>公开程度<select value={visibility} onChange={e => setVisibility(e.target.value)}><option value="">全部</option><option value="PUBLIC">公开</option><option value="HIDDEN">包含隐藏信息</option></select></label>
        <button onClick={() => setList(v => !v)}>{list ? '切换有向图' : '切换关系列表'}</button>
      </div>

      {types.length > 0 && <div className="kb-chips" role="group" aria-label="关系类型筛选">
        <button className="kb-chip" aria-pressed={!relationType} style={{ '--chip-color': 'var(--color-accent)' } as CSSProperties} onClick={() => setRelationType('')}><span className="kb-chip-dot" />全部<span className="kb-chip-count">{layout.edges.length}</span></button>
        {types.map(t => <button key={t.type} className="kb-chip" aria-pressed={relationType === t.type} style={{ '--chip-color': `var(--kb-cat-${t.cat})` } as CSSProperties} onClick={() => setRelationType(v => (v === t.type ? '' : t.type))}><span className="kb-chip-dot" />{t.type}<span className="kb-chip-count">{t.count}</span></button>)}
      </div>}

      {graph.truncated && <p className="kb-warning">已到首屏上限（80 个节点 / 200 条边）。请按人物展开查看；当前查询共 {graph.totalEdges} 条关系。</p>}
      {!layout.edges.length ? <p className="kb-empty">此视角暂无已绑定的人物关系。待绑定候选可在下方审核列表中补充。</p>
        : list ? <div className="kb-table-wrap"><table><caption>箭头表示起点对终点的关系；双向箭头表示对称关系。点击人物或关系可查看详情。</caption><thead><tr><th>起点</th><th>方向 / 关系</th><th>终点</th><th>阶段</th><th>审核</th></tr></thead><tbody>{tableEdges.map(e => <tr key={e.id}><td><button className="kb-list-person" onClick={() => onFocus?.(e.source)}>{name(e.source)}</button></td><td><button onClick={() => onEdge(e)}>{isSymmetric(e) ? '↔' : '→'} {edgeLabel(e)}</button></td><td><button className="kb-list-person" onClick={() => onFocus?.(e.target)}>{name(e.target)}</button></td><td>{String(e.data.stage ?? '--')}</td><td>{e.pending ? '待确认' : '正式'}</td></tr>)}</tbody></table></div>
          : <div className={`kb-graph-stage${dragging ? ' dragging' : ''}`}>
            <svg viewBox="0 0 960 630" preserveAspectRatio="xMidYMid meet" role="img" aria-label={`以 ${centerName} 为中心的人物关系网络`}>
              <defs>
                <pattern id={`${uid}-dots`} width="22" height="22" patternUnits="userSpaceOnUse"><circle className="kb-grid-dot" cx="1.5" cy="1.5" r="1" /></pattern>
                <marker id={`${uid}-arrow`} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" markerUnits="userSpaceOnUse" orient="auto"><path d="M1 1 L7 4 L1 7 Z" className="kb-arrow-fill" /></marker>
                <marker id={`${uid}-arrow-start`} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" markerUnits="userSpaceOnUse" orient="auto-start-reverse"><path d="M1 1 L7 4 L1 7 Z" className="kb-arrow-fill" /></marker>
                <marker id={`${uid}-arrow-warn`} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" markerUnits="userSpaceOnUse" orient="auto"><path d="M1 1 L7 4 L1 7 Z" className="kb-arrow-warn" /></marker>
                {layout.nodes.map(n => {
                  const url = coverUrl(n);
                  if (!url) return null;
                  const ring = positions[String(n.id)]?.ring ?? 2;
                  return <clipPath key={n.id} id={`${uid}-clip-${n.id}`}><circle r={ring === 0 ? 40 : (ring === 1 ? 26 : 24)} /></clipPath>;
                })}
              </defs>
              <rect x="0" y="0" width="960" height="630" fill={`url(#${uid}-dots)`} />
              <g className="kb-graph-scene" ref={sceneRef} style={{ transform: `scale(${zoom})` }}>
                <ellipse className="kb-ring-guide" cx={CX} cy={CY} rx={layout.innerRing.rx} ry={layout.innerRing.ry} />
                {layout.nodes.some(n => positions[String(n.id)]?.ring === 2) && <ellipse className="kb-ring-guide" cx={CX} cy={CY} rx={RING2.rx} ry={RING2.ry} />}
                <g className="kb-graph-edges">
                  {layout.edges.map(e => {
                    const geo = edgeGeometry(e, positions);
                    const label = edgeLabel(e);
                    const hidden = Boolean(e.data.hidden_information);
                    const symmetric = isSymmetric(e);
                    const ring = Math.max(positions[String(e.source)]?.ring ?? 0, positions[String(e.target)]?.ring ?? 0);
                    const dense = ring >= 2 && outerEdgeCount > 5;
                    const dim = Boolean(activeType && edgeType(e) !== activeType);
                    const width = labelWidth(label, hidden);
                    return <g key={String(e.id)} ref={el => { if (el) edgeRefs.current.set(String(e.id), el); else edgeRefs.current.delete(String(e.id)); }}
                      className={`kb-edge${e.pending ? ' pending' : ''}${dense ? ' dense-label' : ''}${dim ? ' dim' : ''}`}
                      tabIndex={0} role="button" aria-label={`${name(e.source)} ${symmetric ? '与' : '对'} ${name(e.target)}：${label}${e.pending ? '（待确认）' : ''}`}
                      onClick={() => onEdge(e)} onKeyDown={event => keyActivate(event, () => onEdge(e))}>
                      <path className="kb-edge-base" d={geo.d}
                        markerEnd={`url(#${uid}-arrow${e.pending ? '-warn' : ''})`}
                        markerStart={symmetric ? `url(#${uid}-arrow-start)` : undefined} />
                      <path className="kb-edge-hit" d={geo.d} />
                      <g className={`kb-edge-label${e.pending ? ' pending' : ''}${hidden ? ' hidden' : ''}`} transform={`translate(${geo.lx.toFixed(1)} ${geo.ly.toFixed(1)})`}>
                        <rect x={-width / 2} y="-9" width={width} height="18" rx="6" />
                        <circle className="kb-label-dot" cx={-width / 2 + 7} cy="0" r="2.4" />
                        <text x={hidden ? 3 : 0} y="3.5" textAnchor="middle">{label}</text>
                      </g>
                    </g>;
                  })}
                </g>
                <g className="kb-graph-nodes">
                  {layout.nodes.map(n => {
                    const key = String(n.id);
                    const pos = positions[key];
                    if (!pos) return null;
                    const ring = pos.ring;
                    const r = ring === 0 ? 40 : (ring === 1 ? 26 : 24);
                    const nameY = r + (ring === 0 ? 27 : 19);
                    const cat = layout.cats[key] ?? 'other';
                    const url = coverUrl(n);
                    const dim = Boolean(activeType) && ring !== 0 && !layout.edges.some(e => edgeType(e) === activeType && (e.source === n.id || e.target === n.id));
                    return <g key={key} data-person={n.id} ref={el => { if (el) nodeRefs.current.set(key, el); else nodeRefs.current.delete(key); }}
                      className={`kb-person ${ring === 0 ? 'ego' : ''}${ring === 0 ? ' cat-none' : ` ${nodeCatClass(cat)}`}${dim ? ' dim' : ''}`}
                      style={{ transform: `translate(${pos.x.toFixed(1)}px, ${pos.y.toFixed(1)}px)` }}
                      tabIndex={0} role="button" aria-label={ring === 0 ? `查看 ${n.name} 档案` : `以 ${n.name} 为中心重建图谱`}
                      onClick={() => activate(n, ring)} onKeyDown={event => keyActivate(event, () => activate(n, ring))}
                      onPointerDown={event => onPointerDown(event, n.id)} onPointerMove={event => onPointerMove(event, n.id)}
                      onPointerUp={event => onPointerUp(event, n.id)} onPointerCancel={event => onPointerUp(event, n.id)}>
                      <circle className="kb-halo" r={r + 9} />
                      <circle className="kb-avatar" r={r} />
                      {url ? <image className="kb-avatar-img" href={url} x={-r} y={-r} width={r * 2} height={r * 2} preserveAspectRatio="xMidYMid slice" clipPath={`url(#${uid}-clip-${n.id})`} />
                        : <text className="kb-initial" y="1" fontSize={Math.round(r * 0.82)} dominantBaseline="middle">{n.name.slice(0, 1)}</text>}
                      <text className="kb-name" y={nameY} fontSize={ring === 0 ? 17 : 13}>{n.name}</text>
                    </g>;
                  })}
                </g>
              </g>
            </svg>
            <div className="kb-graph-legend" aria-hidden="true">
              <span><i />单向关系</span>
              <span><i className="sym" />双向关系</span>
              <span><i className="dash" />待确认</span>
              <span><i className="dotkey" />隐藏信息</span>
            </div>
            <div className="kb-graph-zoom" role="group" aria-label="缩放图谱">
              <button onClick={() => setZoom(v => Math.min(1.6, Math.round((v + .12) * 100) / 100))} aria-label="放大">+</button>
              <button onClick={() => setZoom(v => Math.max(.7, Math.round((v - .12) * 100) / 100))} aria-label="缩小">−</button>
              <button onClick={() => setZoom(1)} aria-label="重置视图">↺</button>
            </div>
          </div>}
      <p className="kb-muted">点击环上人物即以他为中心重建图谱，点击中心人物查看档案，点击连线查看关系与证据。节点可拖拽微调位置，中心节点固定。外圈关系密集时标签自动收起，悬停连线即可显示。</p>
    </section>
  );
}
