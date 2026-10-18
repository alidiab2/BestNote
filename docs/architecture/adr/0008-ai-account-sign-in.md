# ADR-0008: Sign in with your AI account (amends ADR-0007)

- **Status:** Accepted
- **Date:** 2026-10-18
- **Owner:** WS-12 (task AI-11b, briefed as "AI-11 Sign in with your AI account")

## Context
The owner asked: "let users use their own AI subscription and sign in like ChatGPT, instead of pasting API keys." ADR-0007 is bring-your-own-key only. We checked what an open-source, third-party Android app can **legitimately** do in October 2026. Two things were off the table from the start: reusing another product's OAuth client id (for example the Codex CLI or Claude Code client) and scraping consumer web sessions. Both break the providers' terms and would be blocked or revoked.

## Findings per provider

| Provider | What exists for third parties | Verdict |
|---|---|---|
| **OpenRouter** | Public OAuth PKCE. No client id or secret: the app is identified by its `callback_url`, and **localhost callbacks are supported on any port**. Codes are single-use and valid for 10 minutes. The exchange (`POST /api/v1/auth/keys`) returns a **user-controlled API key** that spends the user's own credits on GPT, Claude, Gemini, DeepSeek and others. `GET /api/v1/key` returns the label, limit, remaining limit, usage and free-tier flag. `/api/v1/credits` needs a management key, so we don't call it. | **Implemented.** Nothing to register. |
| **OpenAI "Sign in with ChatGPT"** | Identity sign-in has been in beta since mid-2026. Spending a ChatGPT Plus/Pro plan in other apps launched around DevDay (late Sep / early Oct 2026) for **16 named launch partners** (Cognition/Devin, Amp, Lovable, Notion, Vercel, OpenClaw…). OpenAI has published no open developer terms, no application form and no client registration for plan usage. | **Not implemented.** Not open to us. Revisit once OpenAI publishes developer docs; the account abstraction (`AccountStore`, `SignInPhase`) is provider-neutral. |
| **Anthropic Claude Pro/Max** | Since Jan–Feb 2026, Anthropic's terms restrict OAuth tokens from Free/Pro/Max plans to its own surfaces (Claude Code, claude.ai). Third parties may not offer Claude.ai logins or route requests through plan credentials, and developers are told to use API keys. | **Not allowed.** Claude stays available through an API key or through OpenRouter. |
| **Google Gemini (OAuth)** | The Gemini API OAuth quickstart is explicitly a *testing* setup ("simplified authentication … appropriate for a testing environment"). It uses the `generative-language.retriever` (and `cloud-platform`) scopes. With user OAuth credentials, quota and billing fall on the **OAuth client's Cloud project**, i.e. the BestNote owner's project, not on the user. `cloud-platform` is a sensitive scope, so a public app would also need Google's verification. The consumer **Google AI Pro** plan does not include API access. | **Not implemented.** It would not let users spend their own plan, and it would bill the owner. The low-friction path is a free Gemini key from Google AI Studio (the existing "Get a key" link). |

## Options
1. **OpenRouter OAuth PKCE with a loopback redirect** (RFC 8252 §7.3). It is documented, needs no registration, works on every Android browser, and gives one key for all major model families. On the downside, the user needs an OpenRouter account with credits (free models exist), and OpenRouter shows a generic title for localhost apps.
2. **OpenRouter with a custom-scheme or https App Link callback.** It gives nicer attribution, but custom schemes are undocumented for OpenRouter, and an App Link needs a domain with `assetlinks.json`. It could be added later behind a config value.
3. **Wait for "Sign in with ChatGPT" partner access.** It doesn't solve anything today.

## Decision
Option 1.
- `:ai:vision-providers` → `account/`. `Pkce` (RFC 7636 S256), `OpenRouterAuth` (auth URL, callback parsing with a `state` check, code→key exchange, `/key` status), `LoopbackReceiver` (127.0.0.1 only, random port, ignores other paths, drains the request so browsers don't see a reset) and `AiAccounts` (app-scoped flow so it survives Settings going behind the browser; 10-minute timeout; cancel; sign out; manage link `openrouter.ai/keys/<sha256>`).
- The key is stored exactly like a pasted key: AES-GCM with a non-exportable Android Keystore key (`KeystoreAiSettings`), plus a `signed_in` flag. It is never logged, and it goes into the debug-log redaction list like the other keys.
- New `AiProviderKind.OpenRouter` (contract change in `:ai:vision-api`; every `when` updated). It uses the OpenAI wire format with a fixed base URL, so Smart Board, MATH-01 equations, Tidy, Convert with AI and Design with AI use the signed-in account through `AiProviders.create()` with no further changes. Signing in makes OpenRouter the active provider. The live model list, automatic Best/Fast and the existing picker apply. The fallback before the list loads is `openrouter/auto`.
- Return to the app: the callback page links to `<applicationId>://ai-account/done`. `AiAccountReturnActivity` brings the app's task forward with `CLEAR_TOP | SINGLE_TOP`, which closes the Custom Tab.
- Settings shows "AI account" first, with an explainer on why ChatGPT Plus/Claude Pro can't be used. "Or paste an API key (advanced)" stays below.

## Consequences
- The owner has nothing to register. If the owner later wants an https callback (OpenRouter attribution and rankings), add an App Link domain and switch `callback_url`. The flow code is redirect-agnostic (`parseCallback` accepts a full URL).
- If OpenAI opens plan sign-in to all developers, add a second `AccountStore`/auth implementation. The UI section is already per-account.
- The loopback listener lives only while a sign-in is pending (at most 10 minutes) and closes on success, failure, cancel or timeout.

## Sources (checked 2026-10-18)
- OpenRouter OAuth PKCE: https://openrouter.ai/docs/guides/overview/auth/oauth
- OpenRouter key info (`GET /api/v1/key`): https://openrouter.ai/docs/api/reference/limits
- OpenRouter credits (management key only): https://openrouter.ai/docs/api/api-reference/credits/get-credits
- "Sign in with ChatGPT" plan usage, 16 launch partners: https://itbrief.co.uk/story/openai-launches-chatgpt-sign-in-for-16-partner-tools, https://www.thestar.com.my/tech/tech-news/2026/10/04/openai-expands-chatgpt-with-apps-and-third-party-sign-in, https://thenewstack.io/sign-in-with-chatgpt/
- Anthropic restricts subscription OAuth to first-party surfaces: https://winbuzzer.com/2026/02/19/anthropic-bans-claude-subscription-oauth-in-third-party-apps-xcxwbn/, https://alternativeto.net/news/2026/2/anthropic-officially-bans-using-subscription-authentication-for-third-party-claude-use
- Gemini API OAuth quickstart (testing setup, scopes): https://ai.google.dev/gemini-api/docs/oauth
- RFC 7636 (PKCE), RFC 8252 §7.3 (loopback redirect for native apps)
