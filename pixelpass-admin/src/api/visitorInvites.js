import axios from 'axios';

// The visitor portal is public, so it deliberately does not reuse the console client: that
// one attaches the admin bearer token and redirects to /login on a 401. A relative base keeps
// the request on the origin the phone loaded, which the Vite dev server proxies to the API
// and which avoids mixed content when the portal itself is served over HTTPS.
const baseURL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '');

const http = axios.create({
    baseURL,
    timeout: 20000,
    headers: { 'Content-Type': 'application/json' }
});

const toFailure = (error, fallback) => ({
    status: error?.response?.status || 0,
    message: error?.response?.data?.error || error?.response?.data?.message || fallback
});

// GET /api/visitors/invite/:token - what the visitor's browser needs before the selfie.
export const lookupInvite = async (token) => {
    try {
        const { data } = await http.get(`/api/visitors/invite/${encodeURIComponent(token)}`);
        return { invite: data.invite };
    } catch (error) {
        return {
            error: toFailure(
                error,
                'Could not reach the PixelPass server. Check the network and try again.'
            )
        };
    }
};

// POST /api/visitors/accept. The digest is the browser-side stand-in for an embedding; the
// JPEG is the reference the gate compares against, and the API keeps the decodable image
// when both fields arrive.
export const acceptInvite = async ({ token, faceHash, faceDataUrl }) => {
    try {
        const { data } = await http.post('/api/visitors/accept', {
            inviteToken: token,
            faceEmbedding: faceHash,
            referenceFaceBase64: faceDataUrl
        });

        return {
            pass: data.pass,
            qrToken: data.qrToken || data.pass?.token,
            invite: data.invite,
            visitor: data.visitor
        };
    } catch (error) {
        return { error: toFailure(error, 'Could not create your pass.') };
    }
};
