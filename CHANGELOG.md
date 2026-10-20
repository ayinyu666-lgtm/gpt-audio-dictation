# Changelog

## 1.2.1 - 2026-10-20

- Fixed long ChatGPT conversations where DOM virtualization removed older turns and blocked every pending archive job.
- Matched pending segments by exact and punctuation-insensitive transcript fingerprints instead of global message ordinals.
- Prevented one unmatched segment from blocking unrelated ready segments in the same save queue.
- Added recovery for recent segments when both fingerprints differ slightly from the rendered user message.
- Added a panel action to clear permanently unmatchable blocked records after the content has already been archived by another path.
- Added a development utility to append only newer conversation turns from an exported conversation.
- Removed the temporary category override mechanism and its expired configuration.
- Improved development probe output handling for long multilingual text.

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
