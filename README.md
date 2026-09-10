# WanderBot 1.11.8

Minecraft Forge 1.8.9 / Java 8 client-side Pit navigation and combat-policy project.

## Controls
- `K` — toggle WanderBot
- `Right Shift` — open the modern control dashboard

## Runtime architecture
- `PitDecisionEngine` publishes one consistent per-tick Pit snapshot.
- `PitMasterDecisionEngine` turns that snapshot into the live high-level action.
- `PitExecutionOrchestrator` assigns each tick to wait, recovery, navigation or combat.
- `CombatPathFinder` and `CombatSteering` own combat route planning and locomotion.
- `KillAuraBridge` synchronizes Myau state and arbitrates combat camera ownership.
- WanderBot does not write combat yaw/pitch while Myau may own combat aim.

## Rendering / GUI
- Modern dark dashboard with persisted settings and live diagnostics.
- Navigation and combat paths are rendered separately.
- Path rendering uses distance culling, bounded forward-node rendering and straight-segment merging.
- HUD/debug text is cached to reduce per-frame allocations.

## Configuration
Runtime-consumed settings are stored in `config/wanderbot.cfg`. Obsolete settings from older builds are ignored.
`Force Pit Mode` defaults to off so the normal scoreboard/event policy is used unless explicitly enabled.

## Build
The project uses ForgeGradle 2.1, Java 8 and Minecraft Forge 1.8.9.

```bash
gradle clean build
```

GitHub Actions builds with JDK 8 and Gradle 2.14.
