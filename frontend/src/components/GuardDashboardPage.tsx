import { useEffect, useMemo, useState } from 'react';
import { Activity, AlertTriangle, Gauge, RefreshCw, ShieldCheck, TimerReset, Zap } from 'lucide-react';
import {
  getGuardEvents,
  getGuardMetrics,
  getGuardStats,
  type GuardActionStats,
  type GuardEvent,
  type GuardMetricBucket,
  type GuardStatsPayload,
} from '../api';

const ACTION_LABELS: Record<string, string> = {
  'image-gen': 'HTTP Image',
  'generate-scenes': 'Scenes',
  'generate-manga': 'Manga SSE',
  'regenerate-image': 'Regenerate',
};

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function number(value: number): string {
  return new Intl.NumberFormat().format(value || 0);
}

function StatCard({
  icon,
  label,
  value,
  accent,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  accent: string;
}) {
  return (
    <div className="rounded-lg border border-ink-border bg-ink-light/70 px-4 py-3">
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-medium uppercase tracking-wide text-cream-dim">{label}</span>
        <span className={accent}>{icon}</span>
      </div>
      <div className="mt-3 text-2xl font-semibold text-cream">{value}</div>
    </div>
  );
}

function RateBar({ value, tone }: { value: number; tone: string }) {
  return (
    <div className="flex min-w-0 items-center gap-2">
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-lighter">
        <div className={`h-full rounded-full ${tone}`} style={{ width: `${Math.min(100, Math.max(0, value * 100))}%` }} />
      </div>
      <span className="w-12 text-right text-xs tabular-nums text-cream-dim">{percent(value)}</span>
    </div>
  );
}

function MiniMetric({ label, value, tone = 'text-cream' }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-md bg-ink/70 px-2 py-1.5">
      <div className="text-[10px] uppercase tracking-wide text-warm-gray">{label}</div>
      <div className={`mt-0.5 text-sm font-medium tabular-nums ${tone}`}>{value}</div>
    </div>
  );
}

function decisionTone(decision: string): string {
  if (decision === 'leader') return 'border-aizuri/30 bg-aizuri-light/50 text-aizuri';
  if (decision === 'follower') return 'border-kinpaku/30 bg-kinpaku-light/50 text-kinpaku';
  if (decision === 'success_hit' || decision === 'succeeded') return 'border-success/30 bg-success/10 text-success';
  if (decision.includes('rejected')) return 'border-vermilion/30 bg-vermilion-light/30 text-vermilion';
  if (decision.includes('failed')) return 'border-warning/30 bg-warning/10 text-warning';
  return 'border-paper-border bg-paper-surface text-sumi-dim';
}

function formatTime(value: string): string {
  if (!value) return '-';
  return new Date(value).toLocaleTimeString();
}

function formatSummary(summary?: Record<string, unknown>): string {
  if (!summary) return '';
  return Object.entries(summary)
    .map(([key, value]) => {
      if (value && typeof value === 'object') return `${key}=${JSON.stringify(value)}`;
      return `${key}=${String(value)}`;
    })
    .join(' ');
}

function totals(actions: GuardActionStats[]) {
  const sum = actions.reduce(
    (acc, item) => {
      acc.total += item.total;
      acc.leader += item.leader;
      acc.follower += item.follower;
      acc.successHit += item.success_hit;
      acc.failedHit += item.failed_hit;
      acc.rejected += item.follower_rejected + item.processing_rejected;
      acc.failed += item.failed;
      return acc;
    },
    { total: 0, leader: 0, follower: 0, successHit: 0, failedHit: 0, rejected: 0, failed: 0 },
  );
  return {
    ...sum,
    hitRate: sum.total ? sum.successHit / sum.total : 0,
    reuseRate: sum.total ? (sum.successHit + sum.failedHit) / sum.total : 0,
  };
}

function bucketTotals(items: GuardMetricBucket[]) {
  return items.reduce(
    (acc, item) => {
      acc.total += item.total;
      acc.leader += item.leader;
      acc.follower += item.follower;
      acc.successHit += item.success_hit;
      acc.failedHit += item.failed_hit;
      acc.rejected += item.follower_rejected + item.processing_rejected;
      acc.failed += item.failed;
      return acc;
    },
    { total: 0, leader: 0, follower: 0, successHit: 0, failedHit: 0, rejected: 0, failed: 0 },
  );
}

