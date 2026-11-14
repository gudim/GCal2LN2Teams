# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Python tool that copies calendar events between **Google Calendar**, **HCL Domino / Lotus Notes** (through the Domino REST API), and **Microsoft Teams / Outlook** (through MS Graph). Docs, comments, log messages, and CLI help text are in Russian; keep that when you edit them.

The README and most of the top-level `*.md` files (PROJECT_PLAN, IMPLEMENTATION_SUMMARY, etc.) describe planned features: config.yaml, conflict resolution, a daemon, Wine/COM access to Notes. Most of that does not exist. Trust the code over those docs.

## Commands

```bash
source venv/bin/activate
pip install -r requirements-dev.txt && pip install -e .

# Web UI (the main, working entry point): http://localhost:8000, API docs at /docs
./scripts/run_web.sh          # = python -m uvicorn src.web.app:app --host 0.0.0.0 --port 8000 --reload

# CLI (also installed as `gcalsync`)
python -m src.main --help
python -m src.main init                      # Google OAuth -> data/google_token.pickle
python -m src.main test-domino --server URL --username USER --no-verify-ssl
python -m src.main domino-events --server URL --username USER --no-verify-ssl --limit 10

# Tests (setup.cfg addopts always add -v and coverage reports)
pytest
pytest tests/test_cli.py::test_cli_help

# Lint / format (setup.cfg: line length 100, isort profile=black, mypy disallow_untyped_defs)
flake8 src/ && black src/ && isort src/ && mypy src/
```

Tests need no network. Adapter tests use JSON fixtures in `tests/fixtures/`, which were built from the API docs rather than recorded from real servers (see the README there). The `scripts/*.sh` integration checks call the real Google and Domino services.

## Architecture

Sync runs only from the web app: `POST /api/sync` in `src/web/app.py`. The CLI (`src/main.py`) has setup and inspection commands; `status`, `conflicts`, `config` and `daemon` are TODO stubs. Watch out: `revoke_google` is also registered as `@cli.command('test-google')`, so it overrides the real `test-google` command.

**Sync engine (`src/sync/`):**
- `event.py`: `CalendarEvent`. Timed events are aware datetimes stored in UTC; all-day events use `date` with an exclusive end. `title` has no marker; `source` says which system an event was copied from (`None` means native).
- `calendar.py`: the `Calendar` interface (`system`, `list(window)`, `create(event)`) and `InMemoryCalendar` for tests.
- `planner.py`: `plan(snapshot, pairs, links)` decides what to copy and does no I/O; `apply(copies, calendars)` creates the copies and returns new `Link`s. Dry run means "call `plan()`, skip `apply()`". `DIRECTIONS` maps the UI's `direction` value to pairs of systems, each synced both ways.
- `links.py`: stores `Link`s in the `sync_history` table.

An event counts as already copied if a `Link` exists for (origin system, origin id, target system), or, for copies made before links existed, if the target has an event with the same `source`, title and start time. Copies are never copied further.

Creation, updates and deletes are all synced. The rules (twins, per-field snapshot merge, foreign meetings, delete asymmetry, mass-delete guard) are in `docs/adr/0001-conflicts-and-deletes.md`, and the terms are in `GLOSSARY.md`. `collect()` does the I/O and `plan()` returns `Action`s (create, update, delete, twin). `select()` applies preview keys: an unticked question is remembered as a `kept`/`distinct` link. `apply()` saves each link as soon as it is written.

**Adapters (`src/adapters/`):** each adapter class implements `Calendar` (the `list`/`create` methods at the bottom of the class) and has module-level `event_from_*`/`event_to_*` conversion functions. Everything provider-specific stays in the adapter: markers (`[GC]` Google, `[LN]` Domino, `[MT]` Teams, added on create and removed on read), time zones, paging and the date window. Older methods (`list_events`, `test_connection`, ...) remain for the CLI and the connection-test routes.
- Google: OAuth desktop flow, token pickled at `data/google_token.pickle`; `singleEvents=True` with `pageToken` paging.
- Domino (server 14.5.1): basic auth against `<db>/api/calendar/events` with `since`/`before` (UTC).
  - The server returns at most 40 entries per page whatever `count` is, and pages overlap. Page until an empty page and dedupe.
  - The list is a summary only, so `list()` makes one GET per event (by `href`) for the description and `last-modified`. The detail response gives times with `tzid`, so times come from the list (UTC).
  - Entries with `x-lotus-unid` (invitation/reschedule/cancel notices) and `status: cancelled` are `ignored` and have no detail (404).
  - `last-modified` is per document: editing one instance changes it for the whole series.
  - Instance id is `uid/recurrenceId`; PUT/DELETE on an instance href affect only that instance.
- Teams: MSAL, token pickled at `data/teams_token.pickle`, OAuth callback at `GET /api/teams/callback`. Reads `/me/calendarView` with `Prefer: outlook.timezone="UTC"` and follows `@odata.nextLink`.

**Settings storage:** the web UI keeps its settings (credential paths, Domino URL/user/password, Teams client id/secret) as key/value rows in SQLite (`Config` model; `DATABASE_URL`, default `sqlite:///data/config.db`). Secrets are Fernet-encrypted with the key in `data/.secret_key` (`EncryptionService`). Read and write them through `get_config_value`/`set_config_value` in `app.py`, passing `decrypt=`/`encrypt=`. `init_db()` runs when `app.py` is imported, and paths like `data/...` are relative to the working directory, so start both entry points from the repo root.
