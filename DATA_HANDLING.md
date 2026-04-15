# Aegis Debug — Data Handling

**Last updated:** 2026-04-15
**Product version:** 1.0.0

## What data leaves your machine

| Event | Data sent | Destination | Triggered by |
|-------|-----------|-------------|--------------|
| Static analysis | Nothing | — | Always local |
| Ollama AI pass | File path + file contents (up to 2000 lines) | Your configured Ollama endpoint (default: http://localhost:11434) | `aiProvider = OLLAMA` |
| OpenAI AI pass | File path + file contents (up to 2000 lines) | api.openai.com/v1 | `aiProvider = OPENAI` **AND** `allowCloudUpload = true` |
| OpenAI explain / fix / system | Issue metadata + code snippet (up to 800 chars) | api.openai.com/v1 | Same as above |
| Telemetry | None | — | Never |

## What is NOT sent

- Your project name (never included in prompts).
- Files outside the scanned set.
- API keys (stored locally in IntelliJ PasswordSafe, never transmitted except to OpenAI in the `Authorization` header when you invoke OpenAI).
- Debug session data (stack frames, variable values) — never sent to any AI provider.

## Caching

- AI responses are cached **in memory** per IDE session.
- Cache TTL defaults to 1 hour, configurable in Settings.
- Cache is **never written to disk**.
- Disable via Settings → Tools → Aegis Debug → "Cache AI responses locally".

## Key storage

- OpenAI API keys are stored via `PasswordSafe` (OS-native: Keychain on macOS, Credential Manager on Windows, Secret Service on Linux).
- Keys are never persisted to `ghostdebugger.xml` or any other plugin config file.

## Your controls

- Default provider is `NONE` — no AI, no cloud.
- `allowCloudUpload` must be **explicitly set to true** before any OpenAI request is made.
- `cacheEnabled = false` disables response caching completely.
