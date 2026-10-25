import { ShieldAlert, Users, ScanLine, Ban } from 'lucide-react';

// Headline cards are wired to /api/admin/metrics in the telemetry phase; the layout is
// fixed here so the routing shell ships with its final shape.
const METRICS = [
    { key: 'totalUsers', label: 'Registered accounts', icon: Users, accent: 'text-blue-600' },
    { key: 'scansToday', label: 'Scans today', icon: ScanLine, accent: 'text-emerald-600' },
    { key: 'blockedToday', label: 'Blocked today', icon: Ban, accent: 'text-red-600' },
    { key: 'anomaliesToday', label: 'ML anomalies', icon: ShieldAlert, accent: 'text-amber-500' }
];

export default function Dashboard() {
    return (
        <div className="p-8 space-y-8">
            <header>
                <h1 className="text-3xl font-bold text-gray-900">Command Center</h1>
                <p className="text-gray-500 mt-1">
                    Access health, ML anomalies and recent scan activity at a glance.
                </p>
            </header>

            <section className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6">
                {METRICS.map(({ key, label, icon: Icon, accent }) => (
                    <article key={key} className="bg-white rounded-xl shadow border border-gray-100 p-6">
                        <div className="flex items-center justify-between">
                            <p className="text-sm font-medium text-gray-500">{label}</p>
                            <Icon className={`w-5 h-5 ${accent}`} />
                        </div>
                        <p className="mt-3 text-3xl font-bold text-gray-900">—</p>
                    </article>
                ))}
            </section>

            <section className="bg-white rounded-xl shadow border border-gray-100 p-6">
                <div className="flex items-center gap-2 mb-4">
                    <ShieldAlert className="w-5 h-5 text-amber-500" />
                    <h2 className="text-lg font-bold text-gray-900">ML anomaly alerts</h2>
                </div>
                <p className="text-sm text-gray-500">
                    Isolation Forest flags — impossible travel, scan bursts and replay attacks — appear here once
                    the telemetry feed is connected.
                </p>
            </section>
        </div>
    );
}
