import { useEffect, useMemo, useState } from 'react';
import { Background, BaseEdge, Controls, EdgeLabelRenderer, Handle, MarkerType, Position, ReactFlow, type Edge, type EdgeProps, type Node, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { refImageUrl } from '../../api/authoring';
import type { Data, KnowledgeGraph } from '../../api/knowledgeWorkspace';

type PersonNode = Node<{ label: string; cover?: Data | null }>;
function Person({ data }: NodeProps<PersonNode>) {
  return <div className="kb-person-node"><Handle type="target" position={Position.Left} /><span className="kb-node-avatar">{data.cover?.object_key ? <img alt="" src={refImageUrl(String(data.cover.object_key))} /> : data.label.slice(0, 1)}</span><strong title={data.label}>{data.label}</strong><Handle type="source" position={Position.Right} /></div>;
}
type RelationEdge = Edge<{ offset: number; label: string; pending: boolean }>;
function CurvedRelation(props: EdgeProps<RelationEdge>) {
  const { sourceX: sx, sourceY: sy, targetX: tx, targetY: ty, data } = props;
  const distance = Math.hypot(tx - sx, ty - sy) || 1;
  const offset = data?.offset ?? 0;
  const cx = (sx + tx) / 2 - (ty - sy) / distance * offset, cy = (sy + ty) / 2 + (tx - sx) / distance * offset;
  const path = `M ${sx} ${sy} Q ${cx} ${cy} ${tx} ${ty}`;
  const lx = .25 * sx + .5 * cx + .25 * tx, ly = .25 * sy + .5 * cy + .25 * ty;
  return <><BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} markerStart={props.markerStart} style={{ ...props.style, strokeDasharray: data?.pending ? '6 5' : undefined, strokeWidth: props.selected ? 3 : 1.8 }} /><EdgeLabelRenderer><span className="kb-edge-label" style={{ transform: `translate(-50%, -50%) translate(${lx}px,${ly}px)` }}>{data?.label}</span></EdgeLabelRenderer></>;
}
const nodeTypes = { person: Person }; const edgeTypes = { relationship: CurvedRelation };
export default function RelationshipGraph({ graph, onEdge, onNode }: { graph: KnowledgeGraph; onEdge: (edge: KnowledgeGraph['edges'][number]) => void; onNode: (node: KnowledgeGraph['nodes'][number]) => void }) {
  const [list, setList] = useState(() => window.matchMedia('(max-width:600px)').matches);const [relationType, setRelationType] = useState(''); const [visibility, setVisibility] = useState('');
  useEffect(() => {const media = window.matchMedia('(max-width:600px)');const adapt = () => {if (media.matches) setList(true);};media.addEventListener('change', adapt);return () => media.removeEventListener('change', adapt);}, []);
  const { nodes, edges, shown } = useMemo(() => {
    const shown = graph.edges.filter(e => (!relationType || String(e.data.relation_type) === relationType) && (!visibility || (visibility === 'HIDDEN' ? Boolean(e.data.hidden_information) : !e.data.hidden_information)));
    const groups = new Map<string, typeof shown>();
    shown.forEach(e => { const key = [e.source, e.target].sort((a, b) => a - b).join(':'); groups.set(key, [...(groups.get(key) ?? []), e]); });
    const edges: RelationEdge[] = shown.map(e => {
      const group = groups.get([e.source, e.target].sort((a, b) => a - b).join(':'))!;
      const index = group.indexOf(e); const offset = (index - (group.length - 1) / 2) * 100 * (e.source < e.target ? 1 : -1);
      const symmetric = e.data.direction === 'SYMMETRIC';
      return { id: String(e.id), source: String(e.source), target: String(e.target), type: 'relationship', label: String(e.data.label ?? e.data.relation_type ?? '关系'),
        markerEnd: { type: MarkerType.ArrowClosed, width: 22, height: 22, color: e.pending ? '#b48431' : '#6c7198' }, ...(symmetric ? { markerStart: { type: MarkerType.ArrowClosed, color: '#6c7198' } } : {}),
        data: { offset, label: `${String(e.data.label ?? e.data.relation_type ?? '关系')}${e.data.stage ? ` · ${e.data.stage}` : ''}`, pending: e.pending }, style: { stroke: e.pending ? '#b48431' : '#6c7198' }, focusable: true, ariaLabel: `${graph.nodes.find(n => n.id === e.source)?.name} ${symmetric ? '与' : '对'} ${graph.nodes.find(n => n.id === e.target)?.name}：${String(e.data.label ?? e.data.relation_type)}${e.pending ? '（待确认）' : ''}` };
    });
    const involved = new Set(shown.flatMap(e => [e.source, e.target])); const selectedNodes = graph.nodes.filter(n => involved.has(n.id));
    const nodes: PersonNode[] = selectedNodes.map((n, i) => ({ id: String(n.id), type: 'person', data: { label: n.name, cover: n.cover }, position: selectedNodes.length > 16 ? {x: (i % Math.ceil(Math.sqrt(selectedNodes.length))) * 280, y: Math.floor(i / Math.ceil(Math.sqrt(selectedNodes.length))) * 150} : { x: Math.cos(2 * Math.PI * i / Math.max(1, selectedNodes.length)) * Math.max(240, selectedNodes.length * 30) + 350, y: Math.sin(2 * Math.PI * i / Math.max(1, selectedNodes.length)) * Math.max(180, selectedNodes.length * 23) + 250 }, ariaLabel: `查看人物 ${n.name} #${n.id}`, focusable: true }));
    return { nodes, edges, shown };
  }, [graph, relationType, visibility]);
  const name = (id: number) => graph.nodes.find(n => n.id === id)?.name ?? `人物 #${id}`;
  return <section className="kb-relationships" aria-label="人物有向关系" onKeyDownCapture={event => {if(event.key !== 'Enter')return;const target=event.target as HTMLElement;const item=target.closest('.react-flow__node, .react-flow__edge');if(!item)return;const id=item.getAttribute('data-id');if(item.classList.contains('react-flow__node')){const node=graph.nodes.find(n => String(n.id)===id);if(node){event.preventDefault();onNode(node);}}else{const edge=graph.edges.find(e => String(e.id)===id);if(edge){event.preventDefault();onEdge(edge);}}}}><div className="kb-view-tools"><span>{nodes.length} 位人物 · {shown.length} 条关系</span><label>关系类型<select value={relationType} onChange={e => setRelationType(e.target.value)}><option value="">全部</option>{Array.from(new Set(graph.edges.map(e => String(e.data.relation_type)))).map(t => <option key={t}>{t}</option>)}</select></label><label>公开程度<select value={visibility} onChange={e => setVisibility(e.target.value)}><option value="">全部</option><option value="PUBLIC">公开</option><option value="HIDDEN">包含隐藏信息</option></select></label><button onClick={() => setList(v => !v)}>{list ? '切换有向图' : '切换关系列表'}</button></div>
    {graph.truncated && <p className="kb-warning">已到首屏上限（80 个节点 / 200 条边）。请按人物展开查看；当前查询共 {graph.totalEdges} 条关系。</p>}
    {!shown.length ? <p className="kb-empty">此视角暂无已绑定的人物关系。待绑定候选可在下方审核列表中补充。</p> : list ? <div className="kb-table-wrap"><table><caption>箭头表示起点对终点的关系；双向箭头表示对称关系。</caption><thead><tr><th>起点</th><th>方向 / 关系</th><th>终点</th><th>阶段</th><th>审核</th></tr></thead><tbody>{shown.map(e => <tr key={e.id}><td>{name(e.source)}</td><td><button onClick={() => onEdge(e)}>{e.data.direction === 'SYMMETRIC' ? '↔' : '→'} {String(e.data.label ?? e.data.relation_type)}</button></td><td>{name(e.target)}</td><td>{String(e.data.stage ?? '--')}</td><td>{e.pending ? '待确认' : '正式'}</td></tr>)}</tbody></table></div>
      : <div className="kb-graph"><ReactFlow key={JSON.stringify([nodes.map(n => [n.id, n.position, n.data]), edges.map(e => [e.id, e.data])])} defaultNodes={nodes} defaultEdges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} fitView fitViewOptions={{padding: .15, maxZoom: 1.2}} nodesConnectable={false} deleteKeyCode={null} minZoom={.03} maxZoom={2} onNodeClick={(_, n) => { const node = graph.nodes.find(v => String(v.id) === n.id); if (node) onNode(node); }} onEdgeClick={(_, edge) => { const e = graph.edges.find(v => String(v.id) === edge.id); if (e) onEdge(e); }}><Background gap={22} color="#c8c7d0" /><Controls showInteractive={false} /></ReactFlow></div>}
    <p className="kb-muted">点击人物查看档案，点击箭头查看关系和证据。虚线表示待确认。拖动节点只调整当前布局。</p>
  </section>;
}
