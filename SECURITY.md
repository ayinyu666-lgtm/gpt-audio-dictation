# Security and Privacy

## Reporting

Please report a vulnerability privately through GitHub Security Advisories rather than opening a public issue with exploit details.

## Local data

The extension reads the transcript and the following GPT answer only when local archiving is enabled. The writer stores files locally and listens only on `127.0.0.1`.

Never commit:

- Writer logs or generated Markdown archives
- Browser profiles or extension storage
- API keys, access tokens, cookies, or credentials
- Personal folder paths or private transcript content

The project is not affiliated with OpenAI. ChatGPT DOM changes may affect compatibility and should be re-tested before release.
