import { useState } from 'react';
import { decideNovelDraft } from '../../api/agentRuns';
import { actOnAutomationRun, type AutomationArtifact } from '../../api/automations';
import { randomUUID } from '../../utils/browserSecurity';
import NovelDraftReview from '../NovelDraftReview';

interface Props { runId: string; artifact: AutomationArtifact; onUpdated: () => Promise<void> }

export default function AutomationNovelReview({ runId, artifact, onUpdated }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [responseKey] = useState(() => randomUUID());
  const review = artifact.review;
  if (!review) return null;

  async function resume() {
    setBusy(true); setError('');
    try {
      await actOnAutomationRun(runId, 'respond', responseKey, { decision: 'reviewed', artifactId: artifact.id });
      await onUpdated();
    } catch (e) { setError(e instanceof Error ? e.message : '审阅结果已保存，暂未恢复总结。'); }
    finally { setBusy(false); }
  }

  async function decide(decision: 'confirm' | 'discard', content: string) {
    if (!review || busy) return;
    setBusy(true); setError('');
    try {
      await decideNovelDraft(review.chapterId, review.conversationId, review.requestId, artifact.id, {
        decision, content, base_version: review.baseVersion, expected_content_hash: review.contentHash, continue_agent: true,
      });
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存审阅结果失败，请刷新后重试。');
      setBusy(false);
      return;
    }
    await resume();
  }

  if (saved) return <section className="mt-5 rounded-xl border border-border p-4" aria-live="polite">
    <p>审阅结果已保存，后续总结不会再次修改正文。</p>
    {error && <p role="alert">{error}</p>}
    <button type="button" disabled={busy} onClick={() => { void resume(); }}>{busy ? '正在继续…' : '继续生成总结'}</button>
  </section>;

  return <NovelDraftReview draft={{ requestId: review.requestId, artifactId: artifact.id,
    content: artifact.content ?? '', originalContent: review.originalContent, contentHash: review.contentHash,
    baseVersion: review.baseVersion, busy, error }} stale={review.baseVersion !== review.currentVersion}
    onDecision={(decision, content) => { void decide(decision, content); }} />;
}
