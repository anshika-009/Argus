# Argus

**A lightweight, offline desktop system monitor that warns you before your computer slows down or crashes.**

Argus watches CPU, memory, disk and battery in real time, shows live graphs, ranks the most resource-hungry processes, sends custom desktop alerts, and lets you safely terminate heavy processes. A small always-on-top mode keeps it visible while you work in other apps.

> Built by team **mergeConflict** (Team ID 430) for Softablitz 2026.

---

## Features

- **Live graphs:** CPU, memory, disk and battery charts that update every second.
- **Warning indicators:** a card turns red when a value stays beyond your limit.
- **Rogue process list:** top processes ranked by CPU or memory, refreshed every 2 seconds.
- **Custom alerts:** set your own thresholds and durations. Alerts fire only after the limit has been breached continuously, so brief spikes don't cause spam. Triggers appear as desktop notifications and are saved to a log.
- **Compact mode:** a small always-on-top widget with live bars for each metric.
- **Terminate process:** select a process, confirm, and Argus ends it. System-critical processes are protected, and permission errors are reported clearly.
- **System tray:** closing the window keeps Argus monitoring in the tray.
- **Battery-safe:** machines without a battery show "N/A" instead of crashing.
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
│   ├── AlertEngine.java   Breach-duration checks for alert rules
│   ├── Db.java            SQLite storage for rules and trigger logs
│   └── Rule.java          Alert rule model
└── resources/
    └── argus.css          Green-on-black terminal theme
```

## How it works

1. On startup Argus opens its SQLite database (`~/.argus/argus.db`) and loads the saved alert rules.
2. A background **sampler thread** reads CPU, memory, disk and battery every second and produces an immutable snapshot.
3. The **alert engine** counts how many seconds in a row each rule has been breached. When the count reaches the rule's duration, the alert fires once: it is logged, shown as a tray notification, and the matching card turns red.
4. The **UI thread** only renders the latest snapshot into the charts. A fixed-size history of the last 60 samples bounds memory use no matter how long Argus runs.
5. Every 2 seconds the process list is refreshed. Before terminating, Argus asks for confirmation and refuses protected processes.

## Data storage

Alert rules and trigger logs are stored locally in `~/.argus/argus.db` (on Windows: `C:\Users\<you>\.argus\argus.db`). Delete this file to reset Argus to its default rules.

## Known limitations

- Terminating some processes needs administrator rights. Run Argus as administrator if you see an "access denied" message.
- Desktop PCs usually have no battery sensor, so the battery card shows "N/A" there.

## Roadmap

- Light/Dark theme switch
- Choose which metrics appear in compact mode
- Persist user settings across restarts
- Stricter process-termination safety checks (start-time validation, critical-process list)
- Frame dropping and sensor-level fault isolation under heavy load

