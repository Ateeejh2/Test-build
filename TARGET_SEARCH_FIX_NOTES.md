# Target Search / Combat Path Hotfix

This build fixes the post-startup state where WanderBot could remain forever in
`STREAKING / WALKING` + `Decision SEARCH` without actually moving.

## Root causes fixed

1. TargetTracker used a bounded CombatPathFinder A* probe as a hard acquisition
   filter. One failed probe removed an otherwise valid player from consideration.
2. TargetTracker used an overly strict armor gate. The search fix keeps
   non-diamond opponents eligible while preserving diamond armor as a hard exclusion.
3. BotController's SEARCH branch released movement and returned. SEARCH had no
   navigation implementation, so no-target meant stand still forever.
4. CombatControlModel treated targets farther than 18 blocks as invalid even
   though the combat path planner supports a much larger local envelope.
5. CombatPathFinder had no robust fallback when its intentionally bounded fast
   A* failed on complex terrain.

## New behavior

- Target selection is cheap and does not run pathfinding first.
- Every ordinary loaded non-protected non-diamond player can be a target;
  iron/chain remains a score preference rather than a hard requirement.
- Any player wearing one or more diamond armor pieces is excluded from combat
  acquisition and SEARCH/HUNT navigation.
- Local combat acquisition is limited to 58 blocks, matching the 64-block combat
  path search envelope with safety margin.
- If no local target exists, TargetSearchNavigator actively moves:
  - toward the nearest loaded valid opponent using an intermediate walkable goal;
  - otherwise along a local patrol route.
- Search routes replan when stalled instead of remaining stationary.
- If fast CombatPathFinder A* fails, it falls back to the terrain-aware
  hierarchical PathFinder used by startup.
- Live Diagnostics now includes a Search row:
  - `L` = loaded players
  - `U` = usable opponents after validity/protected/spawn filtering
  - `N` = usable opponents inside the 58-block local combat envelope

## Validation in this environment

- Java parser-error scan: no parser-level errors introduced.
- TargetTracker policy check: diamond-armored opponents are rejected while
  non-diamond opponents remain available for acquisition/search navigation.
- TargetSearchNavigator synthetic test: creates a hunt route toward a far loaded
  opponent.
- Full ForgeGradle build still needs the real Forge 1.8.9 environment.
