package com.webtvshell.app.model;

public final class ChannelGroup {
    public final String id;
    public final String name;
    public final int order;

    public ChannelGroup(String id, String name, int order) {
        this.id = id;
        this.name = name;
        this.order = order;
    }
}