function formatBucketTime(value: string, bucket: string): string {
  if (!value) return '-';
  const date = new Date(value);
  if (bucket === 'DAY') return date.toLocaleDateString();
  if (bucket === 'HOUR') {
    return date.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function GuardDashboardPage() {
  const [data, setData] = useState<GuardStatsPayload | null>(null);
  const [events, setEvents] = useState<GuardEvent[]>([]);
  const [metricBucket, setMetricBucket] = useState('HOUR');
  const [metrics, setMetrics] = useState<GuardMetricBucket[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const load = async (quiet = false) => {
    if (quiet) setRefreshing(true);
    else setLoading(true);
    setError('');
    try {
      const [statsPayload, eventsPayload] = await Promise.all([
        getGuardStats(),
        getGuardEvents(100),
      ]);
      setData(statsPayload);
      setEvents(eventsPayload.events || []);
    } catch (err: any) {
      setError(err?.message || 'Failed to load guard stats');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    load();
    const timer = window.setInterval(() => load(true), 30000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const range = metricBucket === 'MINUTE' ? 60 : metricBucket === 'DAY' ? 30 : 24;
    getGuardMetrics(metricBucket, range)
      .then((payload) => setMetrics(payload.items || []))
      .catch(() => setMetrics([]));
  }, [metricBucket, refreshing, data?.updated_at]);

  const summary = useMemo(() => totals(data?.actions || []), [data]);
  const historySummary = useMemo(() => bucketTotals(metrics), [metrics]);

  return (
    <div className="min-h-dvh bg-ink text-cream">
      <div className="mx-auto flex min-h-dvh w-full max-w-7xl flex-col px-5 py-5">
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-border pb-4">
          <div>
            <div className="flex items-center gap-2 text-sm font-medium text-aizuri">
              <ShieldCheck size={17} />
              Guard Runtime
            </div>
            <h1 className="mt-2 text-2xl font-semibold text-cream">Idempotency & Single-flight Dashboard</h1>
          </div>
          <button
            onClick={() => load(true)}
            disabled={refreshing}
            className="inline-flex h-10 items-center gap-2 rounded-lg border border-paper-border bg-paper-surface px-3 text-sm font-medium text-sumi-dim hover:border-aizuri/60 hover:text-aizuri disabled:opacity-50"
          >
            <RefreshCw size={16} className={refreshing ? 'animate-spin' : ''} />
            Refresh
          </button>
        </header>

        {error && (
          <div className="mt-4 flex items-center gap-2 rounded-lg border border-vermilion/20 bg-vermilion-light/20 px-4 py-3 text-sm text-vermilion">
            <AlertTriangle size={16} />
            {error}
          </div>
        )}

        <section className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <StatCard icon={<Activity size={18} />} label="Total" value={number(summary.total)} accent="text-aizuri" />
          <StatCard icon={<Gauge size={18} />} label="Hit Rate" value={percent(summary.hitRate)} accent="text-success" />
          <StatCard icon={<Zap size={18} />} label="Followers" value={number(summary.follower)} accent="text-amber-accent-light" />
          <StatCard icon={<TimerReset size={18} />} label="Rejected" value={number(summary.rejected)} accent="text-vermilion" />
          <StatCard icon={<AlertTriangle size={18} />} label="Failed" value={number(summary.failed)} accent="text-warning" />
        </section>

        <main className="mt-5 grid flex-1 gap-4 overflow-hidden xl:grid-cols-[minmax(0,1fr)_360px]">
          <section className="overflow-hidden rounded-lg border border-ink-border bg-ink-light/50">
            <div className="flex items-center justify-between border-b border-ink-border px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-cream">Action Metrics</h2>
                <p className="mt-1 text-xs text-cream-dim">
                  Updated {data?.updated_at ? new Date(data.updated_at).toLocaleString() : '-'} · Auto refresh 30s
                </p>
              </div>
            </div>

            <div className="divide-y divide-ink-border">
              {(data?.actions || []).map((item) => (
                <div key={item.action} className="grid gap-3 px-4 py-4 hover:bg-cream/[0.02] lg:grid-cols-[170px_minmax(0,1fr)]">
                  <div>
                    <div className="font-medium text-cream">{ACTION_LABELS[item.action] || item.action}</div>
                    <div className="mt-1 text-xs text-cream-dim">{item.action}</div>
                  </div>
                  <div className="grid min-w-0 gap-3">
                    <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 xl:grid-cols-7">
                      <MiniMetric label="Total" value={number(item.total)} />
                      <MiniMetric label="Leader" value={number(item.leader)} />
                      <MiniMetric label="Follower" value={number(item.follower)} tone="text-amber-accent-light" />
                      <MiniMetric label="Hit" value={number(item.success_hit)} tone="text-success" />
                      <MiniMetric label="Fail Hit" value={number(item.failed_hit)} tone="text-lime-200" />
                      <MiniMetric label="Rejected" value={number(item.follower_rejected + item.processing_rejected)} tone="text-vermilion" />
                      <MiniMetric label="Failed" value={number(item.failed)} tone="text-warning" />
                    </div>
                    <div className="grid gap-2 md:grid-cols-3">
                      <div>
                        <div className="mb-1 text-[10px] uppercase tracking-wide text-warm-gray">Reuse</div>
                        <RateBar value={item.reuse_rate} tone="bg-success" />
                      </div>
                      <div>
                        <div className="mb-1 text-[10px] uppercase tracking-wide text-warm-gray">Single-flight</div>
                        <RateBar value={item.single_flight_rate} tone="bg-kinpaku" />
                      </div>
                      <div>
                        <div className="mb-1 text-[10px] uppercase tracking-wide text-warm-gray">Reject</div>
                        <RateBar value={item.reject_rate} tone="bg-vermilion" />
                      </div>
                    </div>
                  </div>
                </div>
              ))}
              {!loading && !data?.actions?.length && (
                <div className="px-4 py-12 text-center text-sm text-cream-dim">No guard metrics yet.</div>
              )}
              {loading && (
                <div className="px-4 py-12 text-center text-sm text-cream-dim">Loading guard metrics...</div>
              )}
            </div>
          </section>

          <section className="overflow-hidden rounded-lg border border-ink-border bg-ink-light/50">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-border px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold text-cream">Historical Buckets</h2>
                <p className="mt-1 text-xs text-cream-dim">
                  Persistent DB metrics 路 total {number(historySummary.total)} 路 hit {percent(historySummary.total ? historySummary.successHit / historySummary.total : 0)}
                </p>
              </div>
              <div className="flex rounded-lg border border-ink-border bg-ink/80 p-1">
                {[
                  ['MINUTE', '60m'],
                  ['HOUR', '24h'],
                  ['DAY', '30d'],
                ].map(([value, label]) => (
                  <button
                    key={value}
                    onClick={() => setMetricBucket(value)}
                    className={`h-8 rounded-md px-3 text-xs font-medium ${
                      metricBucket === value ? 'bg-aizuri-light/60 text-aizuri' : 'text-cream-dim hover:text-cream'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-left text-xs">
                <thead className="bg-ink/80 text-cream-dim">
                  <tr>
                    <th className="px-4 py-2 font-medium">Bucket</th>
                    <th className="px-3 py-2 font-medium">Action</th>
                    <th className="px-3 py-2 text-right font-medium">Total</th>
                    <th className="px-3 py-2 text-right font-medium">Leader</th>
                    <th className="px-3 py-2 text-right font-medium">Follower</th>
                    <th className="px-3 py-2 text-right font-medium">Hit</th>
                    <th className="px-3 py-2 text-right font-medium">Rejected</th>
                    <th className="px-3 py-2 text-right font-medium">Failed</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-ink-border">
                  {metrics.map((item) => (
                    <tr key={`${item.bucket_start}-${item.action}`} className="hover:bg-cream/[0.02]">
                      <td className="whitespace-nowrap px-4 py-2 text-cream-dim">{formatBucketTime(item.bucket_start, metricBucket)}</td>
                      <td className="px-3 py-2 text-cream">{ACTION_LABELS[item.action] || item.action}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-cream">{number(item.total)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-aizuri">{number(item.leader)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-amber-accent-light">{number(item.follower)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-success">{number(item.success_hit)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-vermilion">{number(item.follower_rejected + item.processing_rejected)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-warning">{number(item.failed)}</td>
                    </tr>
                  ))}
                  {!metrics.length && (
                    <tr>
                      <td colSpan={8} className="px-4 py-8 text-center text-sm text-cream-dim">No persisted metrics yet.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>

          <aside className="min-h-[420px] overflow-hidden rounded-lg border border-ink-border bg-ink-light/50">
            <div className="border-b border-ink-border px-4 py-3">
              <h2 className="text-sm font-semibold text-cream">Recent Requests</h2>
              <p className="mt-1 text-xs text-cream-dim">Latest guard decisions and outcomes</p>
            </div>
            <div className="max-h-[calc(100dvh-260px)] overflow-y-auto p-3">
              {events.map((event) => (
                <div key={event.id} className="mb-3 rounded-lg border border-ink-border bg-ink/70 p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="text-sm font-medium text-cream">{ACTION_LABELS[event.action] || event.action}</div>
                      <div className="mt-1 text-xs text-cream-dim">{formatTime(event.time)} · {event.scope}</div>
                    </div>
                    <span className={`shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-medium ${decisionTone(event.decision)}`}>
                      {event.decision}
                    </span>
                  </div>
                  <div className="mt-3 text-xs leading-relaxed text-cream-dim">
                    {formatSummary(event.summary)}
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-2 text-[11px] text-cream-dim">
                    <span>result={event.result}</span>
                    <span>key={event.key_hash}</span>
                    {event.duration_ms != null && <span>{event.duration_ms}ms</span>}
                  </div>
                  {event.message && <div className="mt-2 text-xs text-cream-dim">{event.message}</div>}
                </div>
              ))}
              {!events.length && (
                <div className="py-12 text-center text-sm text-cream-dim">No request events yet.</div>
              )}
            </div>
          </aside>
        </main>
      </div>
    </div>
  );
}
