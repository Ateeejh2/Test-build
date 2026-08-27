# WanderBot 1.9.0 Regression Audit

Date: 2026-08-27

## Scope
- 56 Java source files under `src/main/java`.
- Forge entry point and event registrations.
- Key bindings and ClickGUI opening.
- Runtime Guard reset paths.
- Settings usage and propagation.
- Render registrations.
- Build configuration and source layout.
- Legacy version references.
- TODO/FIXME/stub markers.

## Results
- Java structural delimiter scan: PASS (0 errors).
- Duplicate Java basenames: PASS (0 duplicates).
- Legacy application version references: PASS (`1.9.0-regression-verified`).
- TODO/FIXME/placeholder/stub markers: PASS (none found).
- Root-level duplicate Java sources: removed; production sources live under `src/main/java`.
- Event bus registration: PASS; main entry + PathRenderer + PitHudRenderer + PitDebugDashboard registered once each.
- Key bindings: PASS; K toggle and Right Shift ClickGUI registered once each.
- Settings propagation: PASS for combat range, target scan range, retreat health, crowd threshold, navigation settings, render flags, and Megastreak selection.
- Render hooks: PASS; world render and overlay render hooks are present.
- Build config: ForgeGradle 2.1-SNAPSHOT, Minecraft/Forge 1.8.9, Java 8 target, Gradle 2.14.1 in CI.
- ZIP archive structure: PASS after cleanup.

## Important limitation
A real Forge 1.8.9 + Java 8 Gradle compilation was not executed in this environment because Gradle 2.14.1, a Gradle wrapper, and Java 8 are not locally available.
