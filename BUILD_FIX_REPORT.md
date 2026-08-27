# Build fix report

The GitHub Actions build reached `:compileJava` successfully after downloading ForgeGradle and Minecraft/Forge 1.8.9 dependencies. The original failure was 34 Java compile errors.

Fixed in this candidate:
- Added `CombatNavigationCoordinator` compatibility facade used by combat telemetry.
- Added missing `BotController.PathChoice` helper.
- Added `StreakControlManager.evaluateTriggers(int)`.
- Replaced 1.8.9-incompatible `getCommandSenderName()` calls with `getName()`.
- Replaced invalid `ConfigCategory.setProperty(...)` calls with Forge `Configuration.get(...).set(...)` property updates.
- Fixed `StreakStrategyEngine.Result` reward reference in `PitCombatStrategyCoordinator`.
- Fixed `PitMasterDecisionEngine` to call the rules engine instead of a non-existent method on `PitRulesEngine.State`.
- Added `PitDecisionEngine.getTargetTracker()` and `getRules()` accessors.

Static source checks:
- 57 Java source files
- structural delimiter check: 0 errors
- public class/file-name mismatch: 0

Note: this environment does not contain Gradle/Java 8, so the final confirmation still needs one more GitHub Actions run.
