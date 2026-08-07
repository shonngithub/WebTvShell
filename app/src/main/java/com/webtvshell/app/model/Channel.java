package com.webtvshell.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Channel {
    public final String id;
    public final String name;
    public final String groupId;
    public final int order;
    public final List<ChannelSource> sources;
    private int sourceIndex;

    public Channel(String id, String name, String groupId, int order, List<ChannelSource> sources) {
        this.id = id;
        this.name = name;
        this.groupId = groupId;
        this.order = order;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public ChannelSource currentSource() {
        if (sources.isEmpty()) return null;
        if (sourceIndex >= sources.size()) sourceIndex = 0;
        return sources.get(sourceIndex);
    }

    public ChannelSource nextSource() {
        if (sources.isEmpty()) return null;
        sourceIndex = (sourceIndex + 1) % sources.size();
        return sources.get(sourceIndex);
    }
}
