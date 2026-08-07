package com.webtvshell.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SiteProfile {
    public final String id;
    public final int priority;
    public final List<String> hosts;
    public final boolean includeSubdomains;
    public final String pathRegex;
    public final String userAgent;
    public final boolean thirdPartyCookies;
    public final boolean blockImages;
    public final String videoSelector;
    public final String playButtonSelector;
    public final List<String> hideSelectors;
    public final List<String> fullscreenSelectors;
    public final String customJs;

    private final Pattern pathPattern;

    public SiteProfile(String id, int priority, List<String> hosts, boolean includeSubdomains,
                       String pathRegex, String userAgent, boolean thirdPartyCookies,
                       boolean blockImages, String videoSelector,
                       String playButtonSelector, List<String> hideSelectors,
                       List<String> fullscreenSelectors, String customJs) {
        this.id = id;
        this.priority = priority;
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        this.includeSubdomains = includeSubdomains;
        this.pathRegex = pathRegex;
        this.userAgent = userAgent;
        this.thirdPartyCookies = thirdPartyCookies;
        this.blockImages = blockImages;
        this.videoSelector = videoSelector;
        this.playButtonSelector = playButtonSelector;
        this.hideSelectors = Collections.unmodifiableList(new ArrayList<>(hideSelectors));
        this.fullscreenSelectors = Collections.unmodifiableList(new ArrayList<>(fullscreenSelectors));
        this.customJs = customJs;
        Pattern compiled = null;
        if (pathRegex != null && !pathRegex.isEmpty()) {
            try {
                compiled = Pattern.compile(pathRegex);
            } catch (PatternSyntaxException ignored) {
            }
        }
        pathPattern = compiled;
    }

    public boolean matches(String host, String path) {
        if (hosts.isEmpty()) return "default".equals(id) || "*".equals(id);
        boolean hostMatches = false;
        for (String candidate : hosts) {
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (host.equals(normalized)
                    || (includeSubdomains && host.endsWith("." + normalized))) {
                hostMatches = true;
                break;
            }
        }
        return hostMatches && (pathPattern == null || pathPattern.matcher(path).find());
    }

    public int matchScore(String host, String path) {
        if (hosts.isEmpty()) return ("default".equals(id) || "*".equals(id)) ? 0 : -1;
        int hostScore = -1;
        for (String candidate : hosts) {
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (host.equals(normalized)) {
                hostScore = Math.max(hostScore, 10000 + normalized.length());
            } else if (includeSubdomains && host.endsWith("." + normalized)) {
                hostScore = Math.max(hostScore, 5000 + normalized.length());
            }
        }
        if (hostScore < 0 || (pathPattern != null && !pathPattern.matcher(path).find())) return -1;
        return hostScore + (pathPattern == null ? 0 : 1000 + pathRegex.length()) + priority;
    }
}
