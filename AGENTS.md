# Grimmory Spinnoff working conventions

This is Dan's experimental Grimmory development repository. The canonical repository
is https://github.com/digitalwm/grimmory-spinnoff, with `main` as its default branch.

- Push requested work to `origin` (digitalwm/grimmory-spinnoff).
- Keep grimmory-tools/grimmory as the fetch-only `upstream` remote. Never push there.
- Preserve the original history and attribution so selective upstream merges remain possible.
- Check upstream changes when requested. Review them on an integration branch, run
  relevant checks, and merge only selected, verified changes. Do not automatically
  replace local improvements with upstream code or enable scheduled merges.
- Application code, `calibre-importer/`, and `deploy/spinnoff/` belong in this repository.
- Keep credentials, `.env` files, books, database files, importer state, and private
  operational diagnostics out of Git. `.env.example` files contain placeholders only.
- Deployment instructions and the upstream integration procedure are in `FORK.md`.
- Preserve unrelated local work. Commit only the requested scope unless the user
  explicitly requests all local changes.
- Follow `AI_POLICY.md` and disclose AI assistance in contributions.
