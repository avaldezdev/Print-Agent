# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

This repo is a **greenfield Android client** that will consume the WAMA Print Jobs API v1 and drive ESC/POS thermal printers over the LAN. As of now the repo contains only the integration spec — there is **no Android project yet**:

- `app/` — empty, reserved for the Gradle/Android module
- `README.md`, `docs/ARCHITECTURE.md`, `docs/ESC_POS_REFERENCE.md` — empty placeholders, expected to be filled in as the implementation lands
- `docs/API_REFERENCE.md` — **the authoritative spec for everything the app must do**. Read it before designing or modifying any networking / job-handling code.
- `postman/api_collection.json` — Postman collection mirroring the same endpoints, useful for hitting the live API

There are therefore no build, lint, or test commands to document yet. When the Android module is created, update this section with the actual `gradlew` invocations.

## What the app has to do (big picture)

The web POS (WAMA) inserts `print_job` rows when a waiter sends an order to the kitchen. This Android app polls the API, prints each pending job on the right thermal printer, and reports success/failure back. Server URL: `https://wama.micdepos.com/api/v1/print-jobs`.

The minimum loop is:

1. `GET /print-jobs/health` once on startup to validate the token + that the `print_jobs_api` module is enabled (otherwise everything 403s).
2. `GET /print-jobs?status=pending` every **3–5 seconds** per device. The rate limit is 120 req/min/IP and is shared across all devices in a business, so don't poll faster.
3. For each job, render ESC/POS and send to the printer (see routing below).
4. `POST /print-jobs/{uuid}/printed` immediately on success. This is **idempotent at the wire**: the second call from any device returns 409 `already_printed`. Treat 409 as success — it just means another device beat us to it (multiple tablets per business is a supported deployment).
5. On failure, `POST /print-jobs/{uuid}/failed` with optional `{"reason": "..."}`. This bumps `attempt_count` but does not stop the job from reappearing in the pending list — retry policy is the client's responsibility.

## Printer routing — the one tricky part

A job carries two ways to identify its destination printer, and the app must prefer the structured one:

```
if (job.kitchen_station?.printer != null) {
    // Source of truth: use the IP/port from the embedded printer object
    sendTo(job.kitchen_station.printer.ip_address,
           job.kitchen_station.printer.port,
           profile = job.kitchen_station.printer.capability_profile)
} else {
    // Legacy fallback: look up a locally-configured printer by the slug
    sendToConfiguredPrinter(job.printer_target)   // e.g. "cocina_principal"
}
```

`kitchen_station.printer.connection_type` may be `network` (raw TCP to ip:port, almost always 9100), `windows`, or `linux`. For v1 the realistic Android target is `network`; the others come from the WAMA desktop agent config and the app should degrade gracefully (mark as failed with a clear reason) rather than crash.

`capability_profile` values seen in the spec: `default`, `simple`, `SP2000`, `TEP-200M`, `P822D`. These map to ESC/POS quirks in the receipt-printer ecosystem — the rendering layer should be profile-aware rather than assuming a single command set.

## Auth

Token format: `wmk_` + 32 hex chars. Sent as `Authorization: Bearer <token>`; the API also accepts `X-Api-Key` as fallback. Always send `X-Device-Hint: <stable-device-id>` so server logs can attribute prints to a specific tablet — this is how operators diagnose "why didn't the kitchen printer fire".

The token is shown in cleartext **once** at creation, so the app's onboarding flow has to capture it on first run and persist it securely (Android Keystore-backed storage).

## Job content schema

`content` shape varies by `type` (`kitchen` | `adicion` | `bar` | `receipt`). v1 only emits `kitchen` and `adicion` automatically; `receipt` is reserved for v2. Both kitchen-class types share the `header` / `subheader` / `items[]` / `meta{}` / `footer` shape documented in `docs/API_REFERENCE.md` §5. The renderer should treat `meta.es_adicion` as the switch between "COMANDA #N" and "ADICION #N" headers rather than branching on `type`.

## When picking up work

- The spec in `docs/API_REFERENCE.md` is stable within v1 — any breaking change goes to v2. Treat it as the contract; don't invent fields.
- `docs/ARCHITECTURE.md` and `docs/ESC_POS_REFERENCE.md` are empty on purpose; populate them as the corresponding code lands rather than leaving them stale.
- Use the Postman collection (`postman/api_collection.json`) to sanity-check the live API before debugging the app — most "the app is broken" reports turn out to be a disabled module or revoked token, both of which are visible in the `/health` response.
