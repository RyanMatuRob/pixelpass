import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { CircleAlert, LoaderCircle, ScanFace, ShieldCheck } from 'lucide-react';
import VisitorCamera from '../components/VisitorCamera';
import VisitorPassView from '../components/VisitorPassView';
import { acceptInvite, lookupInvite } from '../api/visitorInvites';
import { faceDigest } from '../lib/faceDigest';

const formatMoment = (value) => {
    if (!value) {
        return null;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? null
        : date.toLocaleString([], {
              day: 'numeric',
              month: 'short',
              hour: '2-digit',
              minute: '2-digit'
          });
};

const windowLabel = (invite) => {
    const from = formatMoment(invite?.validFrom);
    const until = formatMoment(invite?.validUntil);

    if (from && until) {
        return `${from} - ${until}`;
    }

    return until ? `Until ${until}` : 'Set by your host';
};

function Alert({ message }) {
    return (
        <div
            role="alert"
            className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-left text-sm font-medium text-red-700"
        >
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{message}</span>
        </div>
    );
}

function Detail({ label, value }) {
    return (
        <div className="flex items-start justify-between gap-4">
            <dt className="text-gray-500">{label}</dt>
            <dd className="text-right font-semibold text-gray-900">{value}</dd>
        </div>
    );
}

export default function VisitorCapture() {
    const { token: pathToken } = useParams();
    const [searchParams] = useSearchParams();
    // Minted links carry ?token=; a path token keeps hand-typed URLs working too.
    const token = (pathToken || searchParams.get('token') || '').trim();

    // loading -> welcome -> camera -> submitting -> pass, with invalid as the dead end.
    const [stage, setStage] = useState('loading');
    const [invite, setInvite] = useState(null);
    const [failure, setFailure] = useState(null);
    const [captured, setCaptured] = useState(null);
    const [result, setResult] = useState(null);

    useEffect(() => {
        if (!token) {
            return undefined;
        }

        let cancelled = false;

        const load = async () => {
            const { invite: loaded, error } = await lookupInvite(token);
            if (cancelled) {
                return;
            }

            if (error) {
                setFailure(error.message);
                setStage('invalid');
                return;
            }

            setInvite(loaded);
            setStage('welcome');
        };

        load();

        return () => {
            cancelled = true;
        };
    }, [token]);

    const handleCapture = async (faceDataUrl) => {
        setCaptured(faceDataUrl);
        setFailure(null);
        setStage('submitting');

        try {
            // The digest stands in for an embedding; the JPEG travels with it so the gate can
            // still compare against a real image.
            const faceHash = await faceDigest(faceDataUrl);
            const accepted = await acceptInvite({ token, faceHash, faceDataUrl });

            if (accepted.error) {
                setFailure(accepted.error.message);
                setStage('welcome');
                return;
            }

            setResult(accepted);
            setStage('pass');
        } catch (error) {
            // faceDigest refuses on an insecure origin, where Web Crypto is unavailable.
            setFailure(error.message || 'Could not prepare your selfie.');
            setStage('welcome');
        }
    };

    let content;

    if (!token) {
        content = (
            <div role="alert" className="space-y-3 text-center">
                <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-red-100 text-red-600">
                    <CircleAlert className="h-7 w-7" />
                </div>
                <h2 className="text-xl font-bold text-gray-900">This link is incomplete</h2>
                <p className="text-sm text-gray-500">
                    The invite token is missing. Ask your host to share the full link they received.
                </p>
            </div>
        );
    } else if (stage === 'loading') {
        content = (
            <div className="flex flex-col items-center gap-3 py-8 text-gray-500">
                <LoaderCircle className="h-7 w-7 animate-spin text-[#3D2DE0]" />
                <p className="text-sm font-medium">Checking your invite...</p>
            </div>
        );
    } else if (stage === 'invalid') {
        content = (
            <div role="alert" className="space-y-3 text-center">
                <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-red-100 text-red-600">
                    <CircleAlert className="h-7 w-7" />
                </div>
                <h2 className="text-xl font-bold text-gray-900">This invite cannot be used</h2>
                <p className="text-sm text-gray-500">{failure}</p>
                <p className="text-xs text-gray-400">
                    Invites expire, can be revoked, and work only once.
                </p>
            </div>
        );
    } else if (stage === 'welcome' && invite) {
        content = (
            <div className="space-y-5">
                <div className="text-center">
                    <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-[#EEF0FF] text-[#3D2DE0]">
                        <ScanFace className="h-7 w-7" />
                    </div>
                    <h2 className="text-xl font-bold text-gray-900">You have been invited</h2>
                    <p className="mt-1 text-sm text-gray-500">
                        {invite.host?.name || 'A campus host'} invited {invite.visitorName} to PixelPass.
                    </p>
                </div>

                {failure && <Alert message={failure} />}

                <dl className="space-y-2 rounded-2xl bg-gray-50 p-4 text-sm">
                    <Detail label="Host" value={invite.host?.name || 'Campus host'} />
                    <Detail label="Visit window" value={windowLabel(invite)} />
                </dl>

                <button
                    type="button"
                    onClick={() => {
                        setFailure(null);
                        setStage('camera');
                    }}
                    className="w-full rounded-2xl bg-[#3D2DE0] py-4 text-base font-semibold text-white shadow-lg shadow-[#3D2DE0]/25 transition-colors hover:bg-[#2d21a6]"
                >
                    Start selfie check
                </button>

                <p className="text-center text-xs text-gray-500">
                    Your browser asks for camera access next. Nothing is uploaded until you capture the
                    photo.
                </p>
            </div>
        );
    } else if (stage === 'camera') {
        content = (
            <div className="space-y-4">
                <div>
                    <h2 className="text-xl font-bold text-gray-900">Take your selfie</h2>
                    <p className="mt-1 text-sm text-gray-500">
                        Keep your face inside the oval and hold still.
                    </p>
                </div>
                {failure && <Alert message={failure} />}
                <VisitorCamera onCapture={handleCapture} />
            </div>
        );
    } else if (stage === 'submitting') {
        content = (
            <div className="space-y-4 text-center">
                <h2 className="text-xl font-bold text-gray-900">Creating your pass</h2>
                {captured && (
                    <img
                        src={captured}
                        alt="Captured selfie"
                        className="mx-auto h-40 w-40 rounded-2xl object-cover"
                    />
                )}
                <div className="flex items-center justify-center gap-2 text-sm text-gray-500">
                    <LoaderCircle className="h-4 w-4 animate-spin" />
                    Checking your photo...
                </div>
            </div>
        );
    } else if (stage === 'pass' && result) {
        content = (
            <VisitorPassView
                pass={result.pass}
                qrToken={result.qrToken}
                visitorName={
                    invite?.visitorName ||
                    [result.visitor?.firstName, result.visitor?.lastName].filter(Boolean).join(' ')
                }
            />
        );
    } else {
        content = (
            <div className="flex flex-col items-center gap-3 py-8 text-gray-500">
                <LoaderCircle className="h-7 w-7 animate-spin text-[#3D2DE0]" />
                <p className="text-sm font-medium">Preparing your check-in...</p>
            </div>
        );
    }

    return (
        <div className="flex min-h-screen flex-col bg-[#F8F9FA] font-sans">
            <header className="bg-gray-900 px-6 py-5 text-center">
                <div className="flex items-center justify-center gap-2">
                    <ShieldCheck className="h-5 w-5 text-[#6C5CE7]" />
                    <span className="text-lg font-bold tracking-tight text-white">PixelPass</span>
                </div>
                <p className="mt-1 text-[0.65rem] uppercase tracking-[0.25em] text-gray-400">
                    Visitor check-in
                </p>
            </header>

            <main className="mx-auto w-full max-w-md flex-1 px-4 py-6">
                <div className="rounded-3xl bg-white p-6 shadow-[0_8px_30px_rgb(0,0,0,0.04)]">
                    {content}
                </div>

                <p className="mt-6 text-center text-xs leading-relaxed text-gray-400">
                    Your selfie is used once to verify you at the gate. Ask your host to revoke the
                    invite to remove it.
                </p>
            </main>
        </div>
    );
}
