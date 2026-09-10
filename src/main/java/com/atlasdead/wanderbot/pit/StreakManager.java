package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.util.TextSanitizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level streak state for the private Pit recreation.
 *
 * The server is authoritative whenever a streak value is visible on the
 * scoreboard. Local counters are only a fallback between observations.
 */
public class StreakManager {
    private static final Pattern[] STREAK_PATTERNS = new Pattern[] {
            Pattern.compile("(?:kill\\s*streak|streak|ks)\\s*[:\\-]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:kill\\s*streak|streak)", Pattern.CASE_INSENSITIVE)
    };
    private static final Pattern[] KILL_PATTERNS = new Pattern[] {
            Pattern.compile("(?:kills?|eliminations?)\\s*[:\\-]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*(?:kills?|eliminations?)", Pattern.CASE_INSENSITIVE)
    };

    public enum Tier {
        NONE,
        BUILDING,      // 0-4
        ESTABLISHED,   // 5-9
        HOT,           // 10-19
        HIGH,          // 20-39
        EXTREME       // 40+
    }

    private int localStreak;
    private int killsSeen;
    private int serverStreak = -1;
    private int serverKills = -1;
    private int peakStreak;
    private long lastTargetId = Long.MIN_VALUE;
    private long lastKillMark;
    private long lastServerObservation;
    private long lastDeathMark;
    private final StreakControlManager control = new StreakControlManager();

    public void reset() {
        localStreak = 0;
        killsSeen = 0;
        serverStreak = -1;
        serverKills = -1;
        peakStreak = 0;
        lastTargetId = Long.MIN_VALUE;
        lastKillMark = 0L;
        lastServerObservation = 0L;
        lastDeathMark = 0L;
        control.reset();
    }

    public void observeTarget(long entityId) {
        if (entityId != lastTargetId) lastTargetId = entityId;
    }

    public void markKill(long now) {
        killsSeen++;
        localStreak++;
        peakStreak = Math.max(peakStreak, localStreak);
        lastKillMark = now;
    }

    public void resetLocalProgress() {
        localStreak = 0;
        killsSeen = 0;
        lastTargetId = Long.MIN_VALUE;
        lastKillMark = 0L;
    }

    public void markAuthoritativeReset() {
        localStreak = 0;
        if (serverStreak > 0) serverStreak = 0;
        lastTargetId = Long.MIN_VALUE;
    }

    public void markDeath() {
        localStreak = 0;
        serverStreak = 0;
        lastTargetId = Long.MIN_VALUE;
        lastDeathMark = System.currentTimeMillis();
    }

    /** Observe scoreboard text. Returns true when any authoritative value was updated. */
    public boolean observeScoreboard(List<String> lines, long now) {
        if (lines == null || lines.isEmpty()) return false;
        boolean changed = false;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = strip(raw);
            Integer streakValue = firstMatch(line, STREAK_PATTERNS);
            if (streakValue != null) {
                if (serverStreak != streakValue.intValue()) changed = true;
                serverStreak = Math.max(0, streakValue.intValue());
                localStreak = serverStreak;
                peakStreak = Math.max(peakStreak, serverStreak);
                lastServerObservation = now;
            }
            Integer killValue = firstMatch(line, KILL_PATTERNS);
            if (killValue != null) {
                if (serverKills != killValue.intValue()) changed = true;
                serverKills = Math.max(0, killValue.intValue());
                killsSeen = Math.max(killsSeen, serverKills);
                lastServerObservation = now;
            }
        }
        return changed;
    }

    /** True when a recent server-side value is available and should be trusted. */
    public boolean hasFreshServerObservation(long now) {
        return lastServerObservation > 0L && now - lastServerObservation <= 2500L;
    }

    public int getEffectiveStreak() {
        return serverStreak >= 0 ? serverStreak : localStreak;
    }

    public int getLocalStreak() { return localStreak; }
    public int getServerStreak() { return serverStreak; }
    public int getKillsSeen() { return killsSeen; }
    public int getServerKills() { return serverKills; }
    public int getPeakStreak() { return peakStreak; }
    public long getLastKillMark() { return lastKillMark; }
    public long getLastDeathMark() { return lastDeathMark; }

    public Tier getTier() {
        int streak = getEffectiveStreak();
        if (streak >= 40) return Tier.EXTREME;
        if (streak >= 20) return Tier.HIGH;
        if (streak >= 10) return Tier.HOT;
        if (streak >= 5) return Tier.ESTABLISHED;
        if (streak > 0) return Tier.BUILDING;
        return Tier.NONE;
    }

    /**
     * A 0..1 risk multiplier used by the high-level decision layer.
     * Higher streaks deliberately make reckless behavior more expensive.
     */
    public double getStreakRiskMultiplier() {
        switch (getTier()) {
            case EXTREME: return 2.2D;
            case HIGH: return 1.8D;
            case HOT: return 1.45D;
            case ESTABLISHED: return 1.20D;
            case BUILDING: return 1.0D;
            default: return 0.85D;
        }
    }

    /** Whether preserving this streak should override a marginal chase. */
    public StreakControlManager getControl() { return control; }

    /** Evaluate the selected killstreak slots at the current streak value. */
    public List<PitStreakCatalog.Definition> getTriggeredKillstreaks() {
        return control.evaluateTriggers(getEffectiveStreak());
    }

    public boolean selectedMegastreakReached() {
        return control.megastreakReached(getEffectiveStreak());
    }

    public boolean shouldProtectStreak() {
        return getEffectiveStreak() >= 5;
    }

    /** Whether the current streak is valuable enough that crowd pressure should strongly discourage a fight. */
    public boolean isHighValueStreak() {
        return getEffectiveStreak() >= 20;
    }

    public String summary() {
        return "streak=" + getEffectiveStreak()
                + ", tier=" + getTier()
                + ", kills=" + killsSeen
                + ", peak=" + peakStreak;
    }

    private String strip(String value) {
        return TextSanitizer.normalizedLower(value);
    }

    private Integer firstMatch(String line, Pattern[] patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                try {
                    return Integer.valueOf(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // Try the next pattern.
                }
            }
        }
        return null;
    }
}
