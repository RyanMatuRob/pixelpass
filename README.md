# PixelPass — Multi-Modal Secure Access System

PixelPass is an offline-resilient physical access control platform that combines hardware-backed cryptographically signed QR passes (`SHA256withECDSA` via Android Keystore), biometric facial verification (on-device Google ML Kit geometric alignment paired with DeepFace `Facenet`), and unsupervised machine learning anomaly detection (Scikit-Learn `IsolationForest` + deterministic spatial-temporal explainability).

---

## Repository Architecture

| Component | Path | Technology Stack | Purpose |
| :--- | :--- | :--- | :--- |
| **Android Mobile Client** | `pixelpass-mobile/PixelPass` | Kotlin 2.2.10, Jetpack Compose (BOM 2026.02.01), CameraX 1.3.4, Google ML Kit, ZXing 3.5.3, Room 2.8.5, WorkManager 2.10.0, Retrofit 2.11.0 | Generates rotating ECDSA P-256 signed QR passes + 6-digit fallback OTPs, verifies passes and nonces offline, captures aligned face frames, and batch-syncs queued offline logs. |
| **Backend API Gateway** | `pixelpass-backend` | Node.js 22 LTS, Express 5.2.1, Mongoose 9.10.2, `jsonwebtoken` 9.0.3, `bcryptjs` 3.0.3, `@faker-js/faker` 10.6.0 | Stateless JWT authentication, RBAC guards, visitor invitation lifecycle, global nonce replay protection, spatial-temporal Haversine velocity explainability, and static SPA hosting. |
| **Admin Console & Visitor Portal** | `pixelpass-admin` | React 19.2.8, Vite 8.3.0, Tailwind CSS 4.3.3, React Router 7.18.4, Axios 1.20.0 | Command Center telemetry dashboard, `PROVISIONAL` anomaly alert review, individual/batch CSV user registration, account revocation, and public visitor browser selfie enrolment (`/invite`). |
| **ML & Biometric Microservice** | `pixelpass-ml-service` | Python 3.13, FastAPI 0.143.0, Uvicorn 0.54.0, DeepFace 0.0.102 (`Facenet`), TensorFlow 2.21.0 (`tf-keras`), Scikit-Learn 1.6.1, Pandas, Joblib 1.6.0 | Exposes `POST /predict` to execute 128-D Facenet facial verification and unsupervised Isolation Forest anomaly scoring (`models/pixelpass_if_model.joblib`). |
| **Cloud & DevOps Tooling** | `scripts/`, `.github/workflows/` | Windows PowerShell 5.1, Azure CLI (`az`), GitHub CLI (`gh`), GitHub Actions OIDC | Idempotent Azure App Service provisioning (`B1` API + `B2` ML in South Africa North), passwordless Entra ID / Managed Identity OIDC federation, and student credit budget guardrails. |

---

## Prerequisites

- **Node.js** `v22 LTS` (or `v24`) and `npm`
- **Python** `3.12` or `3.13`
- **JDK 17** (or Android Studio Ladybug+ bundled JBR) and **Android SDK** (`minSdk = 26`, `compileSdk = 37`)
- **MongoDB** (local instance at `mongodb://localhost:27017/pixelpass_dev` or a **MongoDB Atlas M0** connection string)

---

## Local Setup & Configuration

### 1. Backend API Gateway (`pixelpass-backend`)

Copy the example environment file and configure your MongoDB URI and JWT secret:

```powershell
Set-Location pixelpass-backend
Copy-Item .env.example .env
npm install
```

Required variables in `pixelpass-backend/.env`:

```ini
MONGO_URI=mongodb://localhost:27017/pixelpass_dev
PORT=3000
JWT_SECRET=replace_with_a_strong_random_secret
JWT_EXPIRES_IN=15m
PUBLIC_KEY_PATH=src/config/keys/jwtRS256.key.pub
INVITE_BASE_URL=http://localhost:5173/invite
ML_SERVICE_URL=http://127.0.0.1:8000
```

