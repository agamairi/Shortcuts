---
title: Release Status
type: architecture
author: antigravity
tags: [release, status, CI, widgets, website]
---

# Release Status

- Signed release keystore exists.
- `targetSdk` is 36.
- CI (lint + unit tests on PR/push) is in place.
- Legacy widgets have been removed (only the current unified widget remains).

## 0.8.0 (2026-10-17) — Engine v2 recording and replay
- Verified on a Pixel 6a (Android 16); numbers in [`recovery-plan.md`](recovery-plan.md) (fixture suite, real apps, widgets).
- Gates: unit tests, lint (0 errors), signed release build.
- Known issues are listed under 0.8.0 in `changelog.md` (Chrome address-bar searches, Messages untested,
  lock-screen widgets untested on device).
- Still short of the spec's reliability gate (≥ 600 fixture runs over 30 scenarios); 12 scenarios exist.

## Pending Tasks
Two items are explicitly still pending, not yet done:
- **Google Play Console listing paperwork** (data safety form, content rating, store listing) — not started.
- **Pricing model** (one-time purchase vs. freemium) — not decided.

## Companion Website
A companion marketing and compliance website is published and live at https://agamairi.github.io/repeatkit-site/, with its source in a separate repo at github.com/agamairi/repeatkit-site. 
It includes:
- A landing page
- A Privacy Policy (`privacy.html`)
- Terms and Conditions (`terms.html`)

The app's own Settings screen links out to the privacy policy on that site (`https://agamairi.github.io/repeatkit-site/privacy.html`).
