# Tasks to do

Local working checklist. Not committed (see `.gitignore`).

## Blocking the ASK deliverables

- [ ] **Run all three versions and capture real numbers.** The README's results table is currently empty — no load test has actually been run yet. For each version (v1 naive, v2 Postgres lock, v3 Redis), run `flood.py` with the same `--total`/`--concurrency`, save the raw output, run `db/check.sql` after each, and fill in the table.
  - Suggested: save raw output under `results/` (e.g. `results/v1-naive-50k.txt`), one file per run, and link each README row to its file.
- [ ] **Check the brief's exact endpoint spec against `POST /buy`.** Confirm path, params, and response codes/bodies match what's described. Only one endpoint currently exists.
- [ ] **Rotate the Postgres password.** `Prachi@123` is in git history (commits `89a96f0`, `ed61cf9` in `instructions.md`, now deleted but still in history). Change the DB password before sharing this repo, or rewrite history.
- [ ] **Export session logs.** AI chat transcripts and/or terminal session logs from building/testing this, per the brief's wording.
- [ ] **Write up results interpretation**, not just numbers — what changed and why between versions, referencing the actual run output.

## Nice to have / cleanup

- [ ] Remove `backend/target/` from git tracking (currently committed with no `.gitignore` — every build dirties `git status`).
- [ ] Remove stray files `backend/PONG`, `backend/accepting`.
- [ ] Remove unused repository methods/imports (`findFirstByStatus`, `lockNextAvailable` no longer used by v3's controller; leftover imports in `TicketController.java`).
- [ ] Decide if v4 (message queue) is in scope for this submission or explicitly out of scope — say so in the write-up either way.
