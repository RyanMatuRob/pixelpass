# PixelPass Deployment Runbook

Production runs on Microsoft Azure with two Linux App Services and a managed MongoDB:

```
                Azure App Service: pixelpass-api              Azure App Service: pixelpass-ml
  Browser  -->  Express 5 (API + /admin React build)  -->  FastAPI + DeepFace + IsolationForest
  Phone    -->  /api/auth, /api/scan, /api/visitors          POST /predict
                        |                                            ^
                        +--> MongoDB Atlas (users, invites, logs) ----+
```

One App Service serves the console and the API, so the SPA calls `/api/...` same-origin and no
CORS or mixed-content rules are needed in production. The ML service stays separate because
TensorFlow needs its own memory budget and release cadence.

## What the pipelines do

| Workflow | Trigger | Steps |
| --- | --- | --- |
| `.github/workflows/deploy-backend.yml` | `push` to `main` touching `pixelpass-admin/**` or `pixelpass-backend/**`, or manual dispatch | `npm ci` + `npm run build` in `pixelpass-admin` (emits into `pixelpass-backend/public`), syntax checks, then `azure/webapps-deploy` of `pixelpass-backend` |
| `.github/workflows/deploy-ml.yml` | `push` to `main` touching `pixelpass-ml-service/**`, or manual dispatch | Python syntax and startup-script checks, model artifact check, then `azure/webapps-deploy` of `pixelpass-ml-service` |

Both workflows fail before deploying when the app name or publish profile is missing. App Service
builds the server-side dependencies itself (Oryx runs `npm install` / `pip install -r
requirements.txt`), so the uploaded archive never contains `node_modules` or a virtualenv.

## 1. Create the Azure resources

Sections 1 to 3 are automated by `scripts/provision-azure.ps1`. Preview every command first, then
run it for real:

```powershell
az login
.\scripts\provision-azure.ps1 -DryRun
.\scripts\provision-azure.ps1 -MongoUri 'mongodb+srv://user:pass@cluster0.abc.mongodb.net/pixelpass'
```

The script is idempotent, generates the JWT secret with a CSPRNG, uploads both publish profiles as
GitHub secrets, shreds the downloaded profiles afterwards, and prints the resulting URLs. It also
avoids the two settings that silently break a hand-built deployment: `MONGO_URI` versus
`MONGODB_URI`, and a `/predict` suffix on `ML_SERVICE_URL`. The commands below are the same steps
written out, for portal users and for auditing what the script does.

Values used below are placeholders; keep them consistent with the GitHub variables in step 3.

```powershell
$rg = 'pixelpass-rg'
az group create --name $rg --location westeurope

# Backend plan: one small Linux plan is enough for Express plus the static console.
az appservice plan create --name pixelpass-api-plan --resource-group $rg --sku B1 --is-linux
az webapp create --name pixelpass-api --resource-group $rg --plan pixelpass-api-plan --runtime "NODE|22-lts"
az webapp config set --name pixelpass-api --resource-group $rg --linux-fx-version "NODE|22-lts" --startup-file "npm start"

# ML plan: TensorFlow plus Facenet need about 2 GB resident, so it gets its own plan.
# B2 is 3.5 GB and the cheapest tier that survives the first /predict; P1v3 is the safe upgrade.
az appservice plan create --name pixelpass-ml-plan --resource-group $rg --sku B2 --is-linux
az webapp create --name pixelpass-ml --resource-group $rg --plan pixelpass-ml-plan --runtime "PYTHON|3.13"
az webapp config set --name pixelpass-ml --resource-group $rg --linux-fx-version "PYTHON|3.13" --startup-file "bash /home/site/wwwroot/startup.sh"
```

Do not put both Web Apps on one B1 plan: 1.75 GB is shared by every app on the plan, and the
TensorFlow import alone takes most of it. `--startup-file` is the flag that sets the Linux startup
command; `--startup-command` only exists for container settings and fails on `webapp config set`.
Node 20 is end-of-life and the workflow builds with 22.x, so keep `NODE|22-lts`.

Check the runtimes your subscription offers before creating anything: `az webapp list-runtimes
--linux`. If `PYTHON|3.13` is unavailable, use `PYTHON|3.12` and expect pip to resolve slightly
older wheels for the pinned requirements.


## 2. Application settings

