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
Administrators can search titles/filenames, scan duplicate candidates, inspect a
complete group, choose the surviving record, and merge straightforward groups directly from the list. **Merge** runs the
checks and commits a straightforward group in one operation and stays on the list.
Blocking issues appear inline; **Resolve issue** opens the assessment only when
explicitly selected. Merge never opens a preview automatically. **Inspect group** remains available
for manual review at any time. Scanning and previewing are read-only.

**Merge straightforward groups** checks the entire scanned list after one batch
confirmation. It processes one group at a time, leaves complex groups for review,
and can stop after the current operation. Keep the window open during the batch.
Transport/authorization/server failures stop the batch without automatically retrying
an uncertain operation. Every committed group retains its own receipt and journal.

Merge uses exactly the selected records and keeps the target metadata and default
file. Differences in contents, language, series, contributor credits or source
metadata locks do not require another confirmation when work identity is valid.
Distinct files remain attached as alternatives; byte-identical copies use recoverable
quarantine. Copies still referenced by other records stay in place. Matching titles
elsewhere do not silently expand a direct merge. The selected default file remains
preferred even if an attached file is older or the library format priority differs.
Actual reading-data conflicts, identity disagreements, unverifiable files and
unsupported device/proposal data still block the merge. Direct and reviewed merging
share transaction, file verification, quarantine, rollback, and receipt logic.
Completed groups are removed from the existing review list without rescanning it;
remaining groups, selections, and tab/page state stay available. **Review next group**
continues through that list. Catalogue pagination uses the remaining row offset so
local removals do not skip the next candidate. Explicit scanning refreshes the snapshot.

ISBN matching normalizes separators and checks ISBN-10/13 check digits. Invalid or
truncated publisher prefixes cannot create a group. ISBN and provider-ID matches
also require the same normalized full title, so a copied identifier cannot combine
different named volumes. File imports normalize valid ISBNs before storage instead
of truncating hyphenated strings to the database column length. Existing invalid
legacy values remain available for review and are ignored as matching evidence.

Connected shared paths and exact normalized title/author candidates are reviewed
together, including matching records outside the older detector's original group.
The expanded members are shown before confirmation. One operation can collapse
shared references, combine distinct formats, and reconcile byte-identical copies at
different paths. Each retained file stays in place. Different bytes, including
different PDF editions, are retained as separate files; format labels are never
used as proof of identical content. Full SHA-256 and current native fingerprints
are checked for every physical file. The selected record keeps its metadata;
complete original metadata and compatible reading data are archived in a receipt.

Inspection blocks missing/unreadable files, symbolic paths, folder books or
attachments, incompatible identities, conflicting user progress/preferences,
source metadata proposals, device snapshots/state, unsupported relationships,
and quarantine paths still referenced outside the selected library. Groups are
limited to 2–20 book records, 40 file records, 512 MiB per file, and 1 GiB of content
per preview; scans are limited to 200,000 library file records. Multiple library
roots need a separate move plan. Identical shelf memberships are combined; other
conflicting reading data is never silently overwritten.

Separate byte-identical copies are atomically renamed into
`<library-root>/.grimmory-quarantine/<receipt-id>/<file-id>.quarantined` on the same
filesystem. The `.ignore` marker and non-book extension prevent reimport. A durable
journal commits before moves; rollback restores originals without overwriting any
new files. Recovery after restart determines whether to restore copies or retain
them in quarantine by checking the committed merge receipt. Unresolved recovery
keeps the bytes and journal for review. Receipts remain available under **Recent
catalogue merges**, including quarantine status, expiry, and downloadable copies.
Downloading a copy recovers its bytes; automatic undo of the catalogue merge is
not included.

Quarantine retention defaults to **30 days**. Set
`APP_CATALOGUE_QUARANTINE_RETENTION_DAYS` in the Compose environment to change the
retention of new copies; `0` disables automatic purging. Cleanup runs at startup
and daily at 03:20 in the application's timezone, with at most 100 copies per run.
It permanently removes an expired copy only when both its checksum and the
currently registered retained file's path/checksum still match. Missing, changed,
or unregistered keepers and altered backups retain their quarantine copy for
review. Audit receipts and recovery journals are retained after purging. Unknown
files are never purged by this task.

V150 introduced the audit table and four legacy viewer-preference foreign keys.
V151 adds a merge/maintenance lock and the durable quarantine journal. Back up the
database and library before upgrading. Normal deletion and the separate format
attachment endpoint continue to reject unsafe shared-file selections.

Focused tests cover mixed-format merges, identical/different copies, expanded
membership, stale previews, reading-data conflicts, rollback, restart recovery,
and retention safety. Validation also includes frontend checks, the production
build, and an isolated MariaDB application workflow. Live verification only scans
and previews records; it does not merge or quarantine library books.

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
