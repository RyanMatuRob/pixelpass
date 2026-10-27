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
| `.github/workflows/deploy-backend.yml` | `push` to `main` touching `pixelpass-admin/**` or `pixelpass-backend/**`, or manual dispatch | `npm ci` + `npm run build` in `pixelpass-admin` (emits into `pixelpass-backend/public`), syntax checks, `azure/login` (OIDC), then `azure/webapps-deploy` of `pixelpass-backend` |
| `.github/workflows/deploy-ml.yml` | `push` to `main` touching `pixelpass-ml-service/**`, or manual dispatch | Python syntax and startup-script checks, model artifact check, `pip install --target` into the package on the runner, OIDC sign-in, App Service settings via `az`, then `azure/webapps-deploy` of the prepared zip and a `/docs` probe |

Both workflows sign in with a short-lived OIDC token (`azure/login`, `id-token: write`), so nothing
in the repository is a deployment credential and SCM basic authentication can stay disabled. They
fail before deploying when an `AZURE_*` variable is missing. The backend still lets App Service build
its dependencies (Oryx runs `npm install`), so its archive never contains `node_modules`. The ML
archive is different: the workflow resolves `requirements.txt` on the runner into `python-packages`
and ships it, because the platform build there produced `output.tar.zst` and never unpacked it
(#111).

## 1. Create the Azure resources

Sections 1 to 3 are automated by `scripts/provision-azure.ps1`, which finishes by calling
`scripts/setup-azure-oidc.ps1` to create the identity the workflows sign in with. Preview every
command first, then run it for real:

```powershell
az login
.\scripts\provision-azure.ps1 -DryRun
.\scripts\provision-azure.ps1 -MongoUri 'mongodb+srv://user:pass@cluster0.abc.mongodb.net/pixelpass'
```

The script is idempotent, generates the JWT secret with a CSPRNG, sets the repository variables,
creates the OIDC identity, and prints the resulting URLs. It also avoids the two settings that
silently break a hand-built deployment: `MONGO_URI` versus `MONGODB_URI`, and a `/predict` suffix on
`ML_SERVICE_URL`. The commands below are the same steps written out, for portal users and for
auditing what the script does.

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
`az webapp config appsettings set`). On the backend, `SCM_DO_BUILD_DURING_DEPLOYMENT=true` is what
makes App Service install dependencies after the zip lands. On the ML app the workflow sets the
values below itself on every deploy, so they cannot drift.

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
| `SCM_DO_BUILD_DURING_DEPLOYMENT` | `false` | The zip already carries `python-packages`; a platform build only recompresses the app into an `output.tar.zst` the container never unpacks (#111) |
| `WEBSITES_PORT` | `8000` | Port the platform proxy forwards to |
| `PYTHONPATH` | `/home/site/wwwroot/python-packages` | Makes the bundled dependencies importable; `startup.sh` sets the same path as a fallback |
| `PYTHONUNBUFFERED` | `1` | Streams DeepFace/TensorFlow logs to the log stream |
| `WEBSITES_CONTAINER_START_TIME_LIMIT` | `600` | The TensorFlow import plus the Facenet warm-up exceed the 230 s default before uvicorn binds |

The workflow also turns **Always On** on, so the container is not recycled between visits and a scan
does not pay the cold start. `startup.sh` binds uvicorn to `0.0.0.0:$PORT`, so no uvicorn flags belong
in the startup command. Keep `/home` persistent (the default for code deployments): DeepFace
downloads the ~90 MB Facenet weights into the home directory during the lifespan warm-up, and a
wiped home pays that download plus a cold TensorFlow import (about 9 s on the reference laptop) on
every scan.

## 3. GitHub repository configuration

Settings -> Secrets and variables -> Actions.

| Kind | Name | Where it comes from |
| --- | --- | --- |
| Variable | `AZURE_BACKEND_APP_NAME` | e.g. `pixelpass-api` |
| Variable | `AZURE_ML_APP_NAME` | e.g. `pixelpass-ml` |
| Variable | `AZURE_CLIENT_ID` | Client ID of the deploy identity (application or managed identity), printed by `scripts/setup-azure-oidc.ps1` |
| Variable | `AZURE_TENANT_ID` | Printed by the same script |
| Variable | `AZURE_SUBSCRIPTION_ID` | Printed by the same script |
| Variable | `AZURE_RESOURCE_GROUP` | Resource group that holds both apps |

No publish profile belongs in this repository. The workflows present a token that Azure exchanges
for a short-lived one, which keeps working when SCM and FTP basic authentication are disabled by
policy — the state that makes a publish-profile deploy fail with `Publish profile is invalid for
app-name and slot-name provided`.

The variables can be set by hand, but the identity behind them should come from the script: it
creates the Entra ID application, its service principal, the federated credentials and the role
assignment, then writes the variables.

```powershell
az login
.\scripts\setup-azure-oidc.ps1 -DryRun
.\scripts\setup-azure-oidc.ps1
```

The script prefers an Entra ID application and falls back to a user-assigned managed identity when the
account may not register applications (`Insufficient privileges to complete the operation`), which is
the usual state of a student or locked-down tenant. A managed identity is created through Azure
Resource Manager, needs no directory rights, and `azure/login` accepts its client ID in the same way.
Pass `-IdentityType AppRegistration` or `-IdentityType ManagedIdentity` to force one flavour, and note
that managed identities are refused outside the subscription's allowed regions, so the resource
group's own region is used.

It grants `Contributor` on the resource group only, and adds a federated credential for both
`refs/heads/main` and the `production` environment, because a job that declares an environment
authenticates with the environment subject rather than the branch one. Both the plain and the
id-qualified subject are registered, because GitHub may present the immutable form
`repo:Owner@168424652/Repository@1393706386:environment:production` instead of `repo:Owner/Repository`.
Verify with `gh variable list` before triggering a deployment, then delete the now unused secrets:

```powershell
gh secret delete AZURE_BACKEND_PUBLISH_PROFILE
gh secret delete AZURE_ML_PUBLISH_PROFILE
```

`scripts/provision-azure.ps1` does the same thing in one pass, so a fresh environment does not need
this script on its own.

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
| Deploy step: `Publish profile is invalid for app-name and slot-name provided`, with a 401 on `*.scm.azurewebsites.net` | The job still authenticates with a publish profile, and SCM basic auth is disabled by policy or the profile is stale | Run `scripts/setup-azure-oidc.ps1`; the shipped workflows use OIDC and need no profile |
| `Sign in to Azure` step: `AADSTS70021`, `AADSTS700213` or `No matching federated identity record found` | The federated credential subject does not match the ref, the environment, or the id-qualified form GitHub now sends | Re-run `scripts/setup-azure-oidc.ps1`, which adds both `refs/heads/main` and `environment:production` in plain and immutable forms |
| Script fails at `Entra ID application`: `Insufficient privileges to complete the operation` | The account may not register applications in this tenant | Nothing to fix: the script falls back to a managed identity, or pass `-IdentityType ManagedIdentity` |
| Script fails with `RequestDisallowedByAzure` on `identity create` | Managed identities are restricted to the subscription's allowed regions | Pass `-IdentityLocation` with an allowed region; the script defaults to the resource group's region |
| `Could not read the subscription id from az` | The az context is unusable, or the CLI is installed without being on `PATH` | The script searches the standard install paths; otherwise run `az login`, or pass `-SubscriptionId` and `-TenantId` |
| Deploy step: `Resource not found` | Wrong `AZURE_*_APP_NAME`, or the role assignment is missing | Check `gh variable list`, then re-run `scripts/setup-azure-oidc.ps1` |
| App starts, every route returns `Cannot GET /` | Static folder empty, or `SCM_DO_BUILD_DURING_DEPLOYMENT=false` | Re-run the backend workflow; confirm `pixelpass-backend/public/index.html` was produced |
| `/` works, deep links 404 | The SPA fallback is not the last middleware | Keep the fallback after all `/api` mounts in `server.js` |
| `Application Error` with `Cannot find module 'express'` | Oryx build skipped | Set `SCM_DO_BUILD_DURING_DEPLOYMENT=true` and redeploy |
| `ImportError: libGL.so.1` on the ML app | GUI OpenCV wheel on a headless image | `opencv-python-headless` is already pinned; redeploy the ML workflow |
| `ModuleNotFoundError: No module named 'fastapi'` in the ML container log | The zip landed without `python-packages` | Re-run the ML workflow and confirm the `Build the deployment package` step logs a `python-packages` size |
| `ValueError: You have tensorflow 2.21.0 and this requires tf-keras package` in the ML log | DeepFace still calls into Keras 2 on TF 2.16+, where Keras 3 is the default | `tf-keras` is pinned next to `tensorflow` in `requirements.txt`; keep the two versions aligned |
| ML app returns 503 and the docker log shows `Container exited with exit code 127 during startup` | `wwwroot` holds only `output.tar.zst`, so there is no `startup.sh` to run: the platform build ran again | Confirm `SCM_DO_BUILD_DURING_DEPLOYMENT=false` and that the deploy step uploads `ml-app.zip`; the workflow sets the setting on every run |
| ML returns 503 for the first minutes after a deploy | TensorFlow import plus the Facenet warm-up finish before uvicorn binds | Expected: the workflow probes `/docs` for 7.5 minutes and `WEBSITES_CONTAINER_START_TIME_LIMIT=600` covers the bind |
| Scan says `Invalid or Expired Token` while the ML log shows a decode error | Seeded staff accounts carry placeholder `referenceFaceBase64` | Use an account with a real enrolled face, or the visitor selfie flow |
| Invite link points at `localhost` | `INVITE_BASE_URL` not set | Set it to `https://<api-app>.azurewebsites.net/invite` and restart |

## 8. Known gaps

- The ML service is deployed as code (`startup.sh` plus a `python-packages` folder built on the
  runner), not as a container image. Containerising needs an Azure Container Registry, a registry
  credential secret, and a `webapps-deploy` step with `images:`; the `/predict` contract does not
  change. Tracked in #43 and #56.
- That package carries a few hundred megabytes of wheels, so each ML deploy spends a few minutes
  installing and uploading. A container image, or a cached dependency layer, is the same follow-up as
  above.
- The backend has no automated tests yet (#50), so the pipelines only check syntax and the bundle.
- `JWT_SECRET` has an insecure code fallback, and the deployment still starts without it: verify the
  setting exists before the release.
- `origin/main` still lacks the back-merge of the last release; run the back-merge after the release
  PR merges so `develop` and `main` share history.
