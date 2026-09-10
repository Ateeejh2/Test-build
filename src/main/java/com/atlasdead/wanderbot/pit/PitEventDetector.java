package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.util.TextSanitizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads visible scoreboard text for Pit event states without assuming server internals. */
public class PitEventDetector {
    private static final Set<String> MAJOR_EVENTS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "rage pit", "pizza", "robbery", "raffle", "beast", "squads", "blockhead", "spire", "team deathmatch"
    )));

    private static final Set<String> MINOR_EVENTS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "auction", "everyone gets a bounty", "2x rewards", "king of the ladder", "king of the hill",
            "giant cake", "dragon egg", "quick maths", "care package"
    )));

    public Result detect(List<String> scoreboardLines) {
        if (scoreboardLines == null || scoreboardLines.isEmpty()) return Result.none();

        for (String raw : scoreboardLines) {
            if (raw == null) continue;
            String line = TextSanitizer.normalizedLower(raw);
            for (String event : MAJOR_EVENTS) {
                if (line.contains(event)) return new Result(true, true, false, event);
            }
            for (String event : MINOR_EVENTS) {
                if (line.contains(event)) return new Result(true, false, true, event);
            }
        }
        return new Result(true, false, false, null);
    }

    public static final class Result {
        public final boolean available;
        public final boolean major;
        public final boolean minor;
        public final String eventName;

        private Result(boolean available, boolean major, boolean minor, String eventName) {
            this.available = available;
            this.major = major;
            this.minor = minor;
            this.eventName = eventName;
        }

        public static Result none() { return new Result(true, false, false, null); }
    }
}
