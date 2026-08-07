package com.webtvshell.app.config;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Stores the editable TV configuration without requiring a database. */
public final class ConfigRepository {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final int MAX_CONFIG_BYTES = 512 * 1024;

    private static final int MAX_HISTORY = 10;
    private static final int MAX_ITEMS_PER_TYPE = 1000;
    private static final int MAX_HOSTS_PER_PROFILE = 64;
    private static final int MAX_REGEX_LENGTH = 1024;
    private static final int MAX_SCRIPT_BYTES = 96 * 1024;
    private static final int MAX_TOTAL_SCRIPT_BYTES = 256 * 1024;

    private final Context context;
    private final File directory;
    private final File historyDirectory;
    private final File factoryFile;
    private final File activeFile;
    private final File previousFile;
    private final File draftFile;

    public ConfigRepository(Context context) throws IOException {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "config");
        historyDirectory = new File(directory, "history");
        factoryFile = new File(directory, "factory.json");
        activeFile = new File(directory, "active.json");
        previousFile = new File(directory, "previous.json");
        draftFile = new File(directory, "draft.json");
        initialize();
    }

    public synchronized String getActiveJson() throws IOException {
        return readRequired(activeFile);
    }

    public synchronized String getDraftJson() throws IOException {
        return readRequired(draftFile);
    }

    public synchronized int getActiveVersion() throws IOException {
        return parseObject(getActiveJson()).optInt("configVersion", 1);
    }

    public synchronized void saveDraft(String json) throws IOException {
        ensureSize(json);
        parseObject(json);
        writeAtomic(draftFile, normalize(json));
    }

    public synchronized ValidationResult validateDraft() throws IOException {
        return validate(getDraftJson());
    }

    public ValidationResult validate(String json) {
        List<String> errors = new ArrayList<>();
        if (json == null) {
            errors.add("配置不能为空");
            return new ValidationResult(errors);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            errors.add("配置超过 " + MAX_CONFIG_BYTES + " 字节上限");
            return new ValidationResult(errors);
        }

        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            errors.add("JSON 格式错误: " + e.getMessage());
            return new ValidationResult(errors);
        }

        if (root.optInt("schemaVersion", -1) != SUPPORTED_SCHEMA_VERSION) {
            errors.add("schemaVersion 必须为 " + SUPPORTED_SCHEMA_VERSION);
        }

        JSONArray groups = array(root, "groups", errors);
        JSONArray profiles = array(root, "profiles", errors);
        JSONArray scripts = array(root, "scripts", errors);
        JSONArray channels = array(root, "channels", errors);
        checkCount("groups", groups, errors);
        checkCount("profiles", profiles, errors);
        checkCount("scripts", scripts, errors);
        checkCount("channels", channels, errors);

        Set<String> groupIds = collectIds("groups", groups, errors);
        Set<String> profileIds = collectIds("profiles", profiles, errors);
        Set<String> scriptIds = collectIds("scripts", scripts, errors);
        collectIds("channels", channels, errors);
        validateScripts(scripts, errors);
        validateProfiles(profiles, scriptIds, errors);
        validateChannels(channels, groupIds, profileIds, errors);
        return new ValidationResult(errors);
    }

    public synchronized JSONObject applyDraft() throws IOException {
        String draft = getDraftJson();
        ValidationResult result = validate(draft);
        if (!result.isValid()) {
            throw new IllegalArgumentException(result.toJson().toString());
        }

        JSONObject next = parseObject(draft);
        int version = nextVersion();
        try {
            next.put("configVersion", version);
            next.put("updatedAt", isoNow());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        final String nextJson;
        try {
            nextJson = next.toString(2);
        } catch (JSONException e) {
            throw new IOException("无法序列化配置", e);
        }
        String current = getActiveJson();
        writeAtomic(previousFile, current);
        writeAtomic(activeFile, nextJson);
        writeAtomic(draftFile, nextJson);
        writeHistory(nextJson, version);
        pruneHistory();
        return versionInfo(next);
    }

    public synchronized JSONObject rollback(String versionValue) throws IOException {
        final int version;
        try {
            version = Integer.parseInt(versionValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效版本: " + versionValue);
        }
        File snapshot = historyFile(version);
        if (!snapshot.isFile()) {
            throw new IllegalArgumentException("历史版本不存在: " + version);
        }
        String json = readRequired(snapshot);
        ValidationResult validation = validate(json);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("历史配置已损坏: " + validation.toJson());
        }
        // A rollback is a new immutable version, so old history remains addressable.
        writeAtomic(draftFile, json);
        JSONObject applied = applyDraft();
        try {
            applied.put("rolledBackFrom", version);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return applied;
    }

    public synchronized JSONArray getHistory() throws IOException {
        JSONArray result = new JSONArray();
        for (File file : historyFilesNewestFirst()) {
            try {
                JSONObject config = parseObject(readRequired(file));
                JSONObject item = versionInfo(config);
                item.put("bytes", file.length());
                result.put(item);
            } catch (JSONException | IllegalArgumentException ignored) {
                // A corrupt snapshot is intentionally omitted but never activated.
            }
        }
        return result;
    }

    public synchronized String exportActive() throws IOException {
        return getActiveJson();
    }

    public synchronized ValidationResult importDraft(String json) throws IOException {
        ValidationResult result = validate(json);
        if (result.isValid()) {
            saveDraft(json);
        }
        return result;
    }

    public synchronized JSONObject matchTest(String urlValue) throws IOException {
        return matchTest(urlValue, getDraftJson());
    }

    public synchronized JSONObject matchTest(String urlValue, String configJson) throws IOException {
        JSONObject response = new JSONObject();
        try {
            URI uri = new URI(urlValue);
            String host = normalizeHost(uri.getHost());
            if (host.isEmpty() || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("只支持包含域名的 http/https URL");
            }
            ValidationResult validation = validate(configJson);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("草稿配置校验失败");
            }
            JSONArray profiles = parseObject(configJson).optJSONArray("profiles");
            JSONObject best = null;
            int bestScore = Integer.MIN_VALUE;
            if (profiles != null) {
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject profile = profiles.optJSONObject(i);
                    int score = matchScore(profile, host, uri.getRawPath());
                    if (score > bestScore) {
                        bestScore = score;
                        best = profile;
                    }
                }
            }
            response.put("url", urlValue);
            response.put("host", host);
            response.put("matched", best != null && bestScore >= 0);
            if (best != null && bestScore >= 0) {
                response.put("profileId", best.optString("id"));
                response.put("score", bestScore);
            }
        } catch (URISyntaxException | JSONException e) {
            throw new IllegalArgumentException("URL 格式错误: " + e.getMessage());
        }
        return response;
    }

    private void initialize() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建配置目录: " + directory);
        }
        if (!historyDirectory.exists() && !historyDirectory.mkdirs()) {
            throw new IOException("无法创建历史目录: " + historyDirectory);
        }
        if (!factoryFile.isFile()) {
            try (InputStream input = context.getAssets().open("default_config.json")) {
                writeAtomic(factoryFile, readStream(input, MAX_CONFIG_BYTES));
            }
        }
        String factory = readRequired(factoryFile);
        ValidationResult factoryResult = validate(factory);
        if (!factoryResult.isValid()) {
            throw new IOException("内置配置无效: " + factoryResult.toJson());
        }

        if (!isValidFile(activeFile)) {
            if (isValidFile(previousFile)) {
                writeAtomic(activeFile, readRequired(previousFile));
            } else {
                writeAtomic(activeFile, factory);
            }
        }
        if (!isValidFile(previousFile)) {
            writeAtomic(previousFile, getActiveJson());
        }
        if (!isValidFile(draftFile)) {
            writeAtomic(draftFile, getActiveJson());
        }
        int activeVersion = parseObject(getActiveJson()).optInt("configVersion", 1);
        if (!historyFile(activeVersion).isFile()) {
            writeHistory(getActiveJson(), activeVersion);
        }
        pruneHistory();
    }

    private boolean isValidFile(File file) {
        if (!file.isFile() || file.length() > MAX_CONFIG_BYTES) {
            return false;
        }
        try {
            return validate(readRequired(file)).isValid();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static JSONArray array(JSONObject root, String key, List<String> errors) {
        Object value = root.opt(key);
        if (!(value instanceof JSONArray)) {
            errors.add(key + " 必须是数组");
            return new JSONArray();
        }
        return (JSONArray) value;
    }

    private static void checkCount(String name, JSONArray items, List<String> errors) {
        if (items.length() > MAX_ITEMS_PER_TYPE) {
            errors.add(name + " 数量超过 " + MAX_ITEMS_PER_TYPE);
        }
    }

    private static Set<String> collectIds(String name, JSONArray items, List<String> errors) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                errors.add(name + "[" + i + "] 必须是对象");
                continue;
            }
            String id = item.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                errors.add(name + "[" + i + "].id 格式无效");
            } else if (!ids.add(id)) {
                errors.add(name + " 中存在重复 id: " + id);
            }
        }
        return ids;
    }

    private static void validateScripts(JSONArray scripts, List<String> errors) {
        int totalBytes = 0;
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject script = scripts.optJSONObject(i);
            if (script == null) continue;
            String body = script.optString("body", "");
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;
            if (bytes > MAX_SCRIPT_BYTES) {
                errors.add("scripts[" + i + "].body 超过 " + MAX_SCRIPT_BYTES + " 字节");
            }
            JSONArray hosts = script.optJSONArray("hosts");
            if (hosts != null) {
                if (hosts.length() > MAX_HOSTS_PER_PROFILE) {
                    errors.add("scripts[" + i + "].hosts 数量超过 " + MAX_HOSTS_PER_PROFILE);
                }
                for (int h = 0; h < hosts.length(); h++) {
                    String host = normalizeHost(hosts.optString(h));
                    if (!"*".equals(host) && !isValidHost(host)) {
                        errors.add("scripts[" + i + "].hosts[" + h + "] 域名无效");
                    }
                }
            }
            int timeout = script.optInt("timeoutMs", 5000);
            if (timeout < 0 || timeout > 60_000) {
                errors.add("scripts[" + i + "].timeoutMs 必须在 0 到 60000 之间");
            }
        }
        if (totalBytes > MAX_TOTAL_SCRIPT_BYTES) {
            errors.add("脚本总大小超过 " + MAX_TOTAL_SCRIPT_BYTES + " 字节");
        }
    }

    private static void validateProfiles(JSONArray profiles, Set<String> scriptIds,
                                         List<String> errors) {
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null) continue;
            JSONObject match = profile.optJSONObject("match");
            if (match == null) {
                errors.add("profiles[" + i + "].match 必须是对象");
                continue;
            }
            JSONArray hosts = match.optJSONArray("hosts");
            if (hosts == null || hosts.length() == 0) {
                errors.add("profiles[" + i + "].match.hosts 至少包含一个域名");
            } else if (hosts.length() > MAX_HOSTS_PER_PROFILE) {
                errors.add("profiles[" + i + "].match.hosts 数量超过 " + MAX_HOSTS_PER_PROFILE);
            } else {
                for (int h = 0; h < hosts.length(); h++) {
                    String host = normalizeHost(hosts.optString(h));
                    if (!isValidHost(host)) {
                        errors.add("profiles[" + i + "].match.hosts[" + h + "] 域名无效");
                    }
                }
            }
            String regex = match.optString("pathRegex", "");
            if (regex.length() > MAX_REGEX_LENGTH) {
                errors.add("profiles[" + i + "].match.pathRegex 过长");
            } else if (!regex.isEmpty()) {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    errors.add("profiles[" + i + "].match.pathRegex 无效: " + e.getDescription());
                }
            }
            JSONArray refs = profile.optJSONArray("scriptRefs");
            if (refs != null) {
                for (int r = 0; r < refs.length(); r++) {
                    String ref = refs.optString(r, "");
                    if (!scriptIds.contains(ref)) {
                        errors.add("profiles[" + i + "].scriptRefs 引用了不存在的脚本: " + ref);
                    }
                }
            }
            JSONArray allowlist = profile.optJSONArray("navigationAllowlist");
            if (allowlist != null) {
                if (allowlist.length() > MAX_HOSTS_PER_PROFILE) {
                    errors.add("profiles[" + i + "].navigationAllowlist 数量超过 "
                            + MAX_HOSTS_PER_PROFILE);
                }
                for (int a = 0; a < allowlist.length(); a++) {
                    String host = normalizeHost(allowlist.optString(a));
                    if (!isValidHost(host)) {
                        errors.add("profiles[" + i + "].navigationAllowlist[" + a + "] 域名无效");
                    }
                }
            }
            validateProfileSelectors(profile, i, errors);
        }
    }

    private static void validateProfileSelectors(JSONObject profile, int index, List<String> errors) {
        JSONObject automation = profile.optJSONObject("automation");
        if (automation != null) {
            validateTextLimit("profiles[" + index + "].automation.videoSelector",
                    automation.optString("videoSelector", ""), 2048, errors);
            validateTextLimit("profiles[" + index + "].automation.playButtonSelector",
                    automation.optString("playButtonSelector", ""), 2048, errors);
            JSONArray hidden = automation.optJSONArray("hideSelectors");
            if (hidden != null) {
                if (hidden.length() > 128) {
                    errors.add("profiles[" + index + "].automation.hideSelectors 数量超过 128");
                }
                for (int h = 0; h < hidden.length(); h++) {
                    validateTextLimit("profiles[" + index + "].automation.hideSelectors[" + h + "]",
                            hidden.optString(h, ""), 2048, errors);
                }
            }
        }
        JSONObject fullscreen = profile.optJSONObject("fullscreen");
        JSONArray strategies = fullscreen == null ? null : fullscreen.optJSONArray("strategies");
        if (strategies != null) {
            if (strategies.length() > 16) {
                errors.add("profiles[" + index + "].fullscreen.strategies 数量超过 16");
            }
            for (int s = 0; s < strategies.length(); s++) {
                JSONObject strategy = strategies.optJSONObject(s);
                if (strategy == null) {
                    errors.add("profiles[" + index + "].fullscreen.strategies[" + s + "] 必须是对象");
                    continue;
                }
                String type = strategy.optString("type", "");
                if (!("clickSelector".equals(type) || "requestVideoFullscreen".equals(type)
                        || "cssCinema".equals(type))) {
                    errors.add("profiles[" + index + "].fullscreen.strategies[" + s + "].type 无效");
                }
                if ("clickSelector".equals(type)) {
                    String selector = strategy.optString("selector", "");
                    if (selector.trim().isEmpty()) {
                        errors.add("clickSelector 策略必须提供 selector");
                    }
                    validateTextLimit("profiles[" + index + "].fullscreen.strategies[" + s + "].selector",
                            selector, 2048, errors);
                }
            }
        }
    }

    private static void validateTextLimit(String name, String value, int limit, List<String> errors) {
        if (value.length() > limit) errors.add(name + " 超过 " + limit + " 字符");
    }

    private static void validateChannels(JSONArray channels, Set<String> groupIds,
                                         Set<String> profileIds, List<String> errors) {
        Set<String> sourceIds = new HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null) continue;
            String groupId = channel.optString("groupId", "");
            if (!groupId.isEmpty() && !groupIds.contains(groupId)) {
                errors.add("channels[" + i + "].groupId 不存在: " + groupId);
            }
            JSONArray sources = channel.optJSONArray("sources");
            if (sources == null || sources.length() == 0) {
                errors.add("channels[" + i + "].sources 至少包含一个站源");
                continue;
            }
            for (int s = 0; s < sources.length(); s++) {
                JSONObject source = sources.optJSONObject(s);
                String prefix = "channels[" + i + "].sources[" + s + "]";
                if (source == null) {
                    errors.add(prefix + " 必须是对象");
                    continue;
                }
                String sourceId = source.optString("id", "").trim();
                if (!sourceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                    errors.add(prefix + ".id 格式无效");
                } else if (!sourceIds.add(sourceId)) {
                    errors.add("频道站源存在重复 id: " + sourceId);
                }
                String url = source.optString("url", "");
                if (!isHttpUrl(url)) {
                    errors.add(prefix + ".url 必须是有效的 http/https URL");
                }
                String profileId = source.optString("profileId", "");
                if (profileId.isEmpty()) {
                    errors.add(prefix + ".profileId 不能为空");
                } else if (!profileIds.contains(profileId)) {
                    errors.add(prefix + ".profileId 不存在: " + profileId);
                }
                int timeout = source.optInt("timeoutMs", 15000);
                if (timeout < 1000 || timeout > 120_000) {
                    errors.add(prefix + ".timeoutMs 必须在 1000 到 120000 之间");
                }
            }
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isValidHost(String host) {
        if (host.isEmpty() || host.length() > 253 || host.contains("/") || host.contains(":")) {
            return false;
        }
        return host.matches("(?i)(localhost|([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*)");
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static int matchScore(JSONObject profile, String host, String rawPath) {
        if (profile == null || profile.optBoolean("enabled", true) == false) return -1;
        JSONObject match = profile.optJSONObject("match");
        if (match == null) return -1;
        JSONArray hosts = match.optJSONArray("hosts");
        if (hosts == null) return -1;
        int hostScore = -1;
        boolean subdomains = match.optBoolean("includeSubdomains", false);
        for (int i = 0; i < hosts.length(); i++) {
            String expected = normalizeHost(hosts.optString(i));
            if (host.equals(expected)) {
                hostScore = Math.max(hostScore, 10000 + expected.length());
            } else if (subdomains && host.endsWith("." + expected)) {
                hostScore = Math.max(hostScore, 5000 + expected.length());
            }
        }
        if (hostScore < 0) return -1;
        String regex = match.optString("pathRegex", "");
        if (!regex.isEmpty() && !Pattern.compile(regex).matcher(rawPath == null ? "/" : rawPath).find()) {
            return -1;
        }
        return hostScore + profile.optInt("priority", 0) + (regex.isEmpty() ? 0 : regex.length());
    }

    private int nextVersion() throws IOException {
        int max = parseObject(getActiveJson()).optInt("configVersion", 0);
        for (File file : historyFilesNewestFirst()) {
            max = Math.max(max, versionFromFile(file));
        }
        return max + 1;
    }

    private void writeHistory(String json, int version) throws IOException {
        writeAtomic(historyFile(version), json);
    }

    private File historyFile(int version) {
        return new File(historyDirectory, String.format(Locale.US, "%010d.json", version));
    }

    private List<File> historyFilesNewestFirst() {
        File[] files = historyDirectory.listFiles((dir, name) -> name.matches("\\d{10}\\.json"));
        if (files == null) return Collections.emptyList();
        List<File> result = new ArrayList<>(Arrays.asList(files));
        Collections.sort(result,
                (left, right) -> Integer.compare(versionFromFile(right), versionFromFile(left)));
        return result;
    }

    private void pruneHistory() {
        List<File> files = historyFilesNewestFirst();
        for (int i = MAX_HISTORY; i < files.size(); i++) {
            // Deleting an old immutable snapshot does not affect active configuration.
            //noinspection ResultOfMethodCallIgnored
            files.get(i).delete();
        }
    }

    private static int versionFromFile(File file) {
        try {
            return Integer.parseInt(file.getName().substring(0, 10));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static JSONObject versionInfo(JSONObject config) {
        JSONObject result = new JSONObject();
        try {
            result.put("schemaVersion", config.optInt("schemaVersion", -1));
            result.put("configVersion", config.optInt("configVersion", -1));
            result.put("updatedAt", config.optString("updatedAt", ""));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    private static JSONObject parseObject(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("JSON 格式错误: " + e.getMessage(), e);
        }
    }

    private static String normalize(String json) {
        try {
            return new JSONObject(json).toString(2);
        } catch (JSONException e) {
            throw new IllegalArgumentException("JSON 格式错误: " + e.getMessage(), e);
        }
    }

    private static String readRequired(File file) throws IOException {
        if (!file.isFile()) throw new IOException("配置文件不存在: " + file.getName());
        try (InputStream input = new FileInputStream(file)) {
            return readStream(input, MAX_CONFIG_BYTES);
        }
    }

    private static String readStream(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("数据超过大小上限");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void ensureSize(String json) throws IOException {
        if (json == null) throw new IllegalArgumentException("配置不能为空");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IOException("配置超过大小上限");
        }
    }

    private static void writeAtomic(File target, String value) throws IOException {
        writeAtomic(target, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            // POSIX rename replaces the destination atomically and is available from API 21.
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("无法原子替换配置文件: " + target.getName(), e);
        } finally {
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
