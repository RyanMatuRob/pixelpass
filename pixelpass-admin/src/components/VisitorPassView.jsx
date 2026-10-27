import { useEffect, useMemo, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { BadgeCheck, Clock, Sun, TriangleAlert } from 'lucide-react';

const pad = (value) => String(value).padStart(2, '0');

const formatRemaining = (milliseconds) => {
    const total = Math.max(0, Math.floor(milliseconds / 1000));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;

    return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
};

export default function VisitorPassView({ pass, qrToken, visitorName }) {
    // `exp` is the claim the gate enforces, so the countdown is derived from it rather than
    // from the invite row: the API rounds `exp` down to whole seconds.
    const expiresAt = useMemo(() => {
        const exp = Number(pass?.payload?.exp);
        if (Number.isFinite(exp) && exp > 0) {
            return exp * 1000;
        }

        const fromInvite = new Date(pass?.validUntil || 0).getTime();
        return Number.isFinite(fromInvite) ? fromInvite : 0;
    }, [pass]);

    const [now, setNow] = useState(() => Date.now());

    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, []);

    const remaining = Math.max(0, expiresAt - now);
    const expiryLabel = expiresAt
        ? new Date(expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        : '';

    if (!qrToken || remaining <= 0) {
        return (
            <div role="alert" className="space-y-3 text-center">
                <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-red-100 text-red-600">
                    <TriangleAlert className="h-7 w-7" />
                </div>
                <h2 className="text-xl font-bold text-gray-900">This pass has expired</h2>
                <p className="text-sm text-gray-500">
                    Ask your host for a new invite link before you approach the gate.
                </p>
            </div>
        );
    }

    return (
        <div className="space-y-5 text-center">
            <div className="flex flex-col items-center gap-2">
                <div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
                    <BadgeCheck className="h-7 w-7" />
                </div>
                <h2 className="text-xl font-bold text-gray-900">Your pass is ready</h2>
                <p className="text-sm text-gray-500">
                    {visitorName ? `${visitorName}, show` : 'Show'} this code at the security gate and
                    look at the terminal camera.
                </p>
            </div>

            <div className="flex items-start gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-left">
                <Sun className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" />
                <p className="text-sm font-medium text-amber-900">
                    Turn your screen brightness up before you reach the gate.
                </p>
            </div>

            <div className="inline-flex rounded-3xl border-2 border-gray-200 bg-white p-4 shadow-sm">
                <QRCodeSVG
                    value={qrToken}
                    size={256}
                    level="M"
                    marginSize={2}
                    bgColor="#ffffff"
                    fgColor="#000000"
                />
            </div>

            <div className="flex items-center justify-center gap-2 rounded-2xl bg-gray-900 px-4 py-3 text-white">
                <Clock className="h-4 w-4 text-emerald-400" />
                <span className="font-mono text-lg font-semibold tabular-nums">
                    {formatRemaining(remaining)}
                </span>
                <span className="text-xs text-gray-300">
                    left{expiryLabel ? ` \u00b7 expires ${expiryLabel}` : ''}
                </span>
            </div>

            <p className="text-xs text-gray-400">
                Keep this screen open. The code stops working when the timer runs out.
            </p>
        </div>
    );
}
