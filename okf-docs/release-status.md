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
