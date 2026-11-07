import { useEffect, useState } from 'react';
import { useImageGenerationRequest } from '../hooks/useImageGenerationRequest';
import { IMAGE_REQUEST_CHANGE, IMAGE_STATUS_LABELS, imageRecovery, replaceImageRequest, trackedImageRequest, trackImageRequest, type ImageRequestSnapshot } from '../api/imageRequests';
import { useToast } from '../ui/toast/useToast';
import { useConfirm } from '../ui/confirm/useConfirm';

export default function ImageRequestStatusPanel({ scope, requestId, onSnapshot }: { scope?: string; requestId?: string; onSnapshot?: (snapshot: ImageRequestSnapshot) => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [tracked, setTracked] = useState(() => requestId || (scope ? trackedImageRequest(scope) : null));
  const [busy, setBusy] = useState(false);
  const { snapshot, error } = useImageGenerationRequest(tracked || requestId || null);
  useEffect(() => { if (snapshot) onSnapshot?.(snapshot); }, [snapshot, onSnapshot]);
  useEffect(() => {
    const update = () => setTracked(requestId || (scope ? trackedImageRequest(scope) : null));
    update();
    window.addEventListener(IMAGE_REQUEST_CHANGE, update);
    return () => window.removeEventListener(IMAGE_REQUEST_CHANGE, update);
  }, [scope, requestId]);
  if (!snapshot && !error) return null;
  const labels: Record<string, string> = { RECHECK: '重新核实', REQUEST_REVIEW: '申请人工核实', RESUME_SAFE: '继续原请求', RELEASE_CONFIRMED: '结束已核实请求', ABANDON_AND_REGENERATE: '放弃旧结果并重新生成' };
  return <section aria-label="生图请求状态" className="rounded-lg border border-border p-3 text-sm my-3 space-y-2">
    {snapshot && <p>{snapshot.status ? IMAGE_STATUS_LABELS[snapshot.status] : '状态暂不可核实'} · {snapshot.completed_images}/{snapshot.total_images} 张</p>}
    {error && <p role="alert">{error}</p>}
    {snapshot?.images.map(item => <div key={item.operation_id} className="space-y-1">
      <p>{item.image_number ? `第 ${item.image_number} 张 · ` : ''}{item.status ? IMAGE_STATUS_LABELS[item.status] : '查询中'}</p>
      {item.evidence_summary && <p>{item.evidence_summary}</p>}
      {item.blocks_serial_lane && <p>此未决调用同时阻断共用串行通道。</p>}
      {item.last_activity_at && <p className="text-text-muted">最近活动：{new Date(item.last_activity_at).toLocaleString()}</p>}
      {item.status === 'NEEDS_REVIEW' && <p className="break-all">关联 ID：{snapshot.request_id} / {item.operation_id}</p>}
      {item.review_requested_at && <p>已申请人工核实</p>}
      {item.recovery_in_progress && <p>正在核实原请求，请等待结果。</p>}
      {item.recovery_actions.map(action => <button key={action} disabled={busy || item.recovery_in_progress} className="mr-3 underline disabled:opacity-50" onClick={async () => {
        if (action === 'ABANDON_AND_REGENERATE') {
          if (!(await confirm({ title: '重新生成', message: `原操作 ${item.operation_id} 已核实结束。继续会建立一次新的付费生成请求；我确认新的费用并放弃旧结果。`, confirmText: '继续生成', tone: 'danger' }))) return;
          setBusy(true);
          void replaceImageRequest(snapshot.request_id, item).then(id => { setTracked(id);if (scope) trackImageRequest(scope, id); toast.success('已创建新的生图请求'); }).catch(error => toast.error(error.message)).finally(() => setBusy(false));
          return;
        }
        setBusy(true);
        void imageRecovery(snapshot.request_id, item, action).then(() => toast.success('恢复操作已提交')).catch(error => toast.error(error.message)).finally(() => setBusy(false));
      }}>{labels[action] || action}</button>)}
    </div>)}
  </section>;
}
