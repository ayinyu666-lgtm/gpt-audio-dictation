# Changelog

## 1.2.0 - 2026-10-17

- Generalized the project from a classroom/translation utility into a ChatGPT audio dictation and local archive extension.
- Added reliable per-segment pairing between the user's transcript and the following GPT answer.
- Frozen the selected category at segment start so late category changes cannot redirect old segments.
- Added per-job browser queue persistence, retry/backoff, blocked-state recovery, and manual category assignment.
- Added local writer integrity checks, content digests, duplicate detection, partial-write recovery, and conflict protection.
- Added Windows-first folder selection, write probes, configuration recovery, and clearer startup behavior.
- Added Chinese, German, and English onboarding copy.
- Added reproducible syntax checks and release packaging.

## 1.1.0

- Added the initial floating panel, adaptive recording loop, local writer, and Windows launchers.
