package com.atlasdead.wanderbot.pit;

/**
 * Explicit combat state machine.
 *
 * NO_TARGET → ACQUIRE_TARGET → APPROACH → AIM → ATTACK_READY → ATTACK → COOLDOWN → TARGET_REVALIDATE
 *     ↑                                                                                      │
 *     └──────────────────────────── (if target still valid) ─────────────────────────────────┘
 */
public enum CombatState {
    /** No valid target; waiting for TargetSelector to find one. */
    NO_TARGET,
    /** Target found; snapshot being created. */
    ACQUIRE_TARGET,
    /** Closing distance to preferred melee range. */
    APPROACH,
    /** In range; rotation converging on target. */
    AIM,
    /** Rotation aligned and attack conditions met; ready to fire next tick. */
    ATTACK_READY,
    /** Attack packet sent this tick. */
    ATTACK,
    /** Waiting for attack cooldown (vanilla + anti-cheat compliance). */
    COOLDOWN,
    /** Verifying target is still alive/valid after cooldown. */
    TARGET_REVALIDATE;

    /** Should movement be driven by combat in this state? */
    public boolean drivesMovement() {
        return this == APPROACH || this == AIM || this == ATTACK_READY
                || this == ATTACK || this == COOLDOWN;
    }

    /** Should attack packets be considered in this state? */
    public boolean canAttack() {
        return this == ATTACK_READY;
    }

    /** Should the combat layer release movement control? */
    public boolean yieldsMovement() {
        return this == NO_TARGET || this == ACQUIRE_TARGET;
    }
}
