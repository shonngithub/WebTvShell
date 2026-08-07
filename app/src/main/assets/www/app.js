(function () {
  "use strict";

  var state = {
    status: {},
    active: null,
    draft: emptyConfig(),
    history: [],
    baseline: "",
    dirty: false
  };

  var $ = function (selector, root) { return (root || document).querySelector(selector); };
  var $$ = function (selector, root) { return Array.prototype.slice.call((root || document).querySelectorAll(selector)); };

  function emptyConfig() {
    return { schemaVersion: 1, configVersion: 1, updatedAt: new Date().toISOString(), groups: [], channels: [], profiles: [], scripts: [] };
  }

  function normalizeConfig(input) {
    var config = input && typeof input === "object" ? input : emptyConfig();
    config.schemaVersion = Number(config.schemaVersion || 1);
    config.configVersion = Number(config.configVersion || 1);
    config.updatedAt = config.updatedAt || new Date().toISOString();
    ["groups", "channels", "profiles", "scripts"].forEach(function (key) {
      if (!Array.isArray(config[key])) config[key] = [];
    });
    config.groups.forEach(function (group) {
      if (typeof group.enabled !== "boolean") group.enabled = true;
      group.order = numberOr(group.order, 0);
    });
    config.channels.forEach(function (channel) {
      if (typeof channel.enabled !== "boolean") channel.enabled = true;
      channel.order = numberOr(channel.order, 0);
      if (!Array.isArray(channel.sources)) channel.sources = [];
      channel.sources.forEach(function (source) {
        if (typeof source.enabled !== "boolean") source.enabled = true;
        source.priority = numberOr(source.priority, 100);
        source.timeoutMs = numberOr(source.timeoutMs, 15000);
      });
    });
    config.profiles.forEach(function (profile) {
      if (typeof profile.enabled !== "boolean") profile.enabled = true;
      profile.priority = numberOr(profile.priority, 100);
      profile.match = profile.match || { hosts: [], includeSubdomains: false, pathRegex: "" };
      if (!Array.isArray(profile.match.hosts)) profile.match.hosts = [];
      if (!Array.isArray(profile.navigationAllowlist)) profile.navigationAllowlist = [];
      profile.webView = profile.webView || {};
      profile.automation = profile.automation || {};
      if (!Array.isArray(profile.automation.hideSelectors)) profile.automation.hideSelectors = [];
      profile.fullscreen = profile.fullscreen || { strategies: [] };
      if (!Array.isArray(profile.fullscreen.strategies)) profile.fullscreen.strategies = [];
      if (!Array.isArray(profile.scriptRefs)) profile.scriptRefs = [];
    });
    config.scripts.forEach(function (script) {
      if (typeof script.enabled !== "boolean") script.enabled = true;
      if (!Array.isArray(script.hosts)) script.hosts = [];
      script.timeoutMs = numberOr(script.timeoutMs, 5000);
      script.version = numberOr(script.version, 1);
    });
    return config;
  }

  function numberOr(value, fallback) {
    var number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function extract(payload, keys) {
    if (!payload || typeof payload !== "object") return payload;
    for (var i = 0; i < keys.length; i += 1) {
      if (payload[keys[i]] !== undefined) return payload[keys[i]];
    }
    return payload.data !== undefined ? payload.data : payload;
  }

  async function api(path, options) {
    var request = options || {};
    request.headers = Object.assign({ "Accept": "application/json" }, request.headers || {});
    if (request.body && !request.headers["Content-Type"]) request.headers["Content-Type"] = "application/json";
    var response = await fetch(path, request);
    var text = await response.text();
    var payload = null;
    if (text) {
      try { payload = JSON.parse(text); } catch (_error) { payload = text; }
    }
    if (!response.ok) {
      var message = payload && (payload.message || payload.error) ? (payload.message || payload.error) : (text || ("请求失败：HTTP " + response.status));
      throw new Error(typeof message === "string" ? message : JSON.stringify(message));
    }
    return payload;
  }

  function jsonRequest(method, body) {
    return { method: method, body: JSON.stringify(body) };
  }

  async function loadAll() {
    setConnection("正在连接盒子...", false);
    var results = await Promise.all([
      settle(api("/api/status")),
      settle(api("/api/config/active")),
      settle(api("/api/config/draft")),
      settle(api("/api/config/history"))
    ]);

    if (results[0].status === "fulfilled") state.status = extract(results[0].value, ["status"]) || {};
    if (results[1].status === "fulfilled") state.active = normalizeConfig(extract(results[1].value, ["active", "config"]));
    if (results[2].status === "fulfilled") state.draft = normalizeConfig(extract(results[2].value, ["draft", "config"]));
    else if (state.active) state.draft = deepCopy(state.active);
    if (results[3].status === "fulfilled") state.history = normalizeHistory(extract(results[3].value, ["history", "versions"]));

    var failures = results.filter(function (result) { return result.status === "rejected"; });
    setConnection(failures.length ? "部分数据读取失败" : "已连接到盒子", failures.length > 0);
    if (failures.length === results.length) toast("无法连接配置服务，请确认手机与盒子在同一局域网。", true);

    state.baseline = stableJson(state.draft);
    state.dirty = false;
    renderAll();
  }

  function settle(promise) {
    return promise.then(function (value) {
      return { status: "fulfilled", value: value };
    }, function (reason) {
      return { status: "rejected", reason: reason };
    });
  }

  function normalizeHistory(value) {
    if (Array.isArray(value)) return value;
    if (value && Array.isArray(value.items)) return value.items;
    return [];
  }

  function renderAll() {
    renderStatus();
    renderGroups();
    renderChannels();
    renderProfiles();
    renderScripts();
    renderHistory();
    syncJsonEditor();
    updateDirty(false);
  }

  function renderStatus() {
    var status = state.status || {};
    var active = state.active || {};
    var fields = [
      ["服务状态", status.running === false ? "未运行" : (status.state || "运行中")],
      ["盒子名称", status.deviceName || status.device || "-"],
      ["访问地址", status.address || status.url || status.ip || location.host],
      ["App 版本", status.appVersion || status.version || "-"],
      ["WebView 版本", status.webViewVersion || status.webviewVersion || "-"],
      ["配置版本", active.configVersion !== undefined ? active.configVersion : "-"]
    ];
    $("#status-grid").innerHTML = fields.map(function (field) {
      return "<div><dt>" + escapeHtml(field[0]) + "</dt><dd>" + escapeHtml(String(field[1])) + "</dd></div>";
    }).join("");
  }

  function renderGroups() {
    var groups = state.draft.groups.slice().sort(byOrder);
    $("#group-list").innerHTML = groups.length ? groups.map(function (group) {
      var index = state.draft.groups.indexOf(group);
      var count = state.draft.channels.filter(function (channel) { return channel.groupId === group.id; }).length;
      return entityHtml(group.name, group.id + " · " + count + " 个频道 · 分组排序 " + group.order, group.enabled, "group", index);
    }).join("") : emptyHtml("尚未创建分组");
  }

  function renderChannels() {
    var groups = state.draft.groups.slice().sort(byOrder);
    var knownGroupIds = new Set(groups.map(function (group) { return group.id; }));
    var sections = groups.map(function (group) {
      return channelGroupHtml(group.name, group.id, group.order, state.draft.channels.filter(function (channel) {
        return channel.groupId === group.id;
      }).sort(byOrder));
    });
    var ungrouped = state.draft.channels.filter(function (channel) {
      return !knownGroupIds.has(channel.groupId);
    }).sort(byOrder);
    if (ungrouped.length) sections.push(channelGroupHtml("未分组", "-", "-", ungrouped));
    $("#channel-list").innerHTML = state.draft.channels.length ? sections.join("") : emptyHtml("尚未创建频道");
  }

  function channelGroupHtml(groupName, groupId, groupOrder, channels) {
    var items = channels.length ? channels.map(function (channel) {
      var index = state.draft.channels.indexOf(channel);
      var enabledSources = channel.sources.filter(function (source) { return source.enabled !== false; }).length;
      var meta = enabledSources + "/" + channel.sources.length + " 个站源 · 组内排序 " + channel.order;
      return entityHtml(channel.name, meta, channel.enabled, "channel", index);
    }).join("") : emptyHtml("该分组尚无频道");
    return "<section class=\"channel-group-section\"><div class=\"channel-group-heading\"><div><strong>" + escapeHtml(groupName) + "</strong><span>" + escapeHtml(groupId) + " · 分组排序 " + escapeHtml(groupOrder) + "</span></div><span>" + channels.length + " 个频道</span></div><div class=\"channel-group-items\">" + items + "</div></section>";
  }

  function renderProfiles() {
    var profiles = state.draft.profiles.slice().sort(function (a, b) { return numberOr(b.priority, 0) - numberOr(a.priority, 0); });
    $("#profile-list").innerHTML = profiles.length ? profiles.map(function (profile) {
      var index = state.draft.profiles.indexOf(profile);
      var match = profile.match || {};
      var hosts = (match.hosts || []).join(", ") || "未设置域名";
      var strategyCount = profile.fullscreen && Array.isArray(profile.fullscreen.strategies) ? profile.fullscreen.strategies.length : 0;
      return entityHtml(profile.name || profile.id, hosts + " · 优先级 " + profile.priority + " · " + strategyCount + " 个全屏策略", profile.enabled, "profile", index);
    }).join("") : emptyHtml("尚未创建站点规则");
  }

  function renderScripts() {
    var scripts = state.draft.scripts;
    $("#script-list").innerHTML = scripts.length ? scripts.map(function (script, index) {
      var hosts = (script.hosts || []).join(", ") || "未限制域名";
      return entityHtml(script.name || script.id, hosts + " · " + (script.phase || "pageFinished") + " · 超时 " + script.timeoutMs + " ms", script.enabled, "script", index);
    }).join("") : emptyHtml("尚未添加高级脚本");
  }

  function entityHtml(title, meta, enabled, kind, index) {
    return "<article class=\"entity\"><div class=\"entity-main\"><div class=\"entity-title\"><span>" + escapeHtml(title) + "</span><span class=\"badge " + (enabled ? "" : "off") + "\">" + (enabled ? "启用" : "停用") + "</span></div><div class=\"entity-meta\">" + escapeHtml(meta) + "</div></div><div class=\"entity-actions\"><button class=\"secondary small\" type=\"button\" data-action=\"edit\" data-kind=\"" + kind + "\" data-index=\"" + index + "\">编辑</button><button class=\"danger small\" type=\"button\" data-action=\"delete\" data-kind=\"" + kind + "\" data-index=\"" + index + "\">删除</button></div></article>";
  }

  function emptyHtml(text) { return "<div class=\"empty\">" + escapeHtml(text) + "</div>"; }
  function byOrder(a, b) { return numberOr(a.order, 0) - numberOr(b.order, 0) || String(a.id).localeCompare(String(b.id)); }

  function renderHistory() {
    var target = $("#history-list");
    if (!state.history.length) {
      target.innerHTML = emptyHtml("暂无历史版本");
      return;
    }
    target.innerHTML = state.history.map(function (item) {
      var version = item.configVersion !== undefined ? item.configVersion : (item.version !== undefined ? item.version : item);
      var time = item.updatedAt || item.createdAt || "时间未知";
      return "<div class=\"history-item\"><div><strong>版本 " + escapeHtml(String(version)) + "</strong><span>" + escapeHtml(formatDate(time)) + "</span></div><button class=\"danger small\" type=\"button\" data-action=\"rollback\" data-version=\"" + escapeAttr(String(version)) + "\">回滚到此版本</button></div>";
    }).join("");
  }

  function openGroup(index) {
    var editing = index !== undefined;
    var group = editing ? deepCopy(state.draft.groups[index]) : { id: uniqueId("group", state.draft.groups), name: "", order: nextOrder(state.draft.groups), enabled: true };
    openModal(editing ? "编辑分组" : "新增分组", "group", index, "<div class=\"form-grid\">" +
      inputHtml("id", "分组 ID", group.id, "text", true) +
      inputHtml("name", "名称", group.name, "text", true) +
      inputHtml("order", "分组排序", group.order, "number") +
      checkHtml("enabled", "启用", group.enabled) +
      "</div>");
  }

  function openChannel(index) {
    var editing = index !== undefined;
    var orderedGroups = state.draft.groups.slice().sort(byOrder);
    var defaultGroupId = orderedGroups[0] ? orderedGroups[0].id : "";
    var channel = editing ? deepCopy(state.draft.channels[index]) : {
      id: uniqueId("channel", state.draft.channels), name: "", logo: "", groupId: defaultGroupId, order: nextChannelOrder(defaultGroupId), enabled: true, sources: []
    };
    var groups = orderedGroups.map(function (group) { return [group.id, group.name]; });
    var sourceRows = channel.sources.map(sourceRowHtml).join("");
    openModal(editing ? "编辑频道" : "新增频道", "channel", index, "<div class=\"form-grid\">" +
      inputHtml("id", "频道 ID", channel.id, "text", true) +
      inputHtml("name", "名称", channel.name, "text", true) +
      selectHtml("groupId", "分组", channel.groupId, groups, true) +
      inputHtml("order", "组内排序", channel.order, "number") +
      inputHtml("logo", "Logo URL（可选）", channel.logo || "", "url", false, "wide") +
      checkHtml("enabled", "启用频道", channel.enabled) +
      "<div class=\"subeditor\"><div class=\"subeditor-head\"><strong>站源</strong><button id=\"add-source-row\" class=\"secondary small\" type=\"button\">新增站源</button></div><div id=\"source-rows\">" + sourceRows + "</div></div>" +
      "</div>");
  }

  function sourceRowHtml(source) {
    source = source || { id: "source-" + Date.now(), url: "", profileId: "", priority: 100, timeoutMs: 15000, enabled: true };
    var profiles = [["", "自动匹配"]].concat(state.draft.profiles.map(function (profile) { return [profile.id, profile.name || profile.id]; }));
    return "<div class=\"subrow source-row\">" +
      inputHtml("sourceId", "站源 ID", source.id || "", "text", true) +
      inputHtml("url", "网页 URL", source.url || "", "url", true) +
      selectHtml("profileId", "指定规则", source.profileId || "", profiles) +
      "<button class=\"danger small remove-row\" type=\"button\">删除</button>" +
      inputHtml("priority", "优先级", numberOr(source.priority, 100), "number") +
      inputHtml("timeoutMs", "加载超时（毫秒）", numberOr(source.timeoutMs, 15000), "number") +
      checkHtml("sourceEnabled", "启用站源", source.enabled !== false) +
      "</div>";
  }

  function openProfile(index) {
    var editing = index !== undefined;
    var profile = editing ? deepCopy(state.draft.profiles[index]) : {
      id: uniqueId("profile", state.draft.profiles), name: "", priority: 100, enabled: true,
      match: { hosts: [], includeSubdomains: false, pathRegex: "" }, navigationAllowlist: [],
      webView: { userAgentProfile: "default", customUserAgent: "", thirdPartyCookies: false, blockImages: false },
      automation: { videoSelector: "video", playButtonSelector: "", hideSelectors: [] },
      fullscreen: { strategies: [{ type: "requestVideoFullscreen" }, { type: "cssCinema" }] }, scriptRefs: []
    };
    var match = profile.match || {};
    var webView = profile.webView || {};
    var automation = profile.automation || {};
    var strategies = (profile.fullscreen && profile.fullscreen.strategies || []).map(strategyRowHtml).join("");
    var scripts = state.draft.scripts.map(function (script) { return [script.id, script.name || script.id]; });
    openModal(editing ? "编辑站点规则" : "新增站点规则", "profile", index, "<div class=\"form-grid\">" +
      inputHtml("id", "规则 ID", profile.id, "text", true) + inputHtml("name", "名称", profile.name || "", "text", true) +
      inputHtml("priority", "匹配优先级", profile.priority, "number") + checkHtml("enabled", "启用规则", profile.enabled) +
      "<h3 class=\"form-divider\">匹配与导航</h3>" +
      inputHtml("hosts", "匹配域名（每行一个）", (match.hosts || []).join("\n"), "textarea", true) +
      inputHtml("pathRegex", "路径正则（可选）", match.pathRegex || "", "text") +
      checkHtml("includeSubdomains", "包含子域名", match.includeSubdomains === true) +
      inputHtml("navigationAllowlist", "允许导航域名（每行一个）", (profile.navigationAllowlist || []).join("\n"), "textarea", false, "wide") +
      "<h3 class=\"form-divider\">网页与自动播放</h3>" +
      selectHtml("userAgentProfile", "User-Agent 模式", webView.userAgentProfile === "default" ? "system" : (webView.userAgentProfile || "system"), [["system", "系统默认"], ["desktop-chrome", "桌面 Chrome"], ["mobile", "移动 Chrome"], ["custom", "自定义"]]) +
      inputHtml("customUserAgent", "自定义 User-Agent", webView.customUserAgent || "", "text") +
      checkHtml("thirdPartyCookies", "允许第三方 Cookie", webView.thirdPartyCookies === true) + checkHtml("blockImages", "阻止图片加载", webView.blockImages === true) +
      inputHtml("videoSelector", "视频元素选择器", automation.videoSelector || "video", "text") + inputHtml("playButtonSelector", "播放按钮选择器（可选）", automation.playButtonSelector || "", "text") +
      inputHtml("hideSelectors", "隐藏元素选择器（每行一个）", (automation.hideSelectors || []).join("\n"), "textarea", false, "wide") +
      "<h3 class=\"form-divider\">全屏策略</h3>" +
      "<div class=\"subeditor\"><div class=\"subeditor-head\"><span>按列表顺序尝试</span><button id=\"add-strategy-row\" class=\"secondary small\" type=\"button\">新增策略</button></div><div id=\"strategy-rows\">" + strategies + "</div></div>" +
      multiSelectHtml("scriptRefs", "绑定高级脚本", profile.scriptRefs || [], scripts) +
      "</div>");
  }

  function strategyRowHtml(strategy) {
    strategy = strategy || { type: "clickSelector", selector: "" };
    return "<div class=\"subrow strategy-row\">" + selectHtml("strategyType", "策略", strategy.type, [
      ["clickSelector", "点击全屏按钮"], ["requestVideoFullscreen", "请求视频全屏"], ["cssCinema", "CSS 铺满屏幕"]
    ]) + inputHtml("selector", "按钮选择器（点击策略必填）", strategy.selector || "", "text") + "<button class=\"danger small remove-row\" type=\"button\">删除</button></div>";
  }

  function openScript(index) {
    var editing = index !== undefined;
    var script = editing ? deepCopy(state.draft.scripts[index]) : {
      id: uniqueId("script", state.draft.scripts), name: "", version: 1, phase: "pageFinished", hosts: [], body: "", timeoutMs: 5000, oncePerNavigation: true, enabled: true
    };
    openModal(editing ? "编辑高级脚本" : "新增高级脚本", "script", index, "<div class=\"form-grid\">" +
      inputHtml("id", "脚本 ID", script.id, "text", true) + inputHtml("name", "名称", script.name || "", "text", true) +
      inputHtml("version", "脚本版本", script.version, "number") + selectHtml("phase", "注入阶段", script.phase || "pageFinished", [["documentStart", "文档开始"], ["domReady", "DOM 就绪"], ["pageFinished", "页面完成"]]) +
      inputHtml("hosts", "允许运行的域名（每行一个）", (script.hosts || []).join("\n"), "textarea", true, "wide") +
      inputHtml("timeoutMs", "超时（毫秒）", script.timeoutMs, "number") + checkHtml("oncePerNavigation", "每次导航只执行一次", script.oncePerNavigation !== false) +
      checkHtml("enabled", "启用脚本", script.enabled !== false) +
      inputHtml("body", "JavaScript", script.body || "", "textarea", true, "wide code-field") +
      "</div>");
    var body = $("[name=body]", $("#modal-form"));
    if (body) body.rows = 12;
  }

  function openModal(title, kind, index, fieldsHtml) {
    $("#modal-title").textContent = title;
    var form = $("#modal-form");
    form.dataset.kind = kind;
    form.dataset.index = index === undefined ? "" : String(index);
    form.innerHTML = fieldsHtml + "<div class=\"form-actions\"><button id=\"modal-cancel\" class=\"secondary\" type=\"button\">取消</button><button class=\"primary\" type=\"submit\">保存</button></div>";
    $("#modal").classList.remove("hidden");
    var first = $("input, select, textarea", form);
    if (first) first.focus();
  }

  function closeModal() { $("#modal").classList.add("hidden"); }

  function inputHtml(name, label, value, type, required, extraClass) {
    var className = extraClass ? " class=\"" + escapeAttr(extraClass) + "\"" : "";
    var requiredAttr = required ? " required" : "";
    if (type === "textarea") {
      return "<label" + className + "><span>" + escapeHtml(label) + "</span><textarea name=\"" + escapeAttr(name) + "\" rows=\"3\"" + requiredAttr + ">" + escapeHtml(value == null ? "" : String(value)) + "</textarea></label>";
    }
    return "<label" + className + "><span>" + escapeHtml(label) + "</span><input name=\"" + escapeAttr(name) + "\" type=\"" + escapeAttr(type || "text") + "\" value=\"" + escapeAttr(value == null ? "" : String(value)) + "\"" + requiredAttr + "></label>";
  }

  function checkHtml(name, label, checked) {
    return "<label class=\"check\"><input name=\"" + escapeAttr(name) + "\" type=\"checkbox\"" + (checked ? " checked" : "") + "><span>" + escapeHtml(label) + "</span></label>";
  }

  function selectHtml(name, label, value, options, required) {
    var html = options.map(function (option) {
      return "<option value=\"" + escapeAttr(option[0]) + "\"" + (String(option[0]) === String(value) ? " selected" : "") + ">" + escapeHtml(option[1]) + "</option>";
    }).join("");
    return "<label><span>" + escapeHtml(label) + "</span><select name=\"" + escapeAttr(name) + "\"" + (required ? " required" : "") + ">" + html + "</select></label>";
  }

  function multiSelectHtml(name, label, values, options) {
    if (!options.length) return "";
    var html = options.map(function (option) {
      return "<option value=\"" + escapeAttr(option[0]) + "\"" + (values.indexOf(option[0]) >= 0 ? " selected" : "") + ">" + escapeHtml(option[1]) + "</option>";
    }).join("");
    return "<label class=\"wide\"><span>" + escapeHtml(label) + "（可多选）</span><select name=\"" + escapeAttr(name) + "\" multiple size=\"4\">" + html + "</select></label>";
  }

  function saveModal(event) {
    event.preventDefault();
    var form = event.currentTarget;
    if (!form.reportValidity()) return;
    var kind = form.dataset.kind;
    var index = form.dataset.index === "" ? -1 : Number(form.dataset.index);
    try {
      var value;
      if (kind === "group") value = readGroup(form);
      if (kind === "channel") value = readChannel(form);
      if (kind === "profile") value = readProfile(form);
      if (kind === "script") value = readScript(form);
      assertUniqueId(kind, value.id, index);
      var key = { group: "groups", channel: "channels", profile: "profiles", script: "scripts" }[kind];
      if (index >= 0) state.draft[key][index] = value;
      else state.draft[key].push(value);
      closeModal();
      changed();
    } catch (error) {
      toast(error.message, true);
    }
  }

  function readGroup(form) {
    return { id: val(form, "id"), name: val(form, "name"), order: num(form, "order", 0), enabled: checked(form, "enabled") };
  }

  function readChannel(form) {
    var sources = $$(".source-row", form).map(function (row) {
      return {
        id: val(row, "sourceId"), url: val(row, "url"), profileId: val(row, "profileId"),
        priority: num(row, "priority", 100), timeoutMs: num(row, "timeoutMs", 15000), enabled: checked(row, "sourceEnabled")
      };
    });
    if (!sources.length) throw new Error("频道至少需要一个站源。");
    var ids = sources.map(function (source) { return source.id; });
    if (new Set(ids).size !== ids.length) throw new Error("同一频道内的站源 ID 不能重复。");
    return {
      id: val(form, "id"), name: val(form, "name"), logo: val(form, "logo"), groupId: val(form, "groupId"),
      order: num(form, "order", 0), enabled: checked(form, "enabled"), sources: sources
    };
  }

  function readProfile(form) {
    var strategies = $$(".strategy-row", form).map(function (row) {
      var strategy = { type: val(row, "strategyType") };
      var selector = val(row, "selector");
      if (selector) strategy.selector = selector;
      if (strategy.type === "clickSelector" && !selector) throw new Error("点击全屏按钮策略必须填写选择器。");
      return strategy;
    });
    var scriptRefs = $$('[name="scriptRefs"] option:checked', form).map(function (option) { return option.value; });
    return {
      id: val(form, "id"), name: val(form, "name"), priority: num(form, "priority", 100), enabled: checked(form, "enabled"),
      match: { hosts: lines(val(form, "hosts")), includeSubdomains: checked(form, "includeSubdomains"), pathRegex: val(form, "pathRegex") },
      navigationAllowlist: lines(val(form, "navigationAllowlist")),
      webView: {
        userAgentProfile: val(form, "userAgentProfile"), customUserAgent: val(form, "customUserAgent"),
        userAgent: val(form, "userAgentProfile") === "custom" ? val(form, "customUserAgent") : val(form, "userAgentProfile"),
        thirdPartyCookies: checked(form, "thirdPartyCookies"), blockImages: checked(form, "blockImages")
      },
      automation: { videoSelector: val(form, "videoSelector"), playButtonSelector: val(form, "playButtonSelector"), hideSelectors: lines(val(form, "hideSelectors")) },
      fullscreen: { strategies: strategies }, scriptRefs: scriptRefs
    };
  }

  function readScript(form) {
    return {
      id: val(form, "id"), name: val(form, "name"), version: num(form, "version", 1), phase: val(form, "phase"),
      hosts: lines(val(form, "hosts")), body: val(form, "body"), timeoutMs: num(form, "timeoutMs", 5000),
      oncePerNavigation: checked(form, "oncePerNavigation"), enabled: checked(form, "enabled")
    };
  }

  function val(root, name) { var element = $('[name="' + name + '"]', root); return element ? element.value.trim() : ""; }
  function num(root, name, fallback) { return numberOr(val(root, name), fallback); }
  function checked(root, name) { var element = $('[name="' + name + '"]', root); return !!(element && element.checked); }
  function lines(value) { return value.split(/\r?\n|,/).map(function (part) { return part.trim(); }).filter(Boolean); }

  function assertUniqueId(kind, id, index) {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(id)) throw new Error("ID 只能包含字母、数字、点、下划线和短横线。");
    var key = { group: "groups", channel: "channels", profile: "profiles", script: "scripts" }[kind];
    var duplicate = state.draft[key].some(function (item, itemIndex) { return item.id === id && itemIndex !== index; });
    if (duplicate) throw new Error("ID \"" + id + "\" 已存在。");
  }

  function uniqueId(prefix, items) {
    var ids = new Set(items.map(function (item) { return item.id; }));
    var index = 1;
    while (ids.has(prefix + "-" + index)) index += 1;
    return prefix + "-" + index;
  }

  function nextChannelOrder(groupId) {
    var orders = state.draft.channels.filter(function (channel) {
      return channel.groupId === groupId;
    }).map(function (channel) { return numberOr(channel.order, 0); });
    return orders.length ? Math.max.apply(Math, orders) + 1 : 0;
  }

  function nextOrder(items) {
    var orders = items.map(function (item) { return numberOr(item.order, 0); });
    return orders.length ? Math.max.apply(Math, orders) + 1 : 0;
  }

  function changed() {
    renderGroups();
    renderChannels();
    renderProfiles();
    renderScripts();
    syncJsonEditor();
    updateDirty(true);
  }

  function updateDirty(force) {
    state.dirty = force === true || stableJson(state.draft) !== state.baseline;
    $("#dirty-badge").classList.toggle("hidden", !state.dirty);
  }

  function syncJsonEditor() { $("#json-editor").value = JSON.stringify(state.draft, null, 2); }

  async function saveDraft() {
    try {
      setBusy($("#save-draft"), true, "保存中...");
      var payload = await api("/api/config/draft", jsonRequest("PUT", state.draft));
      var returned = extract(payload, ["draft", "config"]);
      if (returned && returned.schemaVersion) state.draft = normalizeConfig(returned);
      state.baseline = stableJson(state.draft);
      updateDirty(false);
      toast("草稿已保存。");
    } catch (error) { toast(error.message, true); }
    finally { setBusy($("#save-draft"), false, "保存草稿"); }
  }

  async function validateConfig(config) {
    return api("/api/config/validate", jsonRequest("POST", config));
  }

  async function validateAndApply() {
    var button = $("#apply-config");
    try {
      setBusy(button, true, "正在校验...");
      var result = await validateConfig(state.draft);
      showValidation(result);
      if (!isValidResult(result)) throw new Error("配置校验未通过，请在测试与维护页面查看错误。");
      await api("/api/config/draft", jsonRequest("PUT", state.draft));
      setBusy(button, true, "正在应用...");
      await api("/api/config/apply", jsonRequest("POST", state.draft));
      state.baseline = stableJson(state.draft);
      state.dirty = false;
      updateDirty(false);
      toast("配置已应用。App 将自动重启，请稍后重新连接。");
      setConnection("App 正在重启...", false);
    } catch (error) { toast(error.message, true); }
    finally { setBusy(button, false, "校验并应用"); }
  }

  function isValidResult(result) {
    var data = extract(result, ["validation", "result"]);
    if (!data || typeof data !== "object") return true;
    if (data.valid === false || data.success === false) return false;
    return !(Array.isArray(data.errors) && data.errors.length);
  }

  function showValidation(result) {
    var target = $("#validation-result");
    var data = extract(result, ["validation", "result"]) || {};
    var errors = Array.isArray(data.errors) ? data.errors : [];
    var warnings = Array.isArray(data.warnings) ? data.warnings : [];
    var messages = [];
    if (errors.length) messages.push("错误：\n" + errors.map(formatIssue).join("\n"));
    if (warnings.length) messages.push("警告：\n" + warnings.map(formatIssue).join("\n"));
    if (!messages.length) messages.push("校验通过，未发现错误。");
    target.textContent = messages.join("\n\n");
    target.className = "validation-result " + (errors.length || data.valid === false ? "error" : "success");
  }

  function formatIssue(issue) {
    if (typeof issue === "string") return "- " + issue;
    return "- " + (issue.path ? issue.path + ": " : "") + (issue.message || issue.error || JSON.stringify(issue));
  }

  async function validateJsonEditor() {
    try {
      var parsed = JSON.parse($("#json-editor").value);
      var result = await validateConfig(parsed);
      showValidation(result);
    } catch (error) {
      $("#validation-result").textContent = "JSON 或校验请求错误：" + error.message;
      $("#validation-result").className = "validation-result error";
    }
  }

  async function importJson() {
    try {
      var parsed = normalizeConfig(JSON.parse($("#json-editor").value));
      var response = await api("/api/config/import", jsonRequest("POST", parsed));
      var imported = extract(response, ["draft", "config"]);
      state.draft = normalizeConfig(imported && imported.schemaVersion ? imported : parsed);
      state.baseline = stableJson(state.draft);
      renderAll();
      toast("配置已导入为草稿，尚未应用。");
    } catch (error) { toast("导入失败：" + error.message, true); }
  }

  async function exportConfig() {
    try {
      var response = await fetch("/api/config/export", { headers: { "Accept": "application/json" } });
      if (!response.ok) throw new Error("HTTP " + response.status);
      var blob = await response.blob();
      var url = URL.createObjectURL(blob);
      var link = document.createElement("a");
      link.href = url;
      link.download = "webtvshell-config-v" + state.draft.configVersion + ".json";
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      toast("配置已导出。");
    } catch (error) { toast("导出失败：" + error.message, true); }
  }

  async function matchTest(event) {
    event.preventDefault();
    var target = $("#match-result");
    target.textContent = "测试中...";
    try {
      var result = await api("/api/rules/match-test", jsonRequest("POST", { url: $("#match-url").value.trim(), config: state.draft }));
      target.textContent = JSON.stringify(result, null, 2);
    } catch (error) { target.textContent = "测试失败：" + error.message; }
  }

  async function refreshHistory() {
    try {
      var result = await api("/api/config/history");
      state.history = normalizeHistory(extract(result, ["history", "versions"]));
      renderHistory();
    } catch (error) { toast(error.message, true); }
  }

  async function rollback(version) {
    if (!window.confirm("确定回滚到配置版本 " + version + "？App 将自动重启。")) return;
    try {
      await api("/api/config/rollback/" + encodeURIComponent(version), { method: "POST" });
      toast("已回滚到版本 " + version + "。App 将自动重启，请稍后重新连接。");
      setConnection("App 正在重启...", false);
    } catch (error) { toast("回滚失败：" + error.message, true); }
  }

  function deleteEntity(kind, index) {
    var keys = { group: "groups", channel: "channels", profile: "profiles", script: "scripts" };
    var key = keys[kind];
    var item = state.draft[key][index];
    if (!item) return;
    if (kind === "group" && state.draft.channels.some(function (channel) { return channel.groupId === item.id; })) {
      toast("该分组仍有频道，请先移动或删除这些频道。", true);
      return;
    }
    if (!window.confirm("确定删除 \"" + (item.name || item.id) + "\"？")) return;
    state.draft[key].splice(index, 1);
    if (kind === "profile") {
      state.draft.channels.forEach(function (channel) { channel.sources.forEach(function (source) { if (source.profileId === item.id) source.profileId = ""; }); });
    }
    if (kind === "script") {
      state.draft.profiles.forEach(function (profile) { profile.scriptRefs = (profile.scriptRefs || []).filter(function (id) { return id !== item.id; }); });
    }
    changed();
  }

  function bindEvents() {
    $$(".nav-item").forEach(function (button) {
      button.addEventListener("click", function () {
        $$(".nav-item").forEach(function (item) { item.classList.toggle("active", item === button); });
        $$(".view").forEach(function (view) { view.classList.toggle("active", view.id === "view-" + button.dataset.view); });
      });
    });
    $("#refresh-all").addEventListener("click", loadAll);
    $("#save-draft").addEventListener("click", saveDraft);
    $("#apply-config").addEventListener("click", validateAndApply);
    $("#add-group").addEventListener("click", function () { openGroup(); });
    $("#add-channel").addEventListener("click", function () { openChannel(); });
    $("#add-profile").addEventListener("click", function () { openProfile(); });
    $("#add-script").addEventListener("click", function () { openScript(); });
    $("#modal-close").addEventListener("click", closeModal);
    $("#modal").addEventListener("click", function (event) { if (event.target === event.currentTarget) closeModal(); });
    $("#modal-form").addEventListener("submit", saveModal);
    $("#modal-form").addEventListener("click", handleModalClick);
    $("#modal-form").addEventListener("change", handleModalChange);
    $("#match-form").addEventListener("submit", matchTest);
    $("#validate-json").addEventListener("click", validateJsonEditor);
    $("#import-config").addEventListener("click", importJson);
    $("#export-config").addEventListener("click", exportConfig);
    $("#refresh-history").addEventListener("click", refreshHistory);
    $("#json-editor").addEventListener("input", function () {
      $("#validation-result").textContent = "编辑内容尚未校验";
      $("#validation-result").className = "validation-result muted";
    });
    document.addEventListener("click", handleEntityClick);
    window.addEventListener("beforeunload", function (event) {
      if (!state.dirty) return;
      event.preventDefault();
      event.returnValue = "";
    });
    document.addEventListener("keydown", function (event) { if (event.key === "Escape") closeModal(); });
  }

  function handleModalClick(event) {
    if (event.target.id === "modal-cancel") closeModal();
    if (event.target.id === "add-source-row") $("#source-rows").insertAdjacentHTML("beforeend", sourceRowHtml());
    if (event.target.id === "add-strategy-row") $("#strategy-rows").insertAdjacentHTML("beforeend", strategyRowHtml());
    if (event.target.classList.contains("remove-row")) event.target.closest(".subrow").remove();
  }

  function handleModalChange(event) {
    var form = event.currentTarget;
    if (form.dataset.kind !== "channel" || form.dataset.index !== "" || event.target.name !== "groupId") return;
    var order = $('[name="order"]', form);
    if (order) order.value = String(nextChannelOrder(event.target.value));
  }

  function handleEntityClick(event) {
    var button = event.target.closest("button[data-action]");
    if (!button) return;
    var action = button.dataset.action;
    if (action === "rollback") return rollback(button.dataset.version);
    var index = Number(button.dataset.index);
    var kind = button.dataset.kind;
    if (action === "delete") return deleteEntity(kind, index);
    if (action === "edit") {
      if (kind === "group") openGroup(index);
      if (kind === "channel") openChannel(index);
      if (kind === "profile") openProfile(index);
      if (kind === "script") openScript(index);
    }
  }

  function setBusy(button, busy, label) {
    button.disabled = busy;
    button.textContent = label;
  }

  function setConnection(label, error) {
    var element = $("#connection-label");
    element.textContent = label;
    element.style.color = error ? "#ffc6bf" : "";
  }

  function toast(message, error) {
    var element = document.createElement("div");
    element.className = "toast" + (error ? " error" : "");
    element.textContent = message;
    $("#toast-region").appendChild(element);
    setTimeout(function () { element.remove(); }, 5200);
  }

  function formatDate(value) {
    var date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", { hour12: false });
  }

  function deepCopy(value) { return JSON.parse(JSON.stringify(value)); }
  function stableJson(value) { return JSON.stringify(value); }
  function escapeHtml(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/\"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function escapeAttr(value) { return escapeHtml(value); }

  bindEvents();
  loadAll();
}());
