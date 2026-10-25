# Argus

**A lightweight, offline desktop system monitor that warns you before your computer slows down or crashes.**

Argus watches CPU, memory, disk and battery in real time, shows live graphs, ranks the most resource-hungry processes, sends custom desktop alerts, and lets you safely terminate heavy processes. A small always-on-top mode keeps it visible while you work in other apps.

> Built by team **mergeConflict** (Team ID 430) for Softablitz 2026.

---

## Features

- **Live graphs:** CPU, memory, disk and battery charts that update every second.
- **Early warnings:** cards turn amber when a value gets high (70%+, or battery under 30%) and red when one of your alert rules fires. Graphs are filled area charts with a 0/50/100% scale. Each card carries a NORMAL / ELEVATED / ALERT tag, and a red banner appears across the top while an alert rule is firing.
- **Rogue process list:** top processes ranked by CPU or memory, refreshed every 2 seconds, with a name filter. Heavy CPU users are highlighted.
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

```
src/main/
├── java/org/tool/
│   ├── Main.java          UI: dashboard, processes, alerts, compact mode, tray
│   ├── Sampler.java       OSHI wrapper that builds immutable system snapshots
│   ├── RingBuffer.java    Fixed-capacity circular buffer for graph history
│   ├── AlertEngine.java   Breach-duration checks for alert rules
│   ├── Db.java            SQLite storage for rules, logs and settings
│   ├── ProcessGuard.java  Safety checks run before terminating a process
│   └── Rule.java          Alert rule model
└── resources/
    ├── argus.css          Theme (dark and light)
    ├── icons/             Pixel-art app icon, nav and card icons
    └── fonts/             Press Start 2P (OFL)
```

## How it works

1. On startup Argus opens its SQLite database (`~/.argus/argus.db`) and loads the saved alert rules.
2. A background **sampler thread** reads CPU, memory, disk and battery every second and produces an immutable snapshot.
3. The **alert engine** counts how many seconds in a row each rule has been breached. When the count reaches the rule's duration, the alert fires once: it is logged, shown as a tray notification, and the matching card turns red.
4. The sampler publishes each snapshot and appends it to a fixed-capacity **ring buffer** (the last 60 samples per metric), so memory use stays constant however long Argus runs. The **UI thread** renders only the newest snapshot once per frame and never queries the OS; if rendering lags, older frames are skipped.
5. Every 2 seconds the process list is refreshed. Before terminating, Argus asks for confirmation and refuses protected processes.

## Data storage

Alert rules, trigger logs and settings are stored locally in `~/.argus/argus.db` (on Windows: `C:\Users\<you>\.argus\argus.db`). Delete this file to reset Argus to its default rules.

## Known limitations

- Terminating some processes needs administrator rights. Run Argus as administrator if you see an "access denied" message.
- Desktop PCs usually have no battery sensor, so the battery card shows "N/A" there.

## Credits

- Pixel font: [Press Start 2P](https://fonts.google.com/specimen/Press+Start+2P) by CodeMan38, SIL Open Font License 1.1 (`src/main/resources/fonts/OFL.txt`).
- All icons are original pixel art made for this project in a blocky, voxel-game style. Argus is not affiliated with or endorsed by Mojang or Microsoft.