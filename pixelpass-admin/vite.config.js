import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'

const projectRoot = fileURLToPath(new URL('.', import.meta.url))

// Azure runs one App Service for the console and the API, so the production bundle is emitted
// straight into the backend's static folder instead of a separate hosting surface.
const backendPublicDir = fileURLToPath(new URL('../pixelpass-backend/public', import.meta.url))

// The visitor portal is tested from a physical phone on the same network, so the dev server
// listens on every interface, forwards /api to the backend, and can serve self-signed HTTPS.
// Browsers only expose the camera (and Web Crypto) on a secure origin, which a plain http://
// LAN address is not; VITE_DEV_HTTPS=true keeps the laptop default unchanged.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, projectRoot, '')

  return {
    plugins: [
      react(),
      tailwindcss(),
      ...(env.VITE_DEV_HTTPS === 'true' ? [basicSsl()] : []),
    ],
    build: {
      outDir: backendPublicDir,
      // outDir sits outside the Vite root, so Vite refuses to clean it unless told to.
      emptyOutDir: true,
    },
    server: {
      host: true,
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://127.0.0.1:3000',
          changeOrigin: true,
        },
      },
    },
  }
})