Seed the development database with **173 deterministic institutional accounts** (`3 ADMIN`, `10 SECURITY`, `40 STAFF`, `100 STUDENT`, `20 VISITOR`) and attach a real reference face photo to the demonstration account (`DEMO-0001` / `demo.student@pixelpass.dev`):

```powershell
npm run seed:users
npm run set:face -- --demo --photo "C:\path\to\reference_face.jpg"
npm run dev
```

The API gateway starts on `http://localhost:3000` (health check: `GET http://localhost:3000/health`).

### 2. Machine Learning & Biometric Microservice (`pixelpass-ml-service`)

Create a virtual environment, install the pinned dependencies, and launch the FastAPI server on port `8000`:

```powershell
Set-Location pixelpass-ml-service
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Verify the OpenAPI interactive readiness endpoint at `http://127.0.0.1:8000/docs`.

### 3. Admin Operations Console & Visitor Portal (`pixelpass-admin`)

For local hot-reload development (`http://localhost:5173`, proxying `/api` to `http://localhost:3000`):

```powershell
Set-Location pixelpass-admin
npm install
npm run dev
```

To build the production SPA bundle directly into `pixelpass-backend/public` so Express serves both the console and the API from the same origin:

```powershell
Set-Location pixelpass-admin
npm run build
Set-Location ..\pixelpass-backend
npm start
```

### 4. Android Mobile Client (`pixelpass-mobile/PixelPass`)

Open `pixelpass-mobile/PixelPass` in Android Studio. The `BASE_URL` in `app/build.gradle.kts` points to the live Azure App Service gateway by default (`https://pixelpass-api-ff4f1e.azurewebsites.net/`) and includes a `getLocalIpAddress()` helper for switching to `http://<local-lan-ip>:3000/` during local LAN testing.

---

## Running Automated Tests

### Android Edge JUnit 4 Suite (81 Unit Tests)

The mobile test suite verifies ECDSA P-256 pass signing, payload tamper detection, clock-skew and TTL enforcement, 6-digit fallback OTP generation, ML Kit upright face oval geometry and mirror cropping, Room SQLite atomic nonce replay protection, and offline scan queueing/replay:

```powershell
Set-Location pixelpass-mobile\PixelPass
.\gradlew.bat testDebugUnitTest --console=plain
```

### Backend & ML Service Verification

```powershell
Set-Location pixelpass-backend
node --check src/server.js
node --check src/routes/scan.js

Set-Location ..\pixelpass-ml-service
python -m py_compile main.py
```

---

## Cloud Deployment & Reproducibility (Microsoft Azure)

Production runs on Microsoft Azure App Service (**South Africa North**) using two Linux plans (`B1` for `pixelpass-api` and `B2` for `pixelpass-ml`) connected to **MongoDB Atlas**. Full provisioning, passwordless GitHub Actions OIDC setup, smoke tests, and troubleshooting are documented in [`DEPLOYMENT.md`](DEPLOYMENT.md).

```powershell
az login
.\scripts\provision-azure.ps1 -DryRun
.\scripts\provision-azure.ps1 -MongoUri "mongodb+srv://<user>:<password>@<cluster>.mongodb.net/pixelpass"
.\scripts\check-azure-budget.ps1 -Detailed
```

Merging a release pull request from `develop` into `main` automatically triggers:
- `.github/workflows/deploy-backend.yml` (compiles `pixelpass-admin` into `pixelpass-backend/public` and deploys to `pixelpass-api` via OIDC).
- `.github/workflows/deploy-ml.yml` (bundles `python-packages` and `models/pixelpass_if_model.joblib`, deploys to `pixelpass-ml`, and probes `/docs` until Uvicorn and Facenet finish warming up).

---

## Security & Repository Hygiene

- Never commit `.env` files, private RSA/ECDSA keys (`*.key`), publish profiles, or unanonymised biometric photographs.
- Version-control workflow, commit conventions (`<type>(<scope>): <summary> [#<issue>]`), and the delivery ledger are maintained in [`AGENTS.md`](AGENTS.md) and [`.github/agents/pixelpass-git.agent.md`](.github/agents/pixelpass-git.agent.md).