Set these in **Portal -> App Service -> Configuration -> Application settings** (or with
`az webapp config appsettings set`). `SCM_DO_BUILD_DURING_DEPLOYMENT=true` is what makes App
Service install dependencies after the zip lands.

### `pixelpass-api` (backend + console)

| Setting | Value | Why |
| --- | --- | --- |
| `SCM_DO_BUILD_DURING_DEPLOYMENT` | `true` | Oryx runs `npm install` for the uploaded package |
| `NODE_ENV` | `production` | Production dependency resolution |
| `MONGO_URI` | `mongodb+srv://...` | Atlas connection string |
| `JWT_SECRET` | long random string | Signs admin sessions and visitor passes; the code falls back to `fallback_secret`, which must never ship |
| `JWT_EXPIRES_IN` | `15m` | Admin session lifetime |
| `ML_SERVICE_URL` | `https://pixelpass-ml.azurewebsites.net` | Backend -> ML endpoint; defaults to `http://127.0.0.1:8000` locally |
| `INVITE_BASE_URL` | `https://pixelpass-api.azurewebsites.net/invite` | Base of the visitor invite link hosts send out |
| `PUBLIC_KEY_PATH` | `src/config/keys/jwtRS256.key.pub` | Public half of the committed RS256 key pair |
| `PORT` | leave unset | App Service injects it; `server.js` already reads `process.env.PORT` |

Two names in that table are easy to get wrong:

- `MONGO_URI` is what `server.js` reads. `MONGODB_URI` is only a fallback inside
  `scripts/seedUsers.js`, so setting it alone leaves the API connecting to `undefined`.
- `ML_SERVICE_URL` is the service root, without `/predict`: `routes/scan.js` appends `/predict`
  itself, so a value ending in `/predict` produces `/predict/predict` and every scan fails.

Do **not** set `PRIVATE_KEY_PATH` and never commit the private key: `services/tokenService.js`
reads it lazily and no route calls it today, so production does not need it. If RS256 signing is
wired in later, inject the PEM as a GitHub secret and write it into the deployed folder during the
workflow instead of committing it.

### `pixelpass-ml` (FastAPI)

| Setting | Value | Why |
| --- | --- | --- |
| `SCM_DO_BUILD_DURING_DEPLOYMENT` | `true` | Oryx runs `pip install -r requirements.txt` |
| `WEBSITES_PORT` | `8000` | Port the platform proxy forwards to |
| `PYTHONUNBUFFERED` | `1` | Streams DeepFace/TensorFlow logs to the log stream |

`startup.sh` binds uvicorn to `0.0.0.0:$PORT`, so no uvicorn flags belong in the startup command.
Keep `/home` persistent (the default for code deployments): DeepFace downloads the ~90 MB Facenet
weights into the home directory on the first `/predict` call, and a wiped home pays that download
plus a cold TensorFlow import (about 9 s on the reference laptop) on every scan.

## 3. GitHub repository configuration

Settings -> Secrets and variables -> Actions.

| Kind | Name | Where it comes from |
| --- | --- | --- |
| Variable | `AZURE_BACKEND_APP_NAME` | e.g. `pixelpass-api` |
| Variable | `AZURE_ML_APP_NAME` | e.g. `pixelpass-ml` |
| Secret | `AZURE_BACKEND_PUBLISH_PROFILE` | Portal -> `pixelpass-api` -> **Download publish profile**, paste the whole XML |
| Secret | `AZURE_ML_PUBLISH_PROFILE` | Portal -> `pixelpass-ml` -> **Download publish profile**, paste the whole XML |

The same profiles can go straight from the CLI into the secrets — no file, no copy-paste. `--body`
takes the multi-line XML; PowerShell reserves `<` for redirection, so `gh secret set X < file` is not
valid here, and `Out-String` keeps the newlines that a raw pipeline would collapse:

```powershell
gh variable set AZURE_BACKEND_APP_NAME --body pixelpass-api
gh variable set AZURE_ML_APP_NAME --body pixelpass-ml

$apiProfile = az webapp deployment list-publishing-profiles --name pixelpass-api --resource-group pixelpass-rg --xml | Out-String
gh secret set AZURE_BACKEND_PUBLISH_PROFILE --body $apiProfile

$mlProfile = az webapp deployment list-publishing-profiles --name pixelpass-ml --resource-group pixelpass-rg --xml | Out-String
gh secret set AZURE_ML_PUBLISH_PROFILE --body $mlProfile
```

