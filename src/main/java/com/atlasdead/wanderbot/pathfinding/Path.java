package com.atlasdead.wanderbot.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Path {
    private final List<PathNode> nodes;
    private final List<PathNode> readOnlyNodes;
    private int index;

    public Path(List<PathNode> nodes) {
        this.nodes = new ArrayList<PathNode>(nodes);
        this.readOnlyNodes = Collections.unmodifiableList(this.nodes);
        this.index = 0;
    }

    /** Returns a cached read-only view; no wrapper is allocated per render/tick. */
    public List<PathNode> getNodes() {
        return readOnlyNodes;
    }

    public int size() {
        return nodes.size();
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
