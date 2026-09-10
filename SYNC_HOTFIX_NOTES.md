# Myau startup sync hotfix

This hotfix fixes a startup deadlock introduced by the strict Myau KillAura ACK handshake.

## Root cause

WanderBot sent `.t killaura off` at startup and refused to run `PitStartupSequence` until an exact Myau chat acknowledgement was observed. If Myau did not emit that exact line (or chat output was disabled/different), the bridge entered `SYNC FAULT`; because startup never ticked, lobby `/play pit` was never sent and Pit-spawn navigation never moved.

## Fix

- Startup no longer waits for a Myau OFF chat acknowledgement.
- `.t killaura off` is still sent when WanderBot starts/restarts.
- OFF requests return local rotation ownership immediately so navigation cannot deadlock.
- ON requests still yield rotation immediately to avoid two aim writers during combat.
- Missing Myau ACK is now diagnostic (`SYNC WARN`) instead of a movement/pathing blocker.
- Combat pathing no longer stops solely because the ACK state timed out.
- Myau chat state parsing accepts ON/OFF, ENABLED/DISABLED, and TRUE/FALSE variants while still requiring a `[Myau] ... KillAura ...` line.