`scripts/provision-azure.ps1` does the same thing and shreds the local copies if one is written.
Verify with `gh secret list` and `gh variable list` before triggering a deployment.

Both jobs use the `production` GitHub environment. GitHub creates it on the first run; add
required reviewers there if a human gate before deployment is wanted.


## 4. First deployment

1. Merge the concern branch into `develop` with a merge commit.
2. Open the release pull request `develop` -> `main` and merge it with a merge commit. That push
   is what triggers both workflows.
3. Watch the Actions tab. `Deploy admin and API to Azure` must report the bundle verification and
   the deploy step as green before the console URL is expected to work.

A manual run is also possible before the first release: Actions -> the workflow -> **Run
workflow** on `main`. The workflows only ever deploy what is on `main`, never a feature branch.

## 5. Smoke tests after a deployment

```powershell
# API liveness (never goes through the SPA fallback)
Invoke-RestMethod https://pixelpass-api.azurewebsites.net/health

# ML liveness: FastAPI's generated docs prove uvicorn is bound and DeepFace imported
(Invoke-WebRequest https://pixelpass-ml.azurewebsites.net/docs).StatusCode

# SPA served by the backend, plus a client-side route
(Invoke-WebRequest https://pixelpass-api.azurewebsites.net/login).StatusCode
```

Then in a browser: sign in at `https://pixelpass-api.azurewebsites.net/login` with an admin
account, confirm the dashboard loads data, and scan one staff or visitor pass after pointing the
mobile app at `https://pixelpass-api.azurewebsites.net/`. The first `/predict` after a cold start
can take a minute; wait for `/docs` to answer before scanning.

Rollback: open the last green run of the affected workflow and **Re-run all jobs**; it redeploys
that run's commit. Deployment slots are the next step for zero-downtime swaps.

## 6. Local parity

```powershell
cd pixelpass-admin; npm run build   # writes ../pixelpass-backend/public
cd ..\pixelpass-backend; npm start  # http://localhost:3000 serves the console and the API
```

`npm run dev` in `pixelpass-admin` still talks to `http://localhost:3000` through the API client and
the Vite proxy, so development behaviour is unchanged. `pixelpass-backend/public` is gitignored.

## 7. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Deploy step: `Resource not found` or 401 | Publish profile stale after a plan or app rename | Re-download the profile and replace the secret |
| App starts, every route returns `Cannot GET /` | Static folder empty, or `SCM_DO_BUILD_DURING_DEPLOYMENT=false` | Re-run the backend workflow; confirm `pixelpass-backend/public/index.html` was produced |
| `/` works, deep links 404 | The SPA fallback is not the last middleware | Keep the fallback after all `/api` mounts in `server.js` |
| `Application Error` with `Cannot find module 'express'` | Oryx build skipped | Set `SCM_DO_BUILD_DURING_DEPLOYMENT=true` and redeploy |
| `ImportError: libGL.so.1` on the ML app | GUI OpenCV wheel on a headless image | `opencv-python-headless` is already pinned; redeploy the ML workflow |
| ML returns 503 for a minute after deploy | TensorFlow import plus Facenet weight download | Expected cold start; keep the plan at B2/P1v3 and warm `/docs` first |
| Scan says `Invalid or Expired Token` while the ML log shows a decode error | Seeded staff accounts carry placeholder `referenceFaceBase64` | Use an account with a real enrolled face, or the visitor selfie flow |
| Invite link points at `localhost` | `INVITE_BASE_URL` not set | Set it to `https://<api-app>.azurewebsites.net/invite` and restart |

## 8. Known gaps

- The ML service is deployed as code (Oryx + `startup.sh`), not as a container image. Containerising
  needs an Azure Container Registry, a registry credential secret, and a `webapps-deploy` step with
  `images:`; the `/predict` contract does not change. Tracked in #43 and #56.
- The backend has no automated tests yet (#50), so the pipelines only check syntax and the bundle.
- `JWT_SECRET` has an insecure code fallback, and the deployment still starts without it: verify the
  setting exists before the release.
- `origin/main` still lacks the back-merge of the last release; run the back-merge after the release
  PR merges so `develop` and `main` share history.
