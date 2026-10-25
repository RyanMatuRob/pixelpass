import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, ScanLine, ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react';
import api, { errorMessage } from '../api/client';

const DECISION_FILTERS = [
  { value: '', label: 'All decisions' },
  { value: 'GRANTED', label: 'Granted' },
  { value: 'DENIED', label: 'Blocked' },
  { value: 'PROVISIONAL', label: 'Provisional' }
];

const LIMIT_OPTIONS = [50, 100, 200];

const DECISION_STYLES = {
  GRANTED: { label: 'Granted', badge: 'bg-emerald-100 text-emerald-700', Icon: ShieldCheck },
  DENIED: { label: 'Blocked', badge: 'bg-red-100 text-red-700', Icon: ShieldX },
  PROVISIONAL: { label: 'Provisional', badge: 'bg-amber-100 text-amber-700', Icon: ShieldAlert }
};

// Rows written before the telemetry phase have no stored decision, so derive one from the
// biometric outcome the way the console always displayed it.
const decisionOf = (log) => {
  if (log.accessDecision) return log.accessDecision;
  if (!log.biometricOutcome) return 'DENIED';
  return log.isAnomaly ? 'PROVISIONAL' : 'GRANTED';
};

const shortId = (value) => (value && value.length > 10 ? `…${value.slice(-8)}` : value || 'unknown');

const formatTime = (value) => new Date(value).toLocaleString();

export default function Scans() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [decision, setDecision] = useState('');
  const [onlyAnomalies, setOnlyAnomalies] = useState(false);
  const [limit, setLimit] = useState(50);

  const loadLogs = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get('/api/logs', {
        params: {
          decision: decision || undefined,
          isAnomaly: onlyAnomalies ? true : undefined,
          limit
        }
      });
      setLogs(data);
      setError(null);
    } catch (err) {
      setError(errorMessage(err, 'Could not load scan telemetry'));
    } finally {
      setLoading(false);
    }
  }, [decision, onlyAnomalies, limit]);

  useEffect(() => {
    // Filter-driven load; the request resolves after the render pass.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadLogs();
  }, [loadLogs]);

  const counts = logs.reduce(
    (acc, log) => {
      acc[decisionOf(log)] += 1;
      if (log.isAnomaly) acc.anomalies += 1;
      return acc;
    },
    { GRANTED: 0, DENIED: 0, PROVISIONAL: 0, anomalies: 0 }
  );

  return (
    <div className="p-8 space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Scan Telemetry</h1>
          <p className="text-gray-500 mt-1">Every gate scan, newest first.</p>
        </div>
        <div className="flex items-center gap-2 text-sm text-gray-500 bg-white px-4 py-2 rounded-lg border shadow-sm">
          <span>{counts.GRANTED} granted</span>
          <span className="text-gray-300">|</span>
          <span>{counts.DENIED} blocked</span>
          <span className="text-gray-300">|</span>
          <span>{counts.PROVISIONAL} provisional</span>
          <span className="text-gray-300">|</span>
          <span className="text-amber-600">{counts.anomalies} anomalies</span>
        </div>
      </header>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <section className="flex flex-wrap items-center gap-3">
        <select
          value={decision}
          onChange={(event) => setDecision(event.target.value)}
          className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 shadow-sm"
        >
          {DECISION_FILTERS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>

        <select
          value={limit}
          onChange={(event) => setLimit(Number(event.target.value))}
          className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 shadow-sm"
        >
          {LIMIT_OPTIONS.map((option) => (
            <option key={option} value={option}>Last {option}</option>
          ))}
        </select>

        <label className="inline-flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 shadow-sm">
          <input
            type="checkbox"
            checked={onlyAnomalies}
            onChange={(event) => setOnlyAnomalies(event.target.checked)}
            className="h-4 w-4 rounded border-gray-300"
          />
          Anomalies only
        </label>

        <button
          type="button"
          onClick={loadLogs}
          disabled={loading}
          className="inline-flex items-center gap-2 rounded-lg bg-gray-900 px-3 py-2 text-sm font-semibold text-white hover:bg-gray-800 disabled:opacity-50"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </section>

      <section className="bg-white rounded-xl shadow border border-gray-100 overflow-hidden">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gray-900 text-white text-xs uppercase tracking-wider">
              <th className="p-4">Scan time</th>
              <th className="p-4">Identity</th>
              <th className="p-4">Decision</th>
              <th className="p-4">Biometrics</th>
              <th className="p-4">Anomaly detail</th>
              <th className="p-4">Terminal / device</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {loading && logs.length === 0 ? (
              <tr>
                <td colSpan="6" className="p-8 text-center text-gray-500">Loading scan telemetry…</td>
              </tr>
            ) : logs.length === 0 ? (
              <tr>
                <td colSpan="6" className="p-10 text-center">
                  <ScanLine className="w-8 h-8 text-gray-300 mx-auto mb-3" />
                  <p className="text-gray-500 text-sm">
                    No scans match these filters. Gate scans appear here as soon as a terminal reports one.
                  </p>
                </td>
              </tr>
            ) : (
              logs.map((log) => {
                const style = DECISION_STYLES[decisionOf(log)];
                return (
                  <tr
                    key={log._id}
                    className={log.isAnomaly ? 'bg-amber-50/40 hover:bg-amber-50' : 'hover:bg-gray-50'}
                  >
                    <td className="p-4 text-gray-500 whitespace-nowrap">{formatTime(log.timestamp)}</td>
                    <td className="p-4">
                      <p className="font-semibold text-gray-900" title={log.userId}>{shortId(log.userId)}</p>
                      <p className="text-xs text-gray-500 mt-0.5">{log.roleCategory}</p>
                    </td>
                    <td className="p-4">
                      <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-bold ${style.badge}`}>
                        <style.Icon className="w-3.5 h-3.5" />
                        {style.label}
                      </span>
                    </td>
                    <td className="p-4">
                      <p className={log.biometricOutcome ? 'text-emerald-700 font-medium' : 'text-red-700 font-medium'}>
                        {log.biometricOutcome ? 'Verified' : 'Mismatch'}
                      </p>
                      <p className="text-xs text-gray-500 mt-0.5">
                        {Number(log.biometricConfidence ?? 0).toFixed(1)}% confidence
                      </p>
                    </td>
                    <td className="p-4">
                      {log.isAnomaly ? (
                        <>
                          <p className="font-medium text-amber-700">
                            {log.anomalyReason || 'Flagged without a recorded reason'}
                          </p>
                          <p className="text-xs text-gray-500 mt-0.5">
                            Score {Number(log.anomalyScore ?? 0).toFixed(3)}
                          </p>
                        </>
                      ) : (
                        <p className="text-gray-500">Normal pattern</p>
                      )}
                    </td>
                    <td className="p-4 text-gray-500">
                      <p>
                        {Number(log.terminalCoordinate?.latitude ?? 0).toFixed(4)},{' '}
                        {Number(log.terminalCoordinate?.longitude ?? 0).toFixed(4)}
                      </p>
                      <p className="text-xs text-gray-400" title={log.deviceFingerprint}>
                        {shortId(log.deviceFingerprint)}
                      </p>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}
