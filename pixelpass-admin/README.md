# PixelPass Admin Console

Vite + React SPA for the PixelPass operations console, plus the public visitor check-in
portal that visitors open from an invite link.

## Requirements and scripts

Node.js 24 and the backend running on port 3000.

```powershell
npm install; npm run dev; npm run lint; npm run build
```

## Routes

| Route | Access | Purpose |
| --- | --- | --- |
| `/login` | public | Admin sign-in |
| `/invite?token=<token>` | public | Visitor check-in portal: camera selfie and signed QR pass |
| `/invite/<token>` | public | Same portal, path form of the same link |
| `/enroll?token=<token>` | public | Legacy selfie uploader, kept for links minted before `/invite` |
| `/dashboard`, `/users`, `/scans`, `/visitors`, `/logs` | admin session | Console pages |

## Environment

Local overrides live in `.env.local`, which Git ignores.

| Variable | Default | Meaning |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `http://localhost:3000` | Absolute API base for the signed-in console |
| `VITE_DEV_API_TARGET` | `http://127.0.0.1:3000` | Where the dev server proxies `/api` |
| `VITE_DEV_HTTPS` | unset | `true` serves the dev server over self-signed HTTPS |

The visitor portal calls `/api` on its own origin instead of the absolute console base, so the
same build works behind the dev proxy, behind a reverse proxy, and on a phone over HTTPS
without mixed-content errors.

## Testing the visitor flow on a physical phone

Browsers expose the camera and Web Crypto only on a secure origin (`https://` or `localhost`),
so a plain `http://<lan-ip>:5173` address cannot capture a selfie. The dev server takes three
settings to make the phone path work:

1. Start the backend, which already listens on every interface:
   `cd pixelpass-backend; npm run dev`
2. Create `pixelpass-admin/.env.local` with `VITE_DEV_HTTPS=true`, then run
   `npm run dev -- --host`. Vite prints the network URL, for example `https://192.168.1.5:5173/`.
3. Point invite links at that address in `pixelpass-backend/.env`, then restart the backend:
   `INVITE_BASE_URL=https://192.168.1.5:5173/invite`
4. Mint an invite on `/visitors`, copy the generated link, and open it on the phone. Accept the
   certificate warning once: the certificate is self-signed, and the warning is expected. Allow
   the camera when the browser asks.
5. The portal shows the QR pass with a brightness reminder and a countdown that follows the
   token's `exp` claim. Present it at the gate.

Notes:

- The portal posts a SHA-256 digest of the capture as `faceEmbedding`, the browser-side stand-in
  for a face embedding until a model runs in the browser, and the JPEG itself as
  `referenceFaceBase64`. The API stores the JPEG because `/api/scan` compares it at the gate.
- Without `VITE_DEV_HTTPS`, `http://localhost:5173/invite?token=...` still works on the laptop
  itself, because browsers trust `localhost`.

