# AI Agent Guidelines

This file serves as the system rules for AI Agents working on this project.

## Git Workflow Policy
1. **Never commit directly to `main`.** `main` is protected and represents the stable release.
2. **Never commit directly to `development`.** `development` is the integration branch.
3. **Always use feature branches.** When assigned a task, checkout a new branch from `development` (e.g., `feat/feature-name` or `fix/bug-name`).
4. **Update Changelog.** Every time you are about to merge a feature branch into `development`, you MUST update `changelog.md` with the changes and a version bump if necessary.

## Knowledge System (OKF)
- The `okf-docs/` folder contains the Open Knowledge Format (OKF) documentation.
- When you need context on architecture, schemas, or API boundaries, ALWAYS read the relevant `.md` files in `okf-docs/`.
- If you introduce a new feature or architectural change, create or update a file in `okf-docs/` following the OKF format (with YAML frontmatter: `title`, `type`, `author`, `tags`).

## Architecture & Code Quality
- Follow Clean Code principles.
- Strictly adhere to MVVM (Model-View-ViewModel) architecture.
- Always write Unit tests (JUnit, MockK) and Integration tests (Espresso) for new features.
- Keep components modular and single-responsibility.
