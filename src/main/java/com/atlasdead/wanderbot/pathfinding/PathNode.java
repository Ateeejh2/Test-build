package com.atlasdead.wanderbot.pathfinding;

public class PathNode {
    public final int x;
    public final int y;
    public final int z;
    public double gCost = Double.POSITIVE_INFINITY;
    public double hCost = 0.0D;
    public PathNode parent;

    public PathNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double fCost() {
        return gCost + hCost;
    }

    public String key() {
        return x + ":" + y + ":" + z;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PathNode)) return false;
        PathNode n = (PathNode) other;
        return x == n.x && y == n.y && z == n.z;
    }

    @Override
    public int hashCode() {
        int h = x * 73428767;
        h ^= y * 912931;
        h ^= z * 19349663;
        return h;
    }
}
