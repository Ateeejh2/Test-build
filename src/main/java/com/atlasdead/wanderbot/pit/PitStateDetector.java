package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.util.TextSanitizer;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Reads only locally-rendered scoreboard state; no network/API assumptions. */
public class PitStateDetector {
    public Result detect(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return Result.noData();
        }

        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return Result.noData();
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) return Result.noData();

        List<String> lines = new ArrayList<String>();
        Collection<Score> scores = board.getSortedScores(objective);
        for (Score score : scores) {
            if (score == null || score.getPlayerName() == null) continue;
            String line = score.getPlayerName();
            if (line.length() > 64) line = line.substring(0, 64);
            lines.add(line);
        }
        Collections.reverse(lines);

        boolean waiting = false;
        boolean warmup = false;
        for (String line : lines) {
            String clean = stripFormatting(line);
            if (clean.equalsIgnoreCase("Waiting Players...")) waiting = true;
            if (clean.contains("Selected Class:")) waiting = true;
            if (clean.contains("0 Kills 0 Assists")) warmup = true;
        }

        return new Result(true, waiting, warmup, lines);
    }

    private String stripFormatting(String value) {
        return TextSanitizer.stripFormatting(value).trim();
    }

    public static final class Result {
        public final boolean available;
        public final boolean waiting;
        public final boolean warmup;
        public final List<String> lines;

        private Result(boolean available, boolean waiting, boolean warmup, List<String> lines) {
            this.available = available;
            this.waiting = waiting;
            this.warmup = warmup;
            this.lines = lines;
        }

        public static Result noData() {
            return new Result(false, false, false, Collections.<String>emptyList());
        }
    }
}
