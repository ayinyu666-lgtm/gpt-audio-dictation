# Architecture

## Data flow

```text
ChatGPT page
  -> page-hook.js: microphone level, automatic segmentation, page actions
  -> panel.js: floating UI, settings, durable save outbox
  -> background.js: extension service worker, loopback relay
  -> writer/writer.mjs: validated local Markdown writer
```

## Main components

### `extension/page-hook.js`

Runs in the page world. It reads the microphone stream used by ChatGPT, tracks speech/silence, drives the existing recording loop, and collects the saved user turn and its following GPT answer. Save queue metadata is persisted with one storage key per `saveId`.

### `extension/panel.js`

Runs in the isolated extension world. It owns the floating panel, settings, category profiles, onboarding, writer status, retry UI, and the durable outbox containing completed answers.

### `extension/background.js`

Relays loopback HTTP calls and preserves HTTP status, retryability, and save IDs between the panel and writer.

### `writer/writer.mjs`

An optional zero-dependency local Node service. It validates paths, writes one category folder per archive, checks write completion, deduplicates save IDs, detects conflicting content, and recovers interrupted segments.

## Storage rules

- Page metadata and panel payloads use per-job keys rather than one shared array.
- The full transcript/answer payload is kept in extension storage until the writer confirms a durable write.
- The writer uses exact line markers and a content digest; it never silently replaces a different answer under the same save ID.

## Compatibility

ChatGPT page structure is not a stable public API. The page integration should be treated as a compatibility adapter and re-tested whenever button labels or message DOM nodes change.
