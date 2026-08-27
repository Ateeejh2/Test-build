package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Hypixel Pit rules layer. This class is intentionally policy-only: it does not
 * press keys and it does not hard-code a particular custom server implementation.
 *
 * The rules modeled here are the stable, externally observable Pit rules that
 * matter to autonomous navigation/decision making: protected spawn, major-event
 * state, streak/megastreak state, and event-time spawn handling.
 */
public final class PitRulesEngine {
    public enum MatchPhase { UNKNOWN, WAITING, WARMUP, ACTIVE, EVENT, POST_EVENT }
    public enum Megastreak { NONE, OVERDRIVE, BEASTMODE, HERMIT, HIGHLANDER, MAGNUM_OPUS, TO_THE_MOON, UBERSTREAK, UNKNOWN }

    public static final class State {
        public final MatchPhase phase;
        public final Megastreak megastreak;
        public final boolean spawnProtected;
        public final boolean majorEvent;
        public final boolean minorEvent;
        public final String eventName;
        public final int streak;
        public final boolean highValueStreak;
        public final boolean opusTriggerReached;
        public final long postEventSpawnDeadline;

        private State(MatchPhase phase, Megastreak megastreak, boolean spawnProtected,
                      boolean majorEvent, boolean minorEvent, String eventName, int streak,
                      boolean highValueStreak, boolean opusTriggerReached, long postEventSpawnDeadline) {
            this.phase = phase;
            this.megastreak = megastreak;
            this.spawnProtected = spawnProtected;
            this.majorEvent = majorEvent;
            this.minorEvent = minorEvent;
            this.eventName = eventName;
            this.streak = streak;
            this.highValueStreak = highValueStreak;
            this.opusTriggerReached = opusTriggerReached;
            this.postEventSpawnDeadline = postEventSpawnDeadline;
        }
    }

    private long majorEventStartedAt;
    private long postEventDeadline;
    private String lastMajorEvent;
    private Megastreak currentMegastreak = Megastreak.NONE;

    public void reset() {
        majorEventStartedAt = 0L;
        postEventDeadline = 0L;
        lastMajorEvent = null;
        currentMegastreak = Megastreak.NONE;
    }

    public State evaluate(EntityPlayerSP self,
                          PitStateDetector.Result score,
                          PitEventDetector.Result event,
                          PitZoneManager zones,
                          StreakManager streak) {
        if (self == null || score == null || !score.available) {
            return new State(MatchPhase.UNKNOWN, currentMegastreak, false,
                    false, false, null, streak == null ? 0 : streak.getEffectiveStreak(), false, false, postEventDeadline);
        }

        long now = System.currentTimeMillis();
        boolean protectedZone = zones != null && zones.isSelfProtected(self);
        int currentStreak = streak == null ? 0 : streak.getEffectiveStreak();
        boolean major = event != null && event.major;
        boolean minor = event != null && event.minor;
        String eventName = event == null ? null : event.eventName;

        if (major) {
            if (!equalsIgnoreCase(lastMajorEvent, eventName)) majorEventStartedAt = now;
            lastMajorEvent = eventName;
            postEventDeadline = 0L;
        } else if (lastMajorEvent != null && majorEventStartedAt > 0L) {
            // Major event just ended. The documented behavior gives players whose
            // high streak was paused a limited spawn-exit window.
            postEventDeadline = now + 30_000L;
            majorEventStartedAt = 0L;
            lastMajorEvent = null;
        }

        if (major) {
            return new State(MatchPhase.EVENT, currentMegastreak, protectedZone,
                    true, minor, eventName, currentStreak, currentStreak >= 20, currentStreak >= 50, postEventDeadline);
        }

        MatchPhase phase;
        if (score.waiting) phase = MatchPhase.WAITING;
        else if (score.warmup) phase = MatchPhase.WARMUP;
        else if (postEventDeadline > now && protectedZone) phase = MatchPhase.POST_EVENT;
        else phase = MatchPhase.ACTIVE;

        // Magnum Opus is triggered at 50 kills. We only expose this state here;
        // no self-elimination action is performed by the rules engine.
        boolean opusTrigger = currentStreak >= 50;
        currentMegastreak = detectMegastreak(score.lines, currentStreak);

        return new State(phase, currentMegastreak, protectedZone,
                false, minor, eventName, currentStreak, currentStreak >= 20, opusTrigger, postEventDeadline);
    }

    public boolean mayAcquireCombatTarget(State state) {
        if (state == null) return false;
        if (state.phase == MatchPhase.WAITING || state.phase == MatchPhase.WARMUP) return false;
        if (state.phase == MatchPhase.EVENT) return false;
        if (state.spawnProtected) return false;
        return state.phase == MatchPhase.ACTIVE;
    }

    public boolean shouldPreferStreakPreservation(State state) {
        if (state == null) return true;
        if (state.megastreak == Megastreak.MAGNUM_OPUS) return true;
        return state.highValueStreak || state.streak >= 20;
    }

    public Megastreak getCurrentMegastreak() { return currentMegastreak; }
    public long getPostEventDeadline() { return postEventDeadline; }

    private Megastreak detectMegastreak(List<String> lines, int streak) {
        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) continue;
                String line = raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase(Locale.ROOT);
                if (line.contains("magnum opus")) return Megastreak.MAGNUM_OPUS;
                if (line.contains("highlander")) return Megastreak.HIGHLANDER;
                if (line.contains("beastmode")) return Megastreak.BEASTMODE;
                if (line.contains("hermit")) return Megastreak.HERMIT;
                if (line.contains("overdrive")) return Megastreak.OVERDRIVE;
                if (line.contains("to the moon") || line.contains("super streaker")) return Megastreak.TO_THE_MOON;
                if (line.contains("uberstreak")) return Megastreak.UBERSTREAK;
            }
        }
        // The 50-kill threshold is also a useful observable fallback for the
        // Magnum Opus strategy layer, without assuming the scoreboard title.
        if (streak >= 50) return Megastreak.MAGNUM_OPUS;
        return Megastreak.NONE;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
