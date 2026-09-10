# Test-build refactor notes

Prepared against `Ateeejh2/Test-build` `main` commit:
`70e966be8bad7bb79edbda6d7344fc5b26b2135f`.

This is a **source overlay**, not a complete clone. Direct `git clone`/dependency downloads are blocked in the execution environment, so unchanged files are intentionally omitted. Apply the overlay to a real clone with `APPLY_REFACTOR.sh`.

## Scope

The repository was reviewed by package and the high-complexity / mixed-responsibility code was refactored. Small enums, DTOs and already single-purpose navigation/render helpers were intentionally left unchanged; rewriting them would add diff noise without improving the design.

### `bot`

- `BotController` is now primarily an orchestrator rather than a container for unrelated runtime mechanics.
- Death/restart heuristics moved to new `DeathRestartDetector`.
- Myau KillAura / `.enemy` chat synchronization moved to new `KillAuraBridge`.
- Repeated automated input cleanup was centralized.
- Runtime-guard handling, target validation and combat cleanup were simplified.
- Public getters and controller entry points were kept compatible.
- `MovementController` centralizes key-state writes and release behavior.

### Mod entry point

- `WanderBotMod` now normalizes chat through helpers and precompiles the death-message pattern.
- Client ticking runs only on `TickEvent.Phase.END`; the old code called `BOT.tick()` for both Forge client-tick phases, which could effectively double-tick bot state.
- Removed `Keyboard.next()` from the key event handler. Advancing LWJGL's event queue there is not a reliable way to cancel Forge input and can consume unrelated events.

### `config` / `humanization`

- `WanderBotSettings` is split by config domain with category constants and typed save helpers.
- Fixed existing `Configuration.get(category, key, ...)` save calls where category/key order had drifted.
- Humanization settings that existed as fields but were effectively disconnected are now loaded, saved, clamped and consumed by `Humanizer`.
- `Humanizer` uses configured SD/chance values and becomes deterministic/neutral when humanization is disabled.
- Existing forced testing behavior (`forcePitMode = true`, full loaded-player scan) is preserved to avoid silently changing runtime policy.

### `gui`

- `ClickGuiScreen` was expanded from dense/minified logic into category-specific builders and typed setting actions.
- Removed string-prefix dispatch such as checking a label to decide which setting to mutate.
- The old `Target Scan` 8..32 control was misleading because loading settings forces the scan to the full loaded-player range; the GUI now presents that behavior as information instead of a non-functional control.

### `pit`

- `CombatExecutionController.tick()` was decomposed into target refresh, threat sampling, navigation, retreat movement, combat-path preparation, rotation, steering, state update, telemetry and debug helpers.
- Constructor initialization is shared and partial-null `CombatThreatField` construction is avoided.
- Retreat navigation is computed once per tick instead of twice.
- `CombatPhaseController` removes unused state/helpers and centralizes phase transitions, threat context, retreat path refresh/following and recovery handling.
- Stale combat-target context is cleared when a target becomes invalid.
- `PitDecisionEngine` consolidates repeated event/runtime reset paths and removes redundant evaluation/temporary state.

### `pathfinding`

- `PathFinder` no longer stores per-search caches in static mutable fields. A `SearchContext` owns nodes, costs, closed set and terrain caches for one invocation, making nested/re-entrant searches safe.
- A* neighbor expansion and path concatenation were extracted into focused helpers.
- `CombatPathFinder` now uses an explicit per-search context, removes unused prediction bookkeeping and separates bounded replan policy from A* expansion.
- `CombatSteering` now separates path progression, corridor-point selection, world-to-local movement conversion, strafe hysteresis and obstacle jumping. Magic thresholds are named constants.

## Findings deliberately not turned into behavior changes

1. `forcePitMode` is still forcibly enabled on load. The GUI can toggle it for the current session, but reload restores testing mode. This looks intentional in the existing source comments; changing it should be a product decision.
2. The regular target scan remains effectively unlimited over loaded players. Reducing it changes target-selection behavior, so the refactor only made the GUI honest about it.
3. `PathFinder.dangerPenalty()` had an `isSafeDrop()` branch that added `0.0`; it was behaviorally dead. No guessed penalty value was introduced.
4. `CombatSteering.Result.sprint` is calculated, while the execution controller historically chooses sprint independently. Wiring that field into execution would change combat movement and is left for a behavior-focused change.
5. Root-level historical `.txt`, report `.md`, and release `.zip` files look like development artifacts. They were not deleted because ownership/history expectations are unclear. Consider moving release binaries to GitHub Releases and old scratch files to `docs/archive/` or removing them in a separate cleanup commit.

## Validation performed here

- Ran `javac -proc:none` over the overlay to force Java parsing. The expected errors are unresolved Minecraft/Forge/unchanged-project symbols because dependencies and the rest of the clone are unavailable; no Java parser errors were found in the refactored sources.
- A real Forge/Gradle build **has not been verified in this environment**. After applying to a real clone, run `VERIFY_REFACTOR.sh` or the repository's normal CI before merging.

## Suggested commit split

For easier review, consider committing in this order:

1. `refactor: isolate bot runtime helpers`
2. `refactor: normalize settings and humanizer configuration`
3. `refactor: simplify GUI setting actions`
4. `refactor: decompose combat orchestration`
5. `refactor: scope pathfinding search state`

If you prefer one commit, `refactor: simplify WanderBot runtime and pathfinding architecture` is a reasonable message.
