# Contributing

Contributions are welcome through GitHub issues and pull requests.

## Development checks

Run:

```powershell
node scripts/check.mjs
```

Before changing the page integration, test against a current ChatGPT page because selectors can change. Do not upload private transcripts, credentials, browser profiles, or local writer logs in an issue or pull request.

## Scope boundaries

- Keep recording decisions, stop/send timing, button selectors, and local archive processing in separate modules.
- Preserve queue recovery and idempotent file writes when changing the save path.
- Avoid adding credentials, personal paths, course names, generated archives, or telemetry files to the repository.

## Pull requests

Describe the user-visible problem, the exact files changed, the test evidence, and any remaining limitations. Keep unrelated refactors out of focused fixes.
