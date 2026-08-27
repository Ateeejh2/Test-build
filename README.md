# WanderBot Ultimate Pit 1.11.1

Forge 1.8.9 / Java 8 client-side Pit recreation bot research project.

## Event AI
- Observes Pit Major/Minor event text from the local scoreboard.
- Maps each event to a policy profile.
- Major events can pause normal combat; Spire is treated as a special-rules event.
- Minor events can bias positioning/risk without forcing a full combat pause.
- Event strategy is consumed by the Pit combat coordinator.

## Controls
- K: Bot ON/OFF
- Right Shift: Clean & Modern ClickGUI

## Runtime execution orchestration
- `PitExecutionOrchestrator` is the single gateway between the master Pit decision and low-level subsystems.
- Each tick is assigned to WAIT, RECOVERY, COMBAT, or NAVIGATION ownership.
- Combat and navigation do not independently claim the same tick.
- Master Decision reasons remain available for debug HUD integration.


## v1.4.4 Event × Megastreak Strategy
- Added `PitEventStreakStrategy` to combine event state and selected megastreak state into one policy result.
- Added event-aware reward/risk modifiers for major/minor events.
- Major-event combat remains policy-blocked where rules require it.
- Fixed `PitStreakCatalog.Definition` constructor duplication and completed megastreak metadata fields.
- Regular killstreak slots remain outside bot control.

## v1.6.0 ClickGUI Control Center
- Persistent settings for render, combat, navigation, and megastreak selection.
- Added Reset All Settings action.
- Megastreak selection is applied live to the Pit streak controller.


## v1.7.0 Runtime Safety
- Added `PitRuntimeGuard` for safe handling of GUI screens, death, world/player context changes, and disconnects.
- GUI screens release all automated movement/attack input while open.
- World/player context changes clear stale navigation, targets, combat state and Pit runtime state.
- Disconnect forces a complete bot shutdown and input release.
- Debug Dashboard exposes the current runtime-guard status.
