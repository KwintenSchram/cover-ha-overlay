# [Showcase] Cover HA Overlay: Instant Home Assistant Quick Controls for Galaxy Z Flip Cover Screen!

**Category:** Share Your Projects!
**Tags:** `android`, `mobile`, `samsung`, `foldables`, `custom-component`, `dashboard`

Hey everyone! 👋

If you own a **Samsung Galaxy Z Flip** (Flip5, Flip6, or the new Flip7) and use Home Assistant, you probably know how frustrating it is to toggle a light or open your front door from the outer screen:
- You have to wake the cover screen, swipe past 3-4 widget panels to find Good Lock / MultiStar, tap the Home Assistant app, wait 2–3 seconds for the WebView to load, and then tap your button.

I wanted something **instant, native, and zero-friction** — so I built **Cover HA Overlay**!

---

### ✨ What does it do?
It renders a compact, translucent floating quick-control bar directly on the Samsung Cover Display (Flex Window):
- ⚡ **Instant Execution (~100ms)**: Tapping an icon fires your REST/WebSocket service in the background with zero full-screen popups.
- 🔒 **Works on Locked Cover Screen**: Trigger quick actions as soon as the screen wakes without unlocking with fingerprint or PIN.
- 👆 **True Touch-Passthrough**: The overlay is strictly sized to the button cluster — all taps/swipes outside the icons pass straight through to your Samsung clock and native widgets.
- 🛡️ **Smart Safety Guards**:
  - **Sensor Verification**: e.g., only open your door if a hallway sensor is clear, with automatic double-pulse warning vibration and 10s override window if triggered!
  - **Lock Confirmation**: Prevent accidental unlatching if your smart lock is currently in a "locked" state.
- 🔋 **Zero Idle Battery Drain**: The app listens directly to display power state events. When your cover screen is asleep, all WebSockets and polling loops are paused ($0.0\%$ CPU).
- 🔐 **Privacy-First**: No analytics, no cloud relays, and your Long-Lived Access Tokens are encrypted with hardware-backed Android Keystore (AES-256-GCM).

---

### 📸 Preview
*(Attach `flip7_cover_screen_doors.png` here)*

---

### 📦 Download & Open Source
- **GitHub**: `https://github.com/KwintenSchram/cover-ha-overlay`
- **F-Droid**: Coming soon (Recipe submitted!)
- **Direct APK**: Download the latest release from the GitHub Releases tab.

Would love to hear your feedback, feature ideas, and what entities you'd put on your cover screen!

