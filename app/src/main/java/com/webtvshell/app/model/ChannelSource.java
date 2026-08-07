package com.webtvshell.app.model;

public final class ChannelSource {
    public final String id;
    public final String url;
    public final String profileId;
    public final int priority;

    public ChannelSource(String id, String url, String profileId, int priority) {
        this.id = id;
        this.url = url;
        this.profileId = profileId;
        this.priority = priority;
    }
}
