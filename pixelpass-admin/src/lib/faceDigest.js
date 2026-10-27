// POST /api/visitors/accept wants an embedding, and this testing phase runs no model in the
// browser, so the portal sends a SHA-256 digest of the captured bytes as that stand-in. The
// capture itself travels too: the terminal compares against the stored image, not the digest.
const DATA_URL_PREFIX = /^data:[^,]*;base64,/i;
const BASE64_BODY = /^[A-Za-z0-9+/]+={0,2}$/;

export const base64ToBytes = (dataUrl) => {
    const body = String(dataUrl || '').replace(DATA_URL_PREFIX, '').replace(/\s+/g, '');
    if (!body || !BASE64_BODY.test(body)) {
        throw new Error('The captured frame is not valid Base64');
    }

    const binary = atob(body);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
        bytes[index] = binary.charCodeAt(index);
    }

    return bytes;
};

export const toHex = (buffer) =>
    Array.from(new Uint8Array(buffer), (byte) => byte.toString(16).padStart(2, '0')).join('');

// Web Crypto only exists in a secure context (https:// or localhost), which is the same
// condition the camera needs, so a failure here means the portal was opened over plain http.
export const sha256Hex = async (bytes) => {
    if (!globalThis.crypto || !globalThis.crypto.subtle) {
        throw new Error('This page cannot hash the selfie. Open it over HTTPS or localhost.');
    }

    const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
    return toHex(digest);
};

export const faceDigest = (dataUrl) => sha256Hex(base64ToBytes(dataUrl));
