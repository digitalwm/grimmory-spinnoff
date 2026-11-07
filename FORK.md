# Grimmory Spinnoff

The canonical repository is https://github.com/digitalwm/grimmory-spinnoff and its
working branch is `main`. The original project remains
https://github.com/grimmory-tools/grimmory. This version is experimental and is
built from source; upstream release images do not contain these improvements.
The upstream license, notices, original authorship, and full commit history are retained.

## Included local work

- BookDrop selection across pages, progress reporting, and per-file import transactions.
- Coordination of library monitoring while importing, source-file preservation on
  failed imports, and rejection of overlapping BookDrop imports and deletions.
- Lightweight sidebar entity counts with bounded refreshes and scope-aware caching.
- Metadata identity validation before automatic provider results are merged;
  unrelated title/author matches are rejected, and old conflicting bulk-import
  proposals fall back to embedded metadata.
- Administrator catalogue reconciliation for duplicate records sharing one registered file,
  with explicit previews, preserved reading data, and durable receipts.
- The custom `calibre-importer/` application, including consolidation and repair tools.
- `deploy/spinnoff/` Compose configuration, environment template, and MariaDB tuning.

The metadata completeness score remains a measure of populated fields, not identity
confidence. Ambiguous matches may need manual metadata selection. Existing library
records have not been subjected to bulk duplicate or metadata cleanup.

## Catalogue duplicates

Open a library's menu, then **Manage library → Find duplicates → Catalogue duplicates**.
Administrators can scan the library, inspect one group, choose the surviving record,
and explicitly confirm a catalogue-only merge. Scanning and inspection are read-only;
there is no automatic cleanup or bulk merge.

This first version handles records registered against the same library path,
case-sensitive subdirectory, and filename. Each record must have exactly one regular
file, and the complete group must contain 2–20 records. Inspection verifies that
stored fingerprints match the current file and computes a full SHA-256 checksum.
It blocks missing files, files above 512 MiB, additional formats, incompatible or
incomplete title/author identities, conflicting user progress/preferences, pending
source metadata proposals, device snapshots/state, and unsupported relationships.
Content duplicates at different paths need a separate review. Interactive scans are
limited to libraries with 200,000 file records.

A merge preserves the physical book file, keeps the selected record's metadata,
transfers compatible reading data and preferences, and combines duplicate shelf
memberships. Removed catalogue rows and their metadata/mappings are archived with
the before/after state and file checksum in a mandatory database receipt. The
transaction revalidates the preview and rolls back if data or file content changed.
Receipts can be downloaded immediately or reopened through **Recent catalogue merges**.
Receipts support investigation and manual recovery; automated undo is not included.
Normal deletion and format attachment reject unsafe shared-file selections.

Migration V150 creates the receipt table and adds book foreign keys to four legacy
viewer-preference tables. Existing orphan preferences deliberately block migration
instead of being removed. Back up the database before upgrading.

Validation covers 15 reconciliation regression cases, the affected book deletion
and format attachment suites, 12 frontend tests, the production build, and a merge
against an isolated MariaDB instance using the complete application schema. No live
library records were merged during implementation or validation.

## Fresh-clone deployment

From the repository root:

```sh
git clone https://github.com/digitalwm/grimmory-spinnoff.git
cd grimmory-spinnoff
cp deploy/spinnoff/.env.example .env
# Edit .env: replace credential placeholders, model settings, IDs, and storage paths.
mkdir -p data mariadb/config books bookdrop sources/epub sources/epub_clean sources/epub-managed/managed-library
cp deploy/spinnoff/mariadb/custom.cnf mariadb/config/custom.cnf
docker compose --project-directory . -f deploy/spinnoff/docker-compose.yml config --quiet
docker compose --project-directory . -f deploy/spinnoff/docker-compose.yml up -d --build
```

The environment template sets `GRIMMORY_SOURCE=.` for this checkout layout. Grimmory
is served on port 6060 and the importer on port 8090. Configure the importer's
Grimmory credentials after creating the application account. The importer writes
to its configured BookDrop and source-library mounts when import or consolidation
operations are requested; select those paths deliberately.

