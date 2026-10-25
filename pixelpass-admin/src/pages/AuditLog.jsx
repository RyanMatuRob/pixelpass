import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';

const DECISION_STYLES = {
  GRANTED: 'bg-green-100 text-green-800',
  DENIED: 'bg-red-100 text-red-800',
  PROVISIONAL: 'bg-amber-100 text-amber-800'
};

// Rows recorded before the telemetry phase carry no decision; derive it the way the
// gate always behaved so the trail never shows a blank verdict.
const decisionOf = (log) => {
  if (log.accessDecision) return log.accessDecision;
  if (!log.biometricOutcome) return 'DENIED';
  return log.isAnomaly ? 'PROVISIONAL' : 'GRANTED';
};

export default function AuditLog() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchLogs = useCallback(async () => {
    try {
      const { data } = await api.get('/api/logs');
      setLogs(data);
    } catch {
      console.error("Failed to fetch logs");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // One-shot load on mount; the fetch resolves after the render pass, so this is not
    // the derived-state pattern the rule is guarding against.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchLogs();
  }, [fetchLogs]);

  if (loading) return <div className="p-8">Loading security audit trail...</div>;

  return (
    <div className="p-8">
      <div className="flex justify-between items-center mb-8">
        <h1 className="text-3xl font-bold text-gray-900">Access Audit Trail</h1>
        <div className="text-sm text-gray-500 bg-white px-4 py-2 rounded-lg border shadow-sm">
          Showing last 50 scans
        </div>
      </div>

      <div className="bg-white rounded-xl shadow border border-gray-100 overflow-hidden">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gray-900 text-white text-xs uppercase tracking-wider">
              <th className="p-4">Timestamp</th>
              <th className="p-4">User ID / Role</th>
              <th className="p-4">DeepFace Biometrics</th>
              <th className="p-4">Behavioral Anomaly (Isolation Forest)</th>
              <th className="p-4 text-right">Final Decision</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {logs.length === 0 ? (
              <tr>
                <td colSpan="5" className="p-8 text-center text-gray-500">No scan history available.</td>
              </tr>
            ) : (
              logs.map((log) => {
                // Stored decision, or the legacy derivation for older rows.
                const decision = decisionOf(log);

                return (
                  <tr key={log._id} className="hover:bg-gray-50 transition-colors">
                    {/* Timestamp */}
                    <td className="p-4 text-gray-500 whitespace-nowrap">
                      {new Date(log.timestamp).toLocaleString()}
                    </td>

                    {/* Identity */}
                    <td className="p-4">
                      <div className="font-semibold text-gray-900">{log.userId}</div>
                      <div className="text-xs text-gray-500 mt-0.5">{log.roleCategory}</div>
                    </td>

                    {/* Facial Recognition Results */}
                    <td className="p-4">
                      {log.biometricOutcome ? (
                        <span className="text-green-600 font-medium flex items-center gap-1">
                          ✓ Verified ({log.biometricConfidence}%)
                        </span>
                      ) : (
                        <span className="text-red-600 font-medium flex items-center gap-1">
                          ✗ Mismatch ({log.biometricConfidence}%)
                        </span>
                      )}
                    </td>

                    {/* Anomaly Detection Results */}
                    <td className="p-4">
                      {log.isAnomaly ? (
                        <div className="flex flex-col">
                          <span className="text-amber-600 font-bold flex items-center gap-1">
                            ⚠ Anomaly Detected
                          </span>
                          <span className="text-xs text-gray-600">
                            {log.anomalyReason || (log.isAnomaly ? 'Reason not recorded' : 'No deviation')}
                          </span>
                          <span className="text-xs text-gray-500">Score: {log.anomalyScore?.toFixed(2)}</span>
                        </div>
                      ) : (
                        <div className="flex flex-col">
                          <span className="text-gray-600 font-medium flex items-center gap-1">
                            Normal Pattern
                          </span>
                          <span className="text-xs text-gray-400">Score: {log.anomalyScore?.toFixed(2)}</span>
                        </div>
                      )}
                    </td>

                    {/* Overall Decision */}
                    <td className="p-4 text-right">
                      <span className={`px-3 py-1 rounded-full text-xs font-bold ${
                        DECISION_STYLES[decision] || DECISION_STYLES.DENIED
                      }`}>
                        {decision}
                      </span>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}