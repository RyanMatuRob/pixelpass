import { ScanLine } from 'lucide-react';

// Chronological telemetry feed. The row renderer and status badges land with the
// telemetry API; this shell reserves the route and its layout.
export default function Scans() {
    return (
        <div className="p-8 space-y-6">
            <header className="flex items-center justify-between">
                <div>
                    <h1 className="text-3xl font-bold text-gray-900">Scan Telemetry</h1>
                    <p className="text-gray-500 mt-1">Every gate scan, newest first.</p>
                </div>
                <div className="text-sm text-gray-500 bg-white px-4 py-2 rounded-lg border shadow-sm">
                    Live feed
                </div>
            </header>

            <section className="bg-white rounded-xl shadow border border-gray-100 p-10 text-center">
                <ScanLine className="w-8 h-8 text-gray-300 mx-auto mb-3" />
                <p className="text-gray-500 text-sm">
                    Scan events are not connected yet. Granted, blocked and offline-sync results will stream in here.
                </p>
            </section>
        </div>
    );
}
