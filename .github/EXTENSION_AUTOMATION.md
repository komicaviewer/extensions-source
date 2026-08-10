# Extension repair automation

The public side of extension repair accepts only sanitized, twice-confirmed
health issues dispatched by the private operations repository. Raw emulator
logs, cookies, credentials, HTTP bodies, and login screenshots must never be
copied here.

## Rollout gate

The private control plane owns `AUTOMATION_PHASE` (`observe`, `issue-draft`, or
`limited-auto-merge`). This public repository never decides to start AI work or
merge by itself. Its zero-secret candidate workflow runs only for the reserved
`automation/extension-fix-*` PR branch prefix and fails closed on the base-owned
policy.

## Private AI boundary

No AI model runs in this public repository. The private
`komicaviewer/extension-ops` control plane owns the Codex fixer and independent
reviewer, and keeps its AI Brain bearer token and logs private. This repository
contains only deterministic policy scripts and zero-secret pull-request
candidate CI.

Until the isolation-era `extension-api` commit reaches NewsHub's default
branch, CI checks out commit `53d421492614c13e2a5984b4991513d993d44246`,
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

Automation PRs contain one `Fixes #<number>` line. Zero-secret PR CI validates
the base-owned path policy, runs the catalog test/build tasks, creates an
ephemeral test-signed APK, and uploads an exact-SHA candidate artifact.

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
