import { useCallback, useEffect, useRef, useState } from 'react';
import { Camera, LoaderCircle, RotateCcw, TriangleAlert } from 'lucide-react';

// The long edge is capped so the JPEG fits comfortably in the JSON body while still giving
// the gate enough pixels to compare against the enrolled reference.
const MAX_EDGE = 720;

const describeCameraError = (error) => {
    switch (error?.name) {
        case 'NotAllowedError':
        case 'SecurityError':
            return 'Camera access was blocked. Allow the camera for this site in your browser, then try again.';
        case 'NotFoundError':
        case 'OverconstrainedError':
            return 'No front camera was found on this device.';
        case 'NotReadableError':
            return 'Another app is holding the camera. Close it and try again.';
        default:
            return 'The camera could not start on this device.';
    }
};

export default function VisitorCamera({ onCapture }) {
    const videoRef = useRef(null);
    const canvasRef = useRef(null);
    const streamRef = useRef(null);

    // A missing mediaDevices object is the plain-http case: browsers hide the API off a
    // secure origin, so the page can explain that instead of failing silently.
    const isSupported = Boolean(globalThis.navigator?.mediaDevices?.getUserMedia);

    const [status, setStatus] = useState(isSupported ? 'starting' : 'failed');
    const [error, setError] = useState(
        isSupported ? null : 'This browser cannot open a camera here. Use HTTPS or localhost.'
    );
    const [attempt, setAttempt] = useState(0);

    // Releasing tracks is mandatory on mobile: a live stream keeps the camera busy and the
    // next getUserMedia call fails while the previous one is still open.
    const releaseStream = useCallback(() => {
        streamRef.current?.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
    }, []);

    useEffect(() => {
        if (!isSupported) {
            return undefined;
        }

        let cancelled = false;

        const start = async () => {
            try {
                const stream = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
                    audio: false
                });

                // StrictMode mounts twice in development; the first stream must not stay live.
                if (cancelled) {
                    stream.getTracks().forEach((track) => track.stop());
                    return;
                }

                streamRef.current = stream;
                if (videoRef.current) {
                    videoRef.current.srcObject = stream;
                }
            } catch (caught) {
                if (!cancelled) {
                    setStatus('failed');
                    setError(describeCameraError(caught));
                }
            }
        };

        start();

        return () => {
            cancelled = true;
            releaseStream();
        };
    }, [attempt, isSupported, releaseStream]);

    const handleRetry = () => {
        setStatus('starting');
        setError(null);
        setAttempt((value) => value + 1);
    };

    const handleCapture = () => {
        const video = videoRef.current;
        const canvas = canvasRef.current;
        if (!video || !canvas || !video.videoWidth) {
            return;
        }

        // Portrait phones report the rotated frame size, so scale on the long edge either way.
        const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
        canvas.width = Math.round(video.videoWidth * scale);
        canvas.height = Math.round(video.videoHeight * scale);

        const context = canvas.getContext('2d');
        context.drawImage(video, 0, 0, canvas.width, canvas.height);

        // The preview is mirrored for comfort; the capture is not, because the gate compares
        // the real orientation of the face.
        onCapture(canvas.toDataURL('image/jpeg', 0.8));
    };

    return (
        <div className="space-y-5">
            <div className="relative aspect-[3/4] overflow-hidden rounded-3xl bg-gray-900">
                <video
                    ref={videoRef}
                    autoPlay
                    playsInline
                    muted
                    onCanPlay={() => setStatus('live')}
                    className="h-full w-full -scale-x-100 object-cover"
                />

                <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                    <div className="h-3/5 w-3/5 rounded-[50%] border-2 border-white/70" />
                </div>

                {status === 'starting' && (
                    <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-gray-900/80 text-gray-200">
                        <LoaderCircle className="h-7 w-7 animate-spin" />
                        <p className="text-sm font-medium">Starting the camera...</p>
                    </div>
                )}

                {status === 'failed' && (
                    <div
                        role="alert"
                        className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-gray-900/90 px-6 text-center text-gray-200"
                    >
                        <TriangleAlert className="h-8 w-8 text-amber-400" />
                        <p className="text-sm font-medium leading-relaxed">{error}</p>
                        <button
                            type="button"
                            onClick={handleRetry}
                            className="mt-1 flex items-center gap-2 rounded-xl bg-white/10 px-4 py-2 text-sm font-semibold text-white hover:bg-white/20"
                        >
                            <RotateCcw className="h-4 w-4" />
                            Try again
                        </button>
                    </div>
                )}
            </div>

            <canvas ref={canvasRef} className="hidden" aria-hidden="true" />

            <button
                type="button"
                onClick={handleCapture}
                disabled={status !== 'live'}
                className="flex w-full items-center justify-center gap-2 rounded-2xl bg-[#3D2DE0] py-4 text-base font-semibold text-white shadow-lg shadow-[#3D2DE0]/25 transition-colors hover:bg-[#2d21a6] disabled:cursor-not-allowed disabled:opacity-50"
            >
                <Camera className="h-5 w-5" />
                Capture selfie
            </button>

            <p className="text-center text-xs text-gray-400">
                Your face should fill the oval. Avoid backlight and strong shadows.
            </p>
        </div>
    );
}
