import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Ban, ScanLine, ShieldAlert, Users } from 'lucide-react';
import api, { errorMessage } from '../api/client';

const EMPTY_METRICS = {
  totalUsers: null,
  activeUsers: null,
  revokedUsers: null,
  enrolledBiometrics: null,
  scansToday: null,
  grantedToday: null,
  blockedToday: null,
  provisionalToday: null,
  anomaliesToday: null,
  totalScans: null,
  totalAnomalies: null,
  lastScanAt: null
};

const ANOMALY_PANEL_LIMIT = 5;

const formatNumber = (value) => (value === null || value === undefined ? '—' : value.toLocaleString());

const shortId = (value) => (value && value.length > 10 ? `…${value.slice(-8)}` : value || 'unknown');

const decisionOf = (log) => {
  if (log.accessDecision) return log.accessDecision;
  if (!log.biometricOutcome) return 'DENIED';
  return log.isAnomaly ? 'PROVISIONAL' : 'GRANTED';
};

export default function Dashboard() {
  const [metrics, setMetrics] = useState(EMPTY_METRICS);
  const [anomalies, setAnomalies] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const [metricsResponse, anomaliesResponse] = await Promise.all([
        api.get('/api/admin/metrics'),
        api.get('/api/logs/anomalies', { params: { limit: ANOMALY_PANEL_LIMIT } })
      ]);
      setMetrics(metricsResponse.data);
      setAnomalies(anomaliesResponse.data);
      setError(null);
    } catch (err) {
      setError(errorMessage(err, 'Could not load dashboard telemetry'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // One-shot load on mount; the requests resolve after the render pass, so this is not
    // the derived-state pattern the rule is guarding against.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadDashboard();
  }, [loadDashboard]);

  const cards = [
    {
      key: 'totalUsers',
      label: 'Registered accounts',
      icon: Users,
      accent: 'text-blue-600',
      detail: `${formatNumber(metrics.activeUsers)} active · ${formatNumber(metrics.revokedUsers)} revoked`
    },
    {
      key: 'scansToday',
      label: 'Scans today',
      icon: ScanLine,
      accent: 'text-emerald-600',
      detail: `${formatNumber(metrics.grantedToday)} granted · ${formatNumber(metrics.provisionalToday)} provisional`
    },
    {
      key: 'blockedToday',
      label: 'Blocked today',
      icon: Ban,
      accent: 'text-red-600',
      detail: `${formatNumber(metrics.totalScans)} scans all time`
    },
    {
      key: 'anomaliesToday',
      label: 'ML anomalies today',
      icon: ShieldAlert,
      accent: 'text-amber-500',
      detail: `${formatNumber(metrics.totalAnomalies)} flagged all time`
    }
  ];

  return (
    <div className="p-8 space-y-8">
      <header>
        <h1 className="text-3xl font-bold text-gray-900">Command Center</h1>
        <p className="text-gray-500 mt-1">
          Access health, ML anomalies and recent scan activity at a glance.
        </p>
      </header>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <section className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6">
        {cards.map(({ key, label, icon: Icon, accent, detail }) => (
          <article key={key} className="bg-white rounded-xl shadow border border-gray-100 p-6">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium text-gray-500">{label}</p>
              <Icon className={`w-5 h-5 ${accent}`} />
            </div>
            <p className="mt-3 text-3xl font-bold text-gray-900">{formatNumber(metrics[key])}</p>
            <p className="mt-1 text-xs text-gray-500">{detail}</p>
          </article>
        ))}
      </section>

      <section className="bg-white rounded-xl shadow border border-gray-100 p-6">
        <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
          <div className="flex items-center gap-2">
            <ShieldAlert className="w-5 h-5 text-amber-500" />
            <h2 className="text-lg font-bold text-gray-900">ML anomaly alerts</h2>
          </div>
          <Link
            to="/scans"
            className="inline-flex items-center gap-1 text-sm font-semibold text-blue-600 hover:text-blue-700"
          >
            Open telemetry
            <ArrowRight className="w-4 h-4" />
          </Link>
        </div>

        {loading && anomalies.length === 0 ? (
          <p className="text-sm text-gray-500">Loading anomaly feed…</p>
        ) : anomalies.length === 0 ? (
          <p className="text-sm text-gray-500">
            Nothing flagged right now. Impossible travel, scan bursts and replay attacks appear here with their
            reason and score.
          </p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {anomalies.map((log) => (
              <li key={log._id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                <div>
                  <p className="font-medium text-amber-700">
                    {log.anomalyReason || 'Flagged without a recorded reason'}
                  </p>
                  <p className="text-xs text-gray-500 mt-0.5">
                    {log.roleCategory || 'Unknown role'} · {shortId(log.userId)} ·{' '}
                    {new Date(log.timestamp).toLocaleString()}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-sm font-bold text-gray-900">
                    Score {Number(log.anomalyScore ?? 0).toFixed(3)}
                  </p>
                  <p className="text-xs text-gray-500">{decisionOf(log).toLowerCase()}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
