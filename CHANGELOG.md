# Changelog

All notable changes to **Cover HA Overlay** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-08-29

### Initial Release
- **Samsung Galaxy Z Flip Support**: Multi-heuristic cover screen targeting for Flip7 ($948 \times 1048$), Flip5/Flip6 ($720 \times 748$), and Flip3/Flip4 ($260 \times 512$).
- **Locked Screen Quick Actions**: Control smart home devices directly on the locked cover screen via `FLAG_SHOW_WHEN_LOCKED` without device unlock.
- **True Touch Passthrough**: Floating overlay strictly constrained to button cluster bounding box (`WRAP_CONTENT`), preserving full touch interaction with native clock and widgets.
- **Instant Background Dispatch**: Zero Activity popups; background REST/WebSocket service dispatch in ~100ms.
- **Safety Guards & Confirmation Windows**:
  - Binary sensor pre-check (e.g. hallway optical sensor) with double warning vibration and 10s override window.
  - Smart lock confirmation guard (double-tap confirmation before unlatching locked doors).
- **Zero Idle Power Drain**: Automatic pause of WebSockets and polling coroutines when the cover screen sleeps.
- **End-to-End Encryption**: Android Keystore AES-256-GCM encrypted local storage for access tokens and server URLs.
- **Material 3 Setup UI**: Entity browser, domain-smart service picker, custom color palettes, and 9-point docking layout selector.
- **One UI Resilience**: Foreground service with boot auto-restart and watchdog heartbeat.
