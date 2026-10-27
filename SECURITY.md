# Security

## What we protect

The Discord user token. Anyone holding it can act as you on Discord, so the app treats it like a password.

## Where the token goes

1. **In**: from the in-app Discord login (read once from the page's storage after sign-in) or pasted by you. It's checked with `GET https://discord.com/api/v10/users/@me` before it's stored.
2. **At rest**: AES-256-GCM ciphertext in app-private storage. The key lives in the Android Keystore (hardware-backed TEE or StrongBox where available) and can't be exported.
3. **Out**: only to
   - `https://discord.com/api/...` (profile, image proxy, application assets), and
   - `wss://gateway.discord.gg` (inside IDENTIFY / RESUME frames).

Nothing else ever receives it. This is enforced in code (`security/TokenGuard.kt`), not just by convention, and checked by tests.

## What we deliberately don't do

- No analytics, telemetry, or crash upload. Crash reports stay on the device until you choose to share them, and they're redacted.
- No third-party server of our own. Image uploads (opt-in) go to catbox.moe without any account data.
- No `QUERY_ALL_PACKAGES`; only launchable apps are visible.
- No `addJavascriptInterface` in the login WebView, and remote WebView debugging is disabled.

## Known limits

- **Root / malware with root**: a rooted attacker can hook the app process and read the token from memory while it runs. Keystore encryption protects data at rest, not a compromised OS.
- **Discord's side**: the token is sent to Discord by design. If your token leaks some other way, **change your Discord password**: that invalidates every existing token.
- **No certificate pinning**: Discord rotates certificates through its CDN, so pinning would break the app. User-added CAs are not trusted, which blocks the common "install this certificate" MITM.
- **Devices without a screen lock** can't gate reveal/copy with authentication; the app warns about this.
- **Self-botting**: using a user token on the gateway is against Discord's Terms. That's an account risk, not a token-leak risk.

## Reporting a vulnerability

Please open a private security advisory on this repository (Security › Advisories › Report a vulnerability). Do **not** include a real token in any report.
