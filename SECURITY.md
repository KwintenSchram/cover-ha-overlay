# Security Policy

## Security Guarantees
Cover HA Overlay is designed with a strict privacy-first architecture:
- **Zero Cloud Intermediaries**: All communication is direct between your Android device and your Home Assistant instance (local LAN or private HTTPS).
- **Android Keystore Encryption**: Home Assistant Long-Lived Access Tokens and sensitive configuration parameters are stored locally using Android Keystore-backed `EncryptedSharedPreferences` (AES-256-GCM / MasterKey).
- **Strict Touch Passthrough**: The overlay window strictly scopes its touch target to the button cluster bounding box (`WRAP_CONTENT`), never capturing screen touches outside the button icons.
- **Zero Data Collection**: No telemetry, analytics, tracking SDKs, or external advertising libraries are present in this app.

## Reporting a Vulnerability
If you discover a security vulnerability in Cover HA Overlay, please do **NOT** open a public issue.
Instead, send a detailed security report via email to the project maintainers or open a Private Security Advisory on GitHub.
