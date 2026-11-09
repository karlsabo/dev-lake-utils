# Agent Guidelines

## Communication
- Be direct and concise. Skip praise and filler.
- Prefer compact paragraphs or lists; use headings only when helpful.
- Distinguish evidence from assumptions. State uncertainty and blockers.

## Engineering
- Inspect relevant code and instructions before making changes.
- Follow existing conventions and preserve unrelated work.
- Deliver the smallest end-to-end change that meets the request.
- Centralize shared knowledge, not merely similar-looking code.
- Keep dependencies explicit and changes localized. Avoid speculative abstractions.
- Prefer reversible decisions; investigate hard-to-reverse choices more carefully.
- Establish data ownership before splitting services.
- Explain material trade-offs and how to validate architectural choices.
- Run required checks. Report failures and anything left unverified.

## CLI Tools
- Prefer service CLIs and machine-readable output.
- Consult `--help` when syntax or capabilities are unclear.
- Use another interface when the CLI cannot complete the task reliably.
- GitHub: `gh`; GitLab: `glab`; Linear: `linear`; Atlassian: `acli`.
- Buildkite: `bk`; Sentry: `sentry-cli`; Chronosphere: `chronoctl`; Trunk: `trunk`.
