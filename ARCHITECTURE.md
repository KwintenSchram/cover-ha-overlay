# Architecture & Technical Design

**Cover HA Overlay** is engineered specifically for foldable Android devices (Samsung Galaxy Z Flip / Fold) to provide zero-latency Home Assistant quick controls directly on the outer cover display.

---

## 1. System Overview

```mermaid
graph TD
    subgraph Hardware Layer
        D0[Main Display #0<br>1080x2520]
        D1[Cover Display #1<br>948x1048]
        FS[Samsung Fold Sensors<br>Hinge & Power]
    end

    subgraph Service & Lifecycle Layer
        FSD[FoldStateDetector<br>Power & Broadcast Listener]
        CDM[CoverDisplayManager<br>Multi-Heuristic Discovery]
        COS[CoverOverlayService<br>Foreground Lifecycle Service]
    end

    subgraph UI & Windowing Layer
        WC[Display WindowContext<br>TYPE_APPLICATION_OVERLAY]
        COV[CoverOverlayView<br>Strict WRAP_CONTENT Bounding Box]
        TP[Touch Passthrough<br>FLAG_NOT_FOCUSABLE]
    end

    subgraph Network Layer
        HAC[HomeAssistantClient<br>OkHttp REST API]
        HAW[HomeAssistantWebSocket<br>Real-Time State Subscriptions]
        SPM[StatePollingManager<br>Periodic Sync Fallback]
    end

    FS -->|State Changed| FSD
    FSD -->|Folded & Active| COS
    CDM -->|Resolves Display #1| COS
    COS -->|Attaches to D1| WC
    WC --> COV
    COV --> TP
    COV -->|Tap Action| COS
    COS -->|Direct Dispatch| HAC
    HAW -->|State Flow Updates| COV
```

---

## 2. Multi-Display Window Targeting

### A. Samsung Outer Display Discovery
On Samsung One UI, standard calls to `DisplayManager.getDisplays()` without flags often omit internal secondary presentation surfaces. Cover HA Overlay queries `displayManager.getDisplays(null)` and `displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)` and applies a multi-heuristic evaluation:
1. **Name Matching**: Checks for `"sub"`, `"cover"`, `"secondary"`, `"flip"`, `"flex"`.
2. **Aspect Ratio & Resolution Signatures**:
   - **Galaxy Z Flip7**: $948 \times 1048$ ($\approx 1.10$ aspect ratio)
   - **Galaxy Z Flip5 / Flip6**: $720 \times 748$ ($\approx 1.04$ aspect ratio)
   - **Galaxy Z Flip3 / Flip4**: $260 \times 512$ ($\approx 1.96$ aspect ratio)
3. **Strict Main Screen Immunity**: The target resolver returns `null` rather than falling back to Display #0, guaranteeing the overlay never obstructs the internal foldable display.

### B. Window Context Creation (API 31+)
```kotlin
val displayContext = context.createDisplayContext(coverDisplay)
val windowContext = displayContext.createWindowContext(
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 
    null
)
val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
```

---

## 3. Fold State & Power Lifecycle

```mermaid
sequenceDiagram
    participant Device as Galaxy Z Flip Hinge
    participant System as Android PowerManager
    participant Detector as FoldStateDetector
    participant Service as CoverOverlayService
    participant Overlay as CoverOverlayView

    Note over Device,Overlay: Device Folded (Closed)
    Device->>System: Cover Screen Display.STATE_ON
    System->>Detector: onDisplayChanged(Display 1)
    Detector->>Service: isCoverDisplayActive = true, foldState = FOLDED
    Service->>Overlay: attachOverlay(Display 1)
    Service->>Service: haWebSocket.resume()

    Note over Device,Overlay: Device Unfolded (Opened)
    Device->>System: Main Screen Display.STATE_ON, Cover Display STATE_OFF
    System->>Detector: onDisplayChanged(Display 0)
    Detector->>Service: isCoverDisplayActive = false, foldState = UNFOLDED
    Service->>Overlay: detachOverlay()
    Service->>Service: haWebSocket.pause()
```

---

## 4. Touch Passthrough & Non-Intrusive Bounding Box

Unlike full-screen overlay apps that capture the entire display touch surface:
- **`WRAP_CONTENT` Dimensions**: WindowManager bounds are strictly shrunk to the bounding rectangle of the floating pill.
- **Window Flags**:
  ```kotlin
  val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
  ```
- **Result**: Swiping, tapping clock widgets, or interacting with native One UI cover notifications outside the button icons passes straight through with zero latency.

---

## 5. Security & Power Efficiency

1. **Android Keystore (AES-256-GCM)**: Access tokens and server configuration are encrypted using `EncryptedSharedPreferences` backed by hardware-backed Android Keystore keys.
2. **Zero WakeLocks**: When the cover screen is off, all network coroutines and WebSockets are paused, allowing the device to enter Android **Doze mode** with $0.0\%$ idle CPU drain.
