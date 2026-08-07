package com.webtvshell.app.model;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AppConfig {
    public final List<ChannelGroup> groups;
    public final List<Channel> channels;
    public final List<SiteProfile> profiles;
    private final Map<String, SiteProfile> profilesById;

    private AppConfig(List<ChannelGroup> groups, List<Channel> channels,
                      List<SiteProfile> profiles) {
        this.groups = Collections.unmodifiableList(groups);
        this.channels = Collections.unmodifiableList(channels);
        this.profiles = Collections.unmodifiableList(profiles);
        profilesById = new HashMap<>();
        for (SiteProfile profile : profiles) profilesById.put(profile.id, profile);
    }

    public static AppConfig parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
        Map<String, String> scripts = parseScripts(root.optJSONArray("scripts"));
        List<ChannelGroup> groups = parseGroups(root.optJSONArray("groups"));
        List<Channel> channels = parseChannels(root.optJSONArray("channels"));
        List<SiteProfile> profiles = parseProfiles(root.optJSONArray("profiles"), scripts);

        Collections.sort(groups, (left, right) -> Integer.compare(left.order, right.order));
        Collections.sort(channels, (left, right) -> Integer.compare(left.order, right.order));
        Collections.sort(profiles, (a, b) -> Integer.compare(b.priority, a.priority));

        if (groups.isEmpty() && !channels.isEmpty()) {
            groups.add(new ChannelGroup("default", "频道", 0));
        }
        return new AppConfig(groups, channels, profiles);
    }

    public SiteProfile findProfile(ChannelSource source, String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (source != null && source.profileId != null && !source.profileId.isEmpty()) {
            SiteProfile explicit = profilesById.get(source.profileId);
            if (explicit != null && explicit.matches(host, path)) return explicit;
        }
        SiteProfile fallback = null;
        SiteProfile best = null;
        int bestScore = -1;
        for (SiteProfile profile : profiles) {
            if (("default".equals(profile.id) || "*".equals(profile.id)) && fallback == null) {
                fallback = profile;
            } else {
                int score = profile.matchScore(host, path);
                if (score > bestScore) {
                    bestScore = score;
                    best = profile;
                }
            }
        }
        return best == null ? fallback : best;
    }

    public List<Channel> channelsForGroup(String groupId) {
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            if (groupId.equals(channel.groupId)
                    || ("default".equals(groupId) && (channel.groupId == null || channel.groupId.isEmpty()))) {
                result.add(channel);
            }
        }
        return result;
    }

    private static List<ChannelGroup> parseGroups(JSONArray array) {
        List<ChannelGroup> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            String id = item.optString("id", "group-" + i);
            result.add(new ChannelGroup(id, item.optString("name", id), item.optInt("order", i)));
        }
        return result;
    }

    private static List<Channel> parseChannels(JSONArray array) {
        List<Channel> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            List<ChannelSource> sources = new ArrayList<>();
            JSONArray sourceArray = item.optJSONArray("sources");
            if (sourceArray != null) {
                for (int j = 0; j < sourceArray.length(); j++) {
                    Object raw = sourceArray.opt(j);
                    if (raw instanceof String) {
                        sources.add(new ChannelSource("source-" + j, (String) raw, "", j));
                    } else if (raw instanceof JSONObject) {
                        JSONObject source = (JSONObject) raw;
                        if (!source.optBoolean("enabled", true)) continue;
                        String url = source.optString("url", "").trim();
                        if (!url.isEmpty()) {
                            sources.add(new ChannelSource(source.optString("id", "source-" + j), url,
                                    source.optString("profileId", ""), source.optInt("priority", j)));
                        }
                    }
                }
            }
            Collections.sort(sources, (left, right) -> Integer.compare(right.priority, left.priority));
            if (sources.isEmpty()) continue;
            String id = item.optString("id", "channel-" + i);
            result.add(new Channel(id, item.optString("name", id),
                    item.optString("groupId", "default"), item.optInt("order", i), sources));
        }
        return result;
    }

    private static List<SiteProfile> parseProfiles(JSONArray array, Map<String, String> scripts) {
        List<SiteProfile> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            JSONObject match = item.optJSONObject("match");
            JSONObject webView = item.optJSONObject("webView");
            JSONObject automation = item.optJSONObject("automation");
            JSONObject fullscreen = item.optJSONObject("fullscreen");
            List<String> hosts = strings(match == null ? null : match.optJSONArray("hosts"));
            List<String> hide = strings(automation == null ? null : automation.optJSONArray("hideSelectors"));
            List<String> fullscreenSelectors = new ArrayList<>();
            if (fullscreen != null) {
                JSONArray strategies = fullscreen.optJSONArray("strategies");
                if (strategies != null) {
                    for (int j = 0; j < strategies.length(); j++) {
                        JSONObject strategy = strategies.optJSONObject(j);
                        if (strategy != null && "clickSelector".equals(strategy.optString("type"))) {
                            String selector = strategy.optString("selector", "");
                            if (!selector.isEmpty()) fullscreenSelectors.add(selector);
                        }
                    }
                }
                String directSelector = fullscreen.optString("selector", "");
                if (!directSelector.isEmpty()) fullscreenSelectors.add(directSelector);
            }
            StringBuilder customJs = new StringBuilder(item.optString("customJs", ""));
            if (automation != null && !automation.optString("customJs", "").isEmpty()) {
                customJs.append('\n').append(automation.optString("customJs"));
            }
            for (String ref : strings(item.optJSONArray("scriptRefs"))) {
                String body = scripts.get(ref);
                if (body != null) customJs.append('\n').append(body);
            }
            String ua = "";
            if (webView != null) {
                ua = webView.optString("customUserAgent", "").trim();
                if (ua.isEmpty()) {
                    ua = webView.optString("userAgent", webView.optString("userAgentProfile", ""));
                }
            }
            result.add(new SiteProfile(item.optString("id", "profile-" + i), item.optInt("priority", 0),
                    hosts, match != null && match.optBoolean("includeSubdomains", false),
                    match == null ? "" : match.optString("pathRegex", ""), ua,
                    webView != null && webView.optBoolean("thirdPartyCookies", false),
                    webView != null && webView.optBoolean("blockImages", false),
                    automation == null ? "video" : automation.optString("videoSelector", "video"),
                    automation == null ? "" : automation.optString("playButtonSelector", ""),
                    hide, fullscreenSelectors, customJs.toString()));
        }
        return result;
    }

    private static Map<String, String> parseScripts(JSONArray array) {
        Map<String, String> result = new HashMap<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optBoolean("enabled", true)
                    && !item.optString("id", "").isEmpty()) {
                result.put(item.optString("id"), item.optString("body", ""));
            }
        }
        return result;
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }
}
