package com.atlasdead.wanderbot.navigation;

import net.minecraft.util.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class NavigationMemory {
    private final Map<String, Integer> failures = new HashMap<String, Integer>();
    private BlockPos lastFailure;

    public void recordFailure(BlockPos pos) {
        if (pos == null) return;
        String key = pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        Integer old = failures.get(key);
        failures.put(key, old == null ? 1 : Math.min(8, old + 1));
        lastFailure = pos;
    }

    public double penalty(BlockPos pos) {
        if (pos == null) return 0.0D;
        String key = pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        Integer count = failures.get(key);
        return count == null ? 0.0D : count * 1.8D;
    }

    public BlockPos getLastFailure() {
        return lastFailure;
    }
}
