# Final optimization pass

This build is based on the modern GUI v2 project and includes the final runtime cleanup pass.

## Correctness fixes
- Added acknowledged Myau KillAura state transitions with explicit `DISABLED / REQUESTING_ON / ACTIVE / REQUESTING_OFF / FAULT` states.
- WanderBot yields combat camera ownership as soon as Myau may own it and retakes rotation only after an explicit OFF acknowledgement.
- Startup/restart waits for confirmed Myau OFF before WanderBot navigation writes camera rotation.
- Removed WanderBot ownership of the Minecraft attack key entirely; Myau is the sole attack controller, avoiding `keyBindAttack=false` conflicts.
- A stale pending Myau ON request is cancelled immediately when the target leaves range; OFF requests are safety-critical and do not wait on the normal command cooldown.
- Fixed Pit mode timers so mode duration resets only on actual transitions.
- Fixed Pit runtime snapshots so normal-mode snapshots are published after the final policy decision for that tick.
- Fixed `TargetTracker.isViable` so its maximum-distance argument is actually enforced and null self input is rejected safely.
- Fixed stale combat route/telemetry state after leaving combat by adding a cheap idempotent suspend path.
- Force Pit Mode now defaults to off and remains an explicit opt-in bypass; forced mode no longer accidentally re-enters event policy.
- Combat range and Myau handoff range are kept coherent: Myau handoff can never be configured closer than the preferred combat stop distance.
- Bot tick timing no longer averages idle/OFF ticks, so the GUI performance metric reflects active runtime work.

## Performance
- Target full rescans are throttled to 200 ms while a valid target is stable.
- Reachability results are cached for 750 ms and pruned periodically.
- Candidates are cheap-ranked first, then A* reachability is evaluated in score order and stops at the highest-scoring reachable target.
- Candidate armor is parsed once per scan and reused; crowd pressure no longer reparses every player's armor for every candidate.
- Transient target drops preserve the short-lived reachability cache; lifecycle/world resets still clear it.
- Combat navigation telemetry sampling is throttled.
- Combat debug text is refreshed only while the debug dashboard is enabled and at a bounded cadence.
- Repeated scoreboard/chat formatting regex work was replaced with allocation-light text normalization helpers.
- HUD and debug dashboard strings are cached at bounded refresh rates rather than rebuilt every frame.
- Path rendering limits completed/forward nodes, culls distant segments, merges redundant straight nodes, and precomputes ring trigonometry.
- `Path.getNodes()` reuses one immutable view instead of allocating a wrapper on each call.

## Architecture / configuration cleanup
- Wired `PitMasterDecisionEngine` and `PitExecutionOrchestrator` into the live BotController runtime instead of leaving them display-only.
- Removed the unused legacy CombatPhase/CombatContext escape/chaser/bow layer.
- Removed unused legacy navigation/cache/stuck helper classes that had no live callers.
- Removed obsolete, unconsumed configuration keys; every remaining `WanderBotSettings` field is referenced by live source.
- Repath interval, steering lookahead, combat range, crowd threshold, combat/navigation toggles and megastreak strategy are connected to runtime behavior.
- Removed random sprint/micro-pause settings and dead helper methods rather than introducing unstable movement variation.
- Removed stale root backup ZIPs/reports and destructive/manual maintenance workflows from this distributable copy.

## Validation
- Myau ownership state-machine test passes through OFF sync, target registration, ON request, target departure and OFF request/retry behavior.
- Source tree has no references to the removed legacy classes/settings or to WanderBot attack-key control.
- Every `WanderBotSettings.*` reference resolves to a declared remaining field.
- `javac` parser/type-pattern scan reports no Java syntax errors, incompatible-type errors, constructor mismatch errors or internal method-arity errors. The remaining raw javac failures are expected missing Minecraft/Forge symbols because those dependencies are not installed in this container.
- A full ForgeGradle dependency-aware build still needs to run in GitHub Actions.

## Scope note
These changes are about deterministic ownership, ordinary coherent movement input, correctness and performance. They do not implement anti-cheat bypass or detection-evasion logic.
