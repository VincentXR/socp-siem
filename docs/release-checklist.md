# Release Checklist

Use this before publishing a portfolio release or tagging `v1.0.0`.

- [x] Repository license selected and added: [MIT License](../LICENSE).
- [ ] Run `bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Run the workbench test, build and artifact verification commands.
- [ ] Run `verify-slice.py`, `verify-pipeline.py`, `verify-full.py`, and the
      attack demo against integration middleware.
- [ ] Run `failure-tests.py` and retain the logs.
- [ ] Capture the screenshots listed in `docs/demo-checklist.md`.
- [ ] Confirm no `.cache/`, H2 databases, credentials, or generated bundles
      are tracked.
- [ ] Write release notes that state the logical-tenancy, local-window-state,
      single-broker and keyword-assistant boundaries.