The Compose file preserves the existing `10.5.0.48/29` network and fixed addresses.
Adapt these if deploying alongside another instance. Application and MariaDB data
persist under the Compose project directory. Database credentials in `.env` must
match an existing database when reusing its persistent storage.

`custom.cnf` sets the InnoDB buffer pool to 1 GiB. The tracked file is the deployment
source; copy it to `mariadb/config/custom.cnf` when provisioning or updating the
MariaDB configuration. Inspect live server variables after applying a change.

## Existing host layout

The existing host remains `/docker-sites/grimmory` as the Compose project directory,
with this repository at `/docker-sites/grimmory/grimmory`:

- `docker-compose.yml` links to `grimmory/deploy/spinnoff/docker-compose.yml`.
- `calibre-importer` links to the canonical `grimmory/calibre-importer` source.
- `.env`, `data/`, `mariadb/config/databases/`, logs, books, import state, and private
  diagnostic receipts remain local and are not part of the repository.
- The installed MariaDB configuration is under `mariadb/config/custom.cnf` and
  matches the tracked deployment configuration.

Compose uses `./grimmory` as the default source directory when `GRIMMORY_SOURCE` is
unset, preserving this host's layout. It uses the original `/data/...` mount paths
unless the optional storage-path variables are set. Run `docker compose` from the
outer directory as before. Repository migration does not restart containers.

## Git remotes and upstream integration

For a fresh clone, configure the original project as a fetch-only source:

```sh
git remote add upstream https://github.com/grimmory-tools/grimmory.git
git remote set-url --push upstream no_push://upstream-read-only
git config remote.pushDefault origin
git config push.default simple
git config branch.main.pushRemote origin
```

The original upstream baseline incorporated in the local tree is
`e73af1361` through merge commit `f460b9944d42bec6e183b3d417e7fefc4f510622`.
The earlier local BookDrop commit `56a7ada90` is retained in the history.

Periodically review upstream explicitly; there is no scheduled fetch, automatic
merge, deployment, or upstream pull request:

```sh
git fetch upstream --prune
git log --oneline main..upstream/develop
git diff --stat main...upstream/develop
git switch -c integrate/upstream-YYYY-MM-DD main
# Choose one approach after reviewing the changes:
# git merge --no-ff upstream/develop
# git cherry-pick <selected-commit>...
```

Resolve conflicts while preserving local behavior. For selected cherry-picks, retain
provenance with `git cherry-pick -x`. Run the relevant regression tests and production
build before merging the integration branch into `main`, then push to `origin`.
Check BookDrop selection/progress, per-file transactions and file preservation,
monitor suppression, metadata identity validation, sidebar counts and permission
scopes, and database migrations when upstream changes touch these areas.

## Automation and validation

CI validation and CodeQL include `main`. Inherited stable release, nightly publishing,
and preview-image workflows are gated off for this repository unless the repository
variable `ENABLE_RELEASE_AUTOMATION` is explicitly set to `true`. Preview images use
this repository's registry namespace. Configure and review release credentials,
version tags, registry settings, and notification integrations before enabling release
workflows. Inherited dependency update configuration is retained; dependency changes
still require review.

The initial imported state passed the production build, 97 focused backend metadata,
parser, and BookDrop tests, and the earlier sidebar checks (17 frontend tests and
7 backend count/concurrency tests). Live verification confirmed a healthy deployment,
matching entity counts, and preservation of both affected EPUB files while clearing
two incorrect pending metadata proposals. Repository migration additionally passed the VersionService checks (15 tests),
Python compilation, workflow YAML validation, and Compose equivalence/template checks.
These are scoped checks, not a claim that
the entire upstream test suite or every importer workflow has been exercised.

OpenAI Codex assisted with implementation, investigation, tests, deployment checks,
and repository setup for these local changes. Original upstream commits retain their
own attribution and history. See `AI_POLICY.md` for the retained contribution policy.
