package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.IChatComponent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Event-oriented Pit progress tracker for a private Pit recreation.
 *
 * Sources are intentionally layered:
 *  1) scoreboard streak/kills (preferred when visible),
 *  2) local player death/respawn observation,
 *  3) optional chat cues (useful when the private server exposes kill/death messages).
 *
 * The tracker never invents a kill. Local streak is only incremented from explicit
 * kill cues supplied by the private server or by an external hook.
 */
public class PitProgressTracker {
    private static final Pattern KILL_CUE = Pattern.compile(
            "(?:you|your)\\s+(?:killed|eliminated)|\\bkilled\\b|\\beliminated\\b|\\+\\s*1\\s*(?:kill|streak)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEATH_CUE = Pattern.compile(
            "(?:you|your)\\s+(?:died|were killed|was killed|got killed|eliminated)|\\byou died\\b",
            Pattern.CASE_INSENSITIVE);

    private final StreakManager streak;
    private long lastKillCue;
    private long lastDeathCue;
    private boolean deathLatched;
    private boolean activeMatch;
    private boolean sawActiveState;
    private int previousServerStreak = -1;
    private int previousServerKills = -1;

    public PitProgressTracker(StreakManager streak) {
        this.streak = streak;
    }

    public void reset() {
        lastKillCue = 0L;
        lastDeathCue = 0L;
        deathLatched = false;
        activeMatch = false;
        sawActiveState = false;
        previousServerStreak = -1;
        previousServerKills = -1;
    }

    public void observeState(boolean waiting, boolean warmup, boolean protectedZone) {
        boolean nowActive = !waiting && !warmup && !protectedZone;
        if (nowActive && !activeMatch) {
            activeMatch = true;
            sawActiveState = true;
            // A new active match should not inherit a stale local counter if the
            // scoreboard is not exposing authoritative streak information yet.
            if (streak.getServerStreak() < 0) streak.resetLocalProgress();
        } else if (!nowActive && activeMatch) {
            activeMatch = false;
        }
    }

    public void observeScoreboard(boolean changed) {
        int serverStreak = streak.getServerStreak();
        int serverKills = streak.getServerKills();

        if (serverStreak >= 0) {
            if (previousServerStreak >= 0 && serverStreak < previousServerStreak) {
                // A visible authoritative decrease is more useful than guessing
                // from combat entities. Treat it as a streak reset observation.
                streak.markAuthoritativeReset();
            }
            previousServerStreak = serverStreak;
        }
        if (serverKills >= 0) previousServerKills = serverKills;
    }

    public void observePlayer(Minecraft mc) {
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        if (player == null) return;

        if (player.isDead || player.getHealth() <= 0.0F) {
            if (!deathLatched) {
                deathLatched = true;
                markDeath(System.currentTimeMillis());
            }
        } else if (deathLatched) {
            deathLatched = false;
        }
    }

    /** Returns true when a chat line was interpreted as an explicit kill cue. */
    public boolean observeChat(IChatComponent message) {
        if (message == null) return false;
        String text = message.getUnformattedText();
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (DEATH_CUE.matcher(lower).find()) {
            markDeath(now);
            return false;
        }
        if (KILL_CUE.matcher(lower).find()) {
            // Avoid duplicate counting when the same server message is delivered
            // twice by a client-side hook.
            if (now - lastKillCue < 600L) return false;
            lastKillCue = now;
            streak.markKill(now);
            return true;
        }
        return false;
    }

    /** External adapter hook for the user's private server plugin. */
    public void markKillFromServer(long now) {
        if (now - lastKillCue < 300L) return;
        lastKillCue = now;
        streak.markKill(now);
    }

    public void markDeath(long now) {
        if (now - lastDeathCue < 1000L) return;
        lastDeathCue = now;
        streak.markDeath();
        activeMatch = false;
        previousServerStreak = 0;
    }

    public boolean isActiveMatch() { return activeMatch; }
    public boolean hasSeenActiveMatch() { return sawActiveState; }
}
