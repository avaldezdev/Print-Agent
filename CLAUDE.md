# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Native Android client (Kotlin, no Android Studio required) that runs on a tablet inside a restaurant's LAN. It polls the WAMA Print Jobs API v1, renders each pending kitchen ticket as raw ESC/POS, and pushes it over a TCP socket (port 9100) to a thermal printer — bypassing the browser `window.print()` dialog that blocks direct kitchen printing from the web POS.

Server: `https://wama.micdepos.com`. The full loop and its rationale are in `docs/API_REFERENCE.md` (the authoritative v1 contract — 409 lines, read it before touching networking/job code). `CLAUDE_CONTEXT.md` holds the original Spanish project brief. UI strings and code comments are in Spanish; keep that convention.

## Build / run

Requires JDK 17 + Android SDK command-line tools (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=`). No Android Studio.

```
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed only if keystore.properties exists (see below)
./gradlew installDebug       # push to a connected adb device
```

On Windows use `.\gradlew.bat` (or `./gradlew` under the Bash tool). There are **no tests and no lint config** — do not claim to run them. Verification is manual via the on-screen debug buttons (health check, list pending, print test, print one real job).

Release signing reads `keystore.properties` (gitignored, keys: `storeFile`/`storePassword`/`keyAlias`/`keyPassword`). If that file is absent the release build simply produces an unsigned APK — no failure. App is distributed as a sideloaded APK, never Play Store.

## Code map

Single module `:app`, package `com.printagent.android`, five Kotlin files under `app/src/main/kotlin/...`:

- `PrintAgent.kt` — the core loop. `pollAndPrintOne(..., lineWidth)` lists one pending job (`?status=pending&limit=1`) just to get its UUID, then fetches the **full detail** (`GET /print-jobs/{uuid}`, unwrapped from a `data`/`job` envelope via `unwrapJob`) — the list serializer is lighter and omits `customer`/`comanda_note`, so the ticket is always built from the detail. Then sends it and POSTs `/printed` or `/failed`. Shared by the background service and the manual "print pending" button.
- `PrintAgentService.kt` — `START_STICKY` foreground service (dataSync type) that calls `pollAndPrintOne` every `POLL_INTERVAL_MS` (4 s) with the configured `line_width`, and reflects the last result in an ongoing notification. Reads config live from SharedPreferences each tick, so settings changes take effect without a restart. `ACTION_STOP` intent tears it down.
- `MainActivity.kt` — the only screen: config fields (base URL, token, printer IP/port, **paper-width radio** → `line_width` pref, 32/48 chars) persisted to SharedPreferences `"settings"`, the agent on/off toggle (`agent_active`, auto-starts the service on launch if set), and debug buttons: health check, list pending, print test, print one real job, and **"Ver JSON crudo"** (fetches the newest job's detail and shows it in a dialog with a `customer`/`comanda_note` presence summary).
- `PrinterClient.kt` — raw `java.net.Socket` to ip:port, 5 s connect/write timeouts. Returns byte count sent.
- `EscPos.kt` — builds the raw command byte stream. `buildJobTicket(job, width)` for real jobs (`width` = chars/line; `WIDTH_58MM`=32, `WIDTH_80MM`=48), `buildTestTicket(width)` for the debug button. Layout: business name (double size) → reverse-video station banner (COCINA/BAR/ADICION) → `subheader` + `hora` row → mesa·mozo → `Cliente:` (`meta.customer.name`) → comprobante·comanda → `Nota:` (`meta.comanda_note`) → items (bold `qty x name`, `+` modifiers, `!` notes) → item count + date. Right-aligned columns via `row()`, banner via `centerPad()`. Text is `ISO_8859_1` (`writeAscii`). Pads every ticket to `MIN_TICKET_LINES` (24) + a blank feed so it is long enough to tear off before the partial cut.

## Domain rules that live in the API, not the code

These come from `docs/API_REFERENCE.md` and govern any change to the job pipeline:

- **Poll cadence**: 3–5 s per device. Rate limit is 120 req/min/IP, **shared across every tablet in a business** — do not poll faster. Current interval is 4 s.
- **`/printed` is idempotent at the wire**: a second call (e.g. another tablet beat this one) returns 409 `already_printed`. Treat 409 as success, not an error. Multiple tablets per business is a supported deployment.
- **`/failed` is terminal until the client rescues it — it does NOT go back to `pending`.** The job moves to the `failed` bucket and the agent, which only polls `pending`, never sees it again. `postman/api_collection.json` documents `?status=failed` as *"útil para retry o auditoría de jobs que fallaron"* — i.e. **retrying is the client's responsibility**, by listing `?status=failed` and reprinting. **Never call `/failed` for a transient printer error**: that silently drops the order. This was the root cause of orders not reaching the kitchen in production; `PrintAgent` now reserves `markFailed` for data errors only.
- **Auth**: token is `wmk_` + 32 hex, sent `Authorization: Bearer <token>` (API also accepts `X-Api-Key`). Always send `X-Device-Hint: <id>` so operators can trace which tablet printed what. Token is shown in cleartext only once at creation.
- **Content schema**: `content` = `header` / `subheader` / `items[]` / `meta{}` / `footer` (see §5). `items[]` entries carry `qty`, `name`, `notes`, and structured `modifiers[]` (each `{name, qty}`, **all selected modifiers incl. price-0 ones** since API 1.1.1). `meta{}` carries `mesa`/`mozo`/`hora`/`comanda_id`/`numero`/`es_adicion`, plus `customer` (object with `name`/`mobile`/`address`/`tax_number`, present only when assigned) and `comanda_note` (order-level "Nota para cocina") since API 1.2.0. **These last two appear only on the detail endpoint, not the list.** `type` is `kitchen` | `adicion` | `bar` | `receipt`; v1 only auto-emits `kitchen`/`adicion`, `receipt` is v2.

## Where the implementation currently diverges from the spec

The spec describes the intended v1; the shipped code is a working slice that cuts several corners. Know these before "fixing" something that looks wrong, or before assuming a documented feature exists:

- **Per-job printer routing is NOT implemented.** `docs/API_REFERENCE.md` and the schema describe preferring `job.kitchen_station.printer.{ip_address,port,capability_profile}` and falling back to `printer_target`. The actual code ignores all of that and sends every job to the single IP/port configured in `MainActivity`. `MainActivity.summarizePending()` reads and displays `kitchen_station.printer` for debugging, but `PrintAgent`/`EscPos` never route by it. Wiring real routing is the main known gap.
- **Not profile-aware.** `capability_profile` (`default`/`simple`/`SP2000`/`TEP-200M`/`P822D`) is ignored. Paper width *is* configurable, though — a 58/80 mm radio in settings (`line_width` pref → 32/48 chars) drives every divider and right-aligned column; otherwise `EscPos` emits one command set.
- **`type` drives the station banner, not `es_adicion`.** The reverse-video banner is derived from `type` (`kitchen`→COCINA, `bar`→BAR, `adicion`→ADICION), falling back to `es_adicion` when `type` is absent. The "COMANDA #N" / "ADICION #N" line itself is still printed from the job's `subheader` as-is, not recomputed.
- **No startup health check.** `GET /print-jobs/health` exists only behind the manual "Probar conexión" button; the service does not validate token/module on start.
- **Token is stored in plain SharedPreferences**, not Android Keystore as the spec's onboarding note calls for.
- **`connection_type`** (`network`/`windows`/`linux`) is not inspected; everything is treated as raw TCP.

## Conventions

- `docs/ARCHITECTURE.md` and `docs/ESC_POS_REFERENCE.md` are intentionally empty — fill them as the matching code lands rather than leaving stale prose.
- Don't invent API fields. v1 in `docs/API_REFERENCE.md` is the contract; breaking changes go to v2.
- When debugging "the printer didn't fire", check the `/health` response first (via the app button or `postman/api_collection.json`) — it's most often a disabled `print_jobs_api` module or a revoked token, both visible there, not an app bug.
