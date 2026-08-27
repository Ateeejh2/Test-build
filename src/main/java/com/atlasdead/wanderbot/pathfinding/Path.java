package com.atlasdead.wanderbot.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Path {
    private final List<PathNode> nodes;
    private int index;

    public Path(List<PathNode> nodes) {
        this.nodes = new ArrayList<PathNode>(nodes);
        this.index = 0;
    }

    public List<PathNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public boolean isFinished() {
        return index >= nodes.size();
    }

    public PathNode current() {
        return isFinished() ? null : nodes.get(index);
    }

    public void advance() {
        if (!isFinished()) index++;
    }

    public int getIndex() {
        return index;
    }
}
