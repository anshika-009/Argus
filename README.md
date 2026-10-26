# Argus

**A lightweight, offline desktop system monitor that warns you before your computer slows down or crashes.**

Argus watches CPU, memory, disk and battery in real time, shows live graphs, ranks the most resource-hungry processes, sends custom desktop alerts, and lets you safely terminate heavy processes. A small always-on-top mode keeps it visible while you work in other apps.

> Built by team **mergeConflict** (Team ID 430) for Softablitz 2026.

---

## Features

- **Live graphs:** CPU, memory, disk and battery charts that update every second.
- **Early warnings:** cards turn amber when a value gets high (70%+, or battery under 30%) and red when one of your alert rules fires. Graphs are filled area charts with a 0/50/100% scale. Each card carries a NORMAL / ELEVATED / ALERT tag, and a red banner appears across the top while an alert rule is firing.
- **Rogue process list:** top processes ranked by CPU or memory (with PID), refreshed every 2 seconds, with a name filter. Heavy CPU users are highlighted, and the dashboard shows the heaviest process right now with its PID.
- **Custom alerts:** set your own thresholds and durations. Alerts fire only after the limit has been breached continuously, so brief spikes don't cause spam. Triggers appear as desktop notifications and are saved to a log.
- **Compact mode:** a small always-on-top widget with live bars. Choose which metrics it shows in the Settings tab.
- **Safe process termination:** Argus asks for confirmation, then re-checks the process before ending it. It refuses Argus itself, critical Windows processes, system-account services and processes it has no permission to inspect, and it verifies the process start time so a recycled PID is never killed by mistake.
- **Light and dark themes:** switch with one click; warning colors stay readable in both.
- **Remembers your settings:** theme and compact-mode choices are saved and restored on the next start.
- **System tray:** closing the window keeps Argus monitoring in the tray.
- **Fault-tolerant sensors:** each sensor is read in isolation, so a failing one (for example no battery) shows "N/A" instead of crashing the app.
- **Stays responsive under load:** the sampler runs at minimum thread priority, and the UI skips stale frames.
- **Fully offline:** no network access, no accounts, no telemetry.

## Tech stack

| Library | Purpose | License |
| --- | --- | --- |
| [OSHI](https://github.com/oshi/oshi) | CPU, memory, disk, battery and process data | MIT |
| [JavaFX](https://openjfx.io) | UI, charts and window management | GPLv2 + Classpath |
| [AtlantaFX](https://github.com/mkpaz/atlantafx) | Base theme | MIT |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Persistent storage for alert rules and logs | Apache 2.0 |
| `java.awt.SystemTray` | Desktop notifications | JDK standard |

## Requirements

- **JDK 25** (JDK 21 or newer also works if you change `maven.compiler.release` in `pom.xml`)
- **Maven**, or IntelliJ IDEA, which bundles it
- Windows 10/11 (developed and tested on Windows)

## Running the app

### From IntelliJ IDEA

1. Open the project folder (`File → Open` → select `pom.xml` → *Open as Project*).
2. Wait for Maven to download dependencies.
3. In the **Maven** panel, go to `Plugins → javafx → javafx:run` and double-click it.

### From the command line

```bash
mvn javafx:run
```

## Project structure

The code follows the architecture in the Softablitz abstract (class diagram): one package per responsibility.

```
src/main/
├── java/org/tool/
│   ├── Main.java                        Entry point; launches ArgusApp
│   ├── ArgusApp.java                    The application: creates and connects every component, owns the windows and the theme
│   ├── model/                           Immutable data (records)
│   │   ├── SystemSnapshot.java          One reading of CPU, memory, disk and battery
│   │   ├── ProcessInfo.java             One running process (pid, name, cpu, memory, start time)
│   │   ├── AlertRule.java               One alert rule
│   │   └── AlertEvent.java              One entry of the event log
│   ├── sampler/
│   │   ├── MetricsSampler.java          Sampler thread: reads the sensors, builds snapshots, keeps the history
│   │   └── RingBuffer.java              Fixed-capacity circular buffer for graph history
│   ├── alert/
│   │   ├── AlertEngine.java             Breach-duration checks; fires notifications and logs events
│   │   └── NotificationService.java     Desktop notifications and the system-tray icon
│   ├── process/
│   │   ├── ProcessMonitor.java          Ranked process list, refreshed every 2 seconds
│   │   ├── ProcessTerminator.java       Safety checks, then terminates
│   │   └── ProtectedProcessPolicy.java  Processes that must never be terminated
│   ├── storage/
│   │   └── SettingsRepository.java      SQLite storage: rules, event log, settings
│   └── ui/
│       ├── DashboardController.java     Main window: dashboard, processes, alerts, settings
│       ├── CompactController.java       Small always-on-top window
│       └── Theme.java                   Shared stylesheet, night palette and logo
└── resources/
    ├── argus.css                        Theme (dark and light)
    ├── icons/                           App icon (PNG, several sizes)
    └── fonts/                           Space Grotesk Bold for the logo (OFL.txt = its license)
```

## How it works

```
MetricsSampler ──snapshots──▶ DashboardController, CompactController   (UI thread, once per frame)
MetricsSampler ──checks─────▶ AlertEngine ──fires──▶ NotificationService
                                         └──logs───▶ SettingsRepository
ProcessMonitor ──lists──────▶ DashboardController ──kill──▶ ProcessTerminator ──checks──▶ ProtectedProcessPolicy
```

1. On startup `ArgusApp` opens the SQLite database (`~/.argus/argus.db`), loads the saved alert rules and settings, and builds both windows.
2. The **sampler thread** (`MetricsSampler`, minimum priority) reads CPU, memory, disk and battery every second. Each sensor is read in its own try/catch, so a failing one shows "N/A" instead of stopping the pipeline. The result is an immutable `SystemSnapshot`, which is appended to a fixed-capacity **ring buffer** (the last 60 samples per metric) and passed to the `AlertEngine`.
3. The **alert engine** counts how many seconds in a row each rule has been breached. When the count reaches the rule's duration the alert fires once: a desktop notification is shown, the event is saved, and the matching card turns red.
4. The **UI thread** never queries the OS. Once per frame `ArgusApp` asks the sampler for the newest snapshot and hands it to the two controllers; if rendering lags, older snapshots are simply skipped.
5. Every 2 seconds the `ProcessMonitor` (also minimum priority) refreshes the process list. When you terminate a process, the `ProcessTerminator` re-checks it (protected list, permission, same start time) before killing it, on a background thread.

## Data storage

Alert rules, trigger logs and settings are stored locally in `~/.argus/argus.db` (on Windows: `C:\Users\<you>\.argus\argus.db`). Delete this file to reset Argus to its default rules.

## Known limitations

- Terminating some processes needs administrator rights. Run Argus as administrator if you see an "access denied" message.
- Desktop PCs usually have no battery sensor, so the battery card shows "N/A" there.

## Credits

- Logo typeface: [Space Grotesk](https://fonts.google.com/specimen/Space+Grotesk), SIL Open Font License 1.1 (`src/main/resources/fonts/OFL.txt`).
- The app icon and logo are original artwork made for this project.
