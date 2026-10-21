# Changelog

## 1.3.0 - 2026-10-21

- Added automatic detection of the ChatGPT context-limit banner and an automatic jump to a new conversation when it appears.
- Rescued the in-progress segment before switching conversations instead of discarding it silently.
- Started a fresh archive file and reset segment numbering after following a new conversation, so new content no longer lands in the old file.
- Frozen archive jobs belonging to the previous conversation instead of letting them spin forever in the queue.
- Added an editable new-conversation prompt that asks ChatGPT to recall the task requirements from its own memory.
- Marked injected prompt turns so they are never archived as transcript segments.
- Added a per-course subdirectory field: empty means files go straight into the course folder.
- Added a course-to-folder alias map so one course with two names keeps a single folder.
- Rebuilt the archive destination flow into root directory, subdirectory, and course, with a full path preview.
- Changed the default so the extension no longer starts recording on its own; press start to begin.

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
