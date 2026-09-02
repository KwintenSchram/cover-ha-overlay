# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| 1.1.x | ✅ |
| 1.0.0 | ❌ — signed with the public Android debug key; please upgrade |

## Design

- **Zero cloud intermediaries.** All communication is direct between your Android device and your
  Home Assistant instance (local LAN or your own HTTPS endpoint). No third-party servers.
- **Keystore-backed storage.** Long-Lived Access Tokens and server configuration are stored in
  `EncryptedSharedPreferences` (AES-256-GCM, `MasterKey`). Both the encrypted store and the
  plaintext fallback store are excluded from cloud backup and device transfer.
- **Honest fallback.** If Keystore initialisation fails, the app falls back to app-private
  plaintext preferences and displays a warning banner. It does not claim encryption it is not
  providing.
- **No remote configuration in release builds.** The configuration-restore hook is compiled out
  of release builds, requires an explicit `ACTION_RESTORE_CONFIG` intent, and only reads inline
  intent extras — never a filesystem path.
- **Scoped touch capture.** The overlay window is `WRAP_CONTENT` with `FLAG_NOT_FOCUSABLE` and
  `FLAG_NOT_TOUCH_MODAL`; touches outside the button cluster pass through untouched.
- **Conservative display targeting.** External and remote displays (casting, HDMI, DeX, virtual)
  are rejected outright, so controls cannot be rendered onto a screen you do not control.
- **No data collection.** No telemetry, analytics, tracking SDKs, or advertising libraries.

## Known limitations

- **Cleartext HTTP is permitted.** Most Home Assistant installs are plain HTTP on the LAN, and
  Android's network-security-config cannot express private IP ranges, so `usesCleartextTraffic`
  remains enabled. The setup screen warns when an `http://` URL points at a host outside
  RFC1918 / loopback / `.local`, where your token would be sent unencrypted. Use HTTPS for any
  internet-reachable instance.
- **A long-lived token is a powerful credential.** Anyone with physical access to an unlocked
  device can read it back from the setup screen. Scope your Home Assistant user accordingly.
- **Lock-screen actions are, by design, available without unlocking.** That is the entire point
  of the app. Use the guard-sensor and locked-state confirmation options on anything physical.

## Reporting a Vulnerability

Please do **not** open a public issue. Open a
[private security advisory](https://github.com/KwintenSchram/cover-ha-overlay/security/advisories/new)
on GitHub, or email the maintainer. Expect an initial response within 7 days.
