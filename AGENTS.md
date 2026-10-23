# AGENTS.md

Repository instructions for AI coding agents (Cline, GitHub Copilot, and other tools) working in the PixelPass repository.

## Source of truth for Git and GitHub work

For any Git or GitHub version-control work in this repository, follow the rules defined in:

`.github/agents/pixelpass-git.agent.md` (the PixelPass Git Agent)

Read that file before acting. It is authoritative for commit messages, branch names, issue and milestone linkage, pull requests, merge strategy, and reporting format. If anything in this file conflicts with it, the agent file wins.

The essentials it requires:

- Commit format: `<type>(<scope>): <two or three word summary> [#<issue-number>]`. The bracketed issue suffix is mandatory on every non-merge, non-revert commit; if no issue number applies, pause and request the mapping before committing.
- Branch names: `<type>/<scope>-<short-description>` using feat, bugfix, hotfix, design, refactor, test, doc, chore, perf, ci, build, or release. No issue numbers in branch names, and no branch per issue.
- Roadmap: exactly four milestones, `1. Mobile App Implementation`, `2. Machine Learning Model`, `3. Backend Implementation`, `4. Testing and Deployment`. Every issue carries exactly one of them.
- Every issue also carries one area label (`mobile`, `backend`, `ml`, `admin`, `testing`, `deployment`, `docs`, `tooling`) and appears on the `PixelPass Roadmap` GitHub Project board.
- Every issue and pull request gets one row in the running summary table in the agent file (issue, milestone, PR, state, one-line outcome), updated as work lands.
- All terminal commands must be Windows PowerShell; use semicolons for chaining. Never provide Bash or Linux commands.
- Map a branch to a specific GitHub issue and milestone before opening a pull request.
- The default merge strategy for pull requests into develop and main is a merge commit.
- Prefer the smallest possible diff; never bundle unrelated changes into one commit or pull request.
- Destructive or irreversible operations such as `git push --force` or `git reset --hard` require explicit user confirmation.
- Out of scope for the Git Agent: writing application logic or architecture design.

## Continuing work handed over from local Copilot

When a Git or GitHub task started in local Copilot is handed off, re-read `.github/agents/pixelpass-git.agent.md` and continue under its rules instead of inventing new conventions. Report results in that file's output format: Findings, Plan, Commands, Validation, Risks / Next actions.

## Repository layout

- `pixelpass-backend/` - Node.js and Express API. Entry point `src/server.js`; models in `src/models/`; scaffolded `config`, `controllers`, `middlewares`, `routes`, `services`, and `utils` directories. CommonJS modules, mongoose and dotenv configuration, started with `npm start` or `npm run dev`.
- `.github/agents/` - custom agent definitions.
- Git remote: `https://github.com/RyanMatuRob/pixelpass.git` with long-lived `main` and `develop` branches.

## Local toolchain

`git` 2.51, `gh` 2.101 authenticated as `RyanMatuRob`, and `node` v24 are available. `core.autocrlf` is `true`, so keep working-tree files using CRLF line endings.
