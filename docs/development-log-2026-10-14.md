# 2026-10-14 Android 0.4.0 completion work

This continuation implements the owner's four requested areas: the chat UI, owner-controlled tool policy, real phone capabilities behind the granted permissions, and exact account/login memory with backup. No paid API, private VM or real account/SMS/call fixture was used.

## Implemented and connected

- One complete runtime tool catalog now drives model definitions and the permission UI. A native UI regression verifies that every current tool has a persisted owner control, including MFA.
- Account storage preserves exact current credentials, canonical source identities, corrections, history and deletion/exclusion. Account records and unknown extra fields follow the existing memory ZIP; account deletion does not silently delete the owner's ordinary conversation.
- TOTP uses RFC6238/RFC4226 computation. Native direct entry computes immediately before typing, with expiry metadata and no claim of successful authentication. New owner-attributed seeds and recovery codes must match their actual original text.
- Recovery-code reservation is durable before reveal, bound to native request/action identity, and merged across older ZIPs through portable owner controls. A connected external folder is synchronously verified before a code is exposed; a missing/unavailable folder is explicitly distinguished from verified portable persistence.
- Actual tools consume notification actions/replies, contacts/calendar mutation, dialing/calls, conditional SMS/call-log permissions, device administrator state/lock, accessibility input/screenshots, overlays, Bluetooth/Wi-Fi/local-only hotspot, microphone FGS recording and native exact reminders. Normal Android restrictions and actual grants remain observable.
- Binary phone/media import, original-file export, copy/move/delete and immutable Work attachment preserve hashes and canonical source checks. Ordinary project/download files can be attached without pretending that they are owner utterances.
- UI input targets no longer expire solely after30 seconds. The latest snapshot and live target identity/window/state are checked before execution, so slow model responses can still use an unchanged field.
- Native asynchronous completion is reconciled against actual callback receipts in the same root. Accepted requests, no-op stops, sent SMS, delivered SMS and externally verified login remain distinct.

## Defects found during verification

The first expanded full test run failed13 cases. Six account UI fixtures clicked before native dialog listeners were ready; four alarm fixtures advanced monotonic time while production used UTC; two catalog tests assumed the older number/size of tools; one actual prompt-capacity edge appeared after expanding the catalog. The fixtures were corrected without reducing behavior assertions. Exact tool-schema deferral now preserves mandatory source/instruction content when the full catalog would overflow. It may require a later definition lookup and is not a universal latency or capacity guarantee.

Subsequent cross-review found that strict native agent-authored input roots were rejected as if they were malformed owner roots, that excluded direct receipts could still certify completion, and that an old terminal callback could certify a later unrequested stop. These were corrected with separate origin/visibility/acceptance regressions. Another review caught MFA values that could be incorrectly attributed to an owner source; exact source matching was extended to new authentication seeds and recovery codes.

The standalone Android34 media fixture required the Java17 test runner's `java.io` module opening for Robolectric's file-descriptor interceptor. No production URI/grant check was removed to make that fixture pass.

A later full integration run completed1229 tests:1210 passed,17 opt-in cases skipped and2 legacy-control fixture failures. Those two fixtures used the new v2 format marker for an intentionally v1 four-field record. The legacy fixture marker is corrected; production schema validation remains strict. The final run also includes regressions that keep historical radio observations visible when only Location services are switched off, and preserve sent-error history while recognizing a later verified SMS delivery.

## Evidence boundaries

Focused tests include25 UI cases,43 account/MFA/facade cases,88 portable-controls/catalog/prompt cases,40 alarm/onboarding/async cases and21 SMS/call-log cases. These overlap with the final full suite; their counts must not be added and presented as independent coverage. Additional source/provenance and target-identity regressions are included in the final run.

The first frozen five-year pass completed432 source checks with zero protected-source leaks or blocked cases, and its144 final evidence packets matched the prior v0.3.2 reference exactly. It used synthetic long-term data and actual native SQLite, without model calls. Final-source rerun and complete build/native results are recorded separately. This is not a new score for144 real model answers, proof of five years of phone operation or proof of a10% daily provider-usage target.

The native UI preview contains11 actual Android15 native renderings with synthetic state and the owner's unchanged cat profile. It is not a physical Galaxy capture. Real Instagram/other-site authentication, CAPTCHA, FIDO/biometric ceremonies, carrier delivery and Galaxy S24 Ultra long-running battery behavior remain device/account checks.
