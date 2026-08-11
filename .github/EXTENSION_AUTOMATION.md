# Extension repair automation

The public side of extension repair accepts only sanitized, twice-confirmed
health issues dispatched by the private operations repository. Raw emulator
logs, cookies, credentials, HTTP bodies, and login screenshots must never be
copied here.

## Rollout gate

The private control plane owns `AUTOMATION_PHASE` (`observe`, `issue-draft`, or
`limited-auto-merge`). This public repository never decides to start AI work or
merge by itself. It has **no GitHub Actions workflows**. The private control
plane starts `cloudbuild/pr-candidate.yaml` only for the reserved
`automation/extension-fix-*` PR branch prefix; the build fails closed on policy
copied from the exact base SHA.

## Private AI boundary

No AI model runs in this public repository. The private
`komicaviewer/extension-ops` control plane owns the Codex fixer and independent
reviewer, and keeps its AI Brain bearer token and logs private. This repository
contains only deterministic policy scripts and Cloud Build definitions. No
Codex or AI bearer secret is supplied to either Cloud Build definition.

Until the isolation-era `extension-api` commit reaches NewsHub's default
branch, Cloud Build checks out commit `53d421492614c13e2a5984b4991513d993d44246`,
builds its AAR locally, and supplies it through `-PnewshubDir`. This avoids a
mutable branch dependency and does not rely on an unpublished JitPack artifact.

The private fixer receives no website login, signing, push, or merge
credentials. Its patch is transferred to a fresh job for fail-closed path
validation, deterministic version bump, tests, and publication.

## Public issue contract

The private monitor opens or updates an issue containing exactly one payload:

```text
<!-- newshub-extension-health:v1
{"schemaVersion":1,"sourceId":"...","operation":"getBoardPage","failureClass":"parser-contract","targetHost":"example.org","fingerprint":"sha256:<64 lowercase hex>","observedAt":"2026-08-11T01:23:45Z","summary":"sanitized evidence"}
-->
```

The Source ID and exact host must match `release-catalog.json`. The private
fixer is dispatched with the issue number and requires the `automation:ready`
label. Per-issue concurrency in the private control plane prevents parallel
claims.

## Candidate and live attestation

Automation PRs contain one `Fixes #<number>` line. The zero-secret PR Cloud
Build validates the base-owned path policy, runs the catalog test/build tasks,
creates an ephemeral test-signed APK, and uploads an exact-SHA candidate to the
configured private GCS bucket. `_BASE_SHA` is the PR merge base and must be an
ancestor of `_HEAD_SHA`. The build accepts only exact PR metadata and
base64-encoded sanitized health JSON; it declares no `secretEnv` or Secret
Manager entries.

The private verifier installs that artifact in the trusted NewsHub harness. It
must publish a completed successful check run named
`newshub-extension-live-verification` on the candidate commit. The check output
title must be `verified-sha:<40-character SHA>`.

After verification, the private reviewer re-fetches the PR, rejects any SHA
mismatch, runs Codex read-only, and validates that Codex names the same head
SHA. A failed attestation or review comments and tags `@twkevinzhang`.

The permanent automatic-change allowlist is narrowly named parser,
request-builder, model, board-catalog, pager and read interactor code,
Source-local tests/fixtures, and only the release and named Source version
assignments. A production repair, regression test, and both version bumps are
all mandatory.
Manifest, Source/authentication/session code, service wrappers, network policy,
workflow, Gradle build logic, catalog, and permission changes always require a
manually authored and reviewed PR.

## Cloud Build safety and bounds

- `cloudbuild/pr-candidate.yaml`: `E2_STANDARD_2`, 45-minute hard timeout,
  10-minute queue TTL, 50 GB disk, zero secrets, and no automatic retry.
- `cloudbuild/publish.yaml`: `E2_STANDARD_2`, 50-minute hard timeout,
  10-minute queue TTL, 80 GB disk, and no automatic retry.
- Logs use `CLOUD_LOGGING_ONLY`; access is controlled by GCP IAM, not a public
  repository log page.
- The candidate service account needs only source-read/log-write and write
  access to the candidate bucket. It must have no Secret Manager access.
- The dedicated publisher service account gets access only to the listed
  package-signing secrets and GitHub App material. Each signing container sees
  one package's five `secretEnv` values.
- Trigger-level retries must remain disabled. The private control plane owns
  serialization and may start a new build only as a new bounded attempt.
- Publication must remain a controller-dispatched manual trigger after an
  exact-SHA merge. A repository push trigger would bypass the monthly cost
  reservation and is prohibited.
- The private control plane must submit the candidate config from trusted
  `main` (or an immutable GCS copy), never load build YAML from the PR head.

The publisher exchanges GitHub App material for a token whose remaining life
is verified to be at most 65 minutes. It clones through `GIT_ASKPASS`, never
puts the token in a URL or argument, admits the full distribution, and
exact-head squash-merges an isolated PR. It never pushes destination `main`
directly.

Private orchestration can use the stable compatibility CLI after applying an
agent code/test patch, then perform the deterministic version bump and validate
the final patch:

```bash
python3 scripts/validate_agent_patch.py \
  --issue-json normalized-health.json \
  --base-ref "$BASE_SHA" \
  --print-test-task
python3 scripts/bump_release_version.py --issue-json normalized-health.json
python3 scripts/validate_agent_patch.py \
  --issue-json normalized-health.json \
  --base-ref "$BASE_SHA" \
  --allow-version-bump \
  --print-test-task
```

The version flag is an explicit acknowledgement, not a bypass. Both exact
version-only diffs, a production repair, and a Source-local regression test
remain mandatory.
