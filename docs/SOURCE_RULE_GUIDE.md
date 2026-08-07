# WebTvShell 站源与匹配规则维护指南

本文说明当前配置结构、央视规则的实现方式，以及以后增加新站源时的调查、配置、验证和回滚步骤。内容以当前代码行为为准，不把规划中的能力当成已经实现。

## 1. 核心边界

- App 是网页播放器壳，不解析、代理或转码视频流。
- 频道 URL、站点域名、DOM 选择器、UA 和站点专用 JS 必须放在 JSON 配置中。
- Java 代码只实现通用能力：WebView、规则匹配、播放尝试、通用影院模式、遥控器/触摸操作和配置服务。
- App 最低支持 Android 5.0/API 21；项目无 native `.so`，同一 APK 可运行在 `armeabi-v7a` 32 位 ARM 和 `arm64-v8a` 设备上。具体直播兼容性仍取决于设备上的 System WebView、TLS 和硬件解码能力；能安装不等于所有站点都能播放。
- 不因单个站点兼容问题引入新的浏览器内核、视频 SDK 或运行时第三方依赖。
- 不跳过 SSL 错误，不绕过登录、付费、地域、DRM 或网站访问限制。

## 2. 相关文件

| 文件 | 作用 |
|---|---|
| `app/src/main/assets/default_config.json` | APK 内置出厂配置，仅在应用数据中没有 factory 配置时复制 |
| `app/src/main/java/com/webtvshell/app/model/AppConfig.java` | JSON 运行时解析、频道排序和 profile 选择 |
| `app/src/main/java/com/webtvshell/app/model/SiteProfile.java` | host/path 匹配与优先级评分 |
| `app/src/main/java/com/webtvshell/app/browser/BrowserController.java` | WebView 设置、注入、播放和全屏 |
| `app/src/main/java/com/webtvshell/app/config/ConfigRepository.java` | 配置校验、active/draft/history 和回滚 |
| `app/src/main/java/com/webtvshell/app/server/LanConfigServer.java` | `6688` 局域网配置服务和 API |
| `app/src/main/assets/www/` | 内置 Web 配置页面 |

重要：修改 `default_config.json` 不会覆盖已安装设备的 active 配置。已有设备必须通过 `http://盒子IP:6688` 导入并应用，或明确清除应用数据后重新安装。局域网配置端口不得使用 Chromium 受限端口（例如 `6665-6669`），否则浏览器会在发送请求前直接报 `ERR_UNSAFE_PORT`。

## 3. 配置生命周期

应用数据目录保存：

```text
files/config/
  factory.json
  active.json
  previous.json
  draft.json
  history/
```

流程如下：

1. 首次启动从 APK 的 `default_config.json` 创建 factory、active 和 draft。
2. Web 页面编辑的是 draft。
3. 应用前先执行结构、URL、正则和引用校验。
4. 应用成功后自动生成新的 `configVersion` 和 `updatedAt`。
5. Activity 重建，重新创建 WebView 和配置服务，并读取新的 active 配置。
6. 错误配置可从 history 回滚；回滚也会生成一个新版本。

## 4. 当前数据模型

### 4.1 顶层

```json
{
  "schemaVersion": 1,
  "configVersion": 1,
  "updatedAt": "2026-08-06T00:00:00Z",
  "groups": [],
  "channels": [],
  "profiles": [],
  "scripts": []
}
```

- `schemaVersion` 当前必须为 `1`。
- `configVersion` 应用配置时由 App 单调递增，不要手工依赖输入值。
- `updatedAt` 应用配置时由 App 重写。

### 4.2 分组和频道

```json
{
  "id": "example-group",
  "name": "示例直播",
  "order": 0,
  "enabled": true
}
```

```json
{
  "id": "example-channel",
  "name": "示例频道",
  "logo": "",
  "groupId": "example-group",
  "order": 0,
  "enabled": true,
  "sources": [
    {
      "id": "example-source",
      "url": "https://live.example.com/channel/1",
      "profileId": "example-profile",
      "priority": 100,
      "timeoutMs": 20000,
      "enabled": true
    }
  ]
}
```

- 分组按 group `order` 升序显示；频道的 `order` 只在所属分组内比较，不要求跨分组唯一。
- 管理页按分组分段展示频道；App 侧栏顶部显示可触摸的分组标签，遥控器左右键仍可切换分组。
- 同频道多个 source 按 `priority` 降序尝试。
- `profileId` 指向 profile，但该 profile 仍必须匹配最终 URL 的 host/path。
- 当前主页面明确加载失败时才会切换备用 source。
- `timeoutMs` 当前会被校验，但尚未用于运行时超时切源。

### 4.3 站点 profile

```json
{
  "id": "example-profile",
  "name": "示例站点规则",
  "priority": 100,
  "enabled": true,
  "match": {
    "hosts": ["live.example.com"],
    "includeSubdomains": false,
    "pathRegex": "^/channel/"
  },
  "navigationAllowlist": ["live.example.com", "player.example.com"],
  "webView": {
    "userAgentProfile": "desktop-chrome",
    "customUserAgent": "",
    "thirdPartyCookies": true,
    "blockImages": false
  },
  "automation": {
    "videoSelector": "video",
    "playButtonSelector": ".play-button",
    "hideSelectors": ["header", ".advertisement"]
  },
  "fullscreen": {
    "strategies": [
      {"type": "clickSelector", "selector": ".fullscreen-button"},
      {"type": "requestVideoFullscreen"},
      {"type": "cssCinema"}
    ]
  },
  "scriptRefs": ["example-bootstrap"]
}
```

当前真正影响运行时的字段：

- `match.hosts/includeSubdomains/pathRegex`
- `priority`
- `webView.userAgentProfile/customUserAgent/thirdPartyCookies/blockImages`
- `automation.videoSelector/playButtonSelector/hideSelectors`
- `fullscreen.strategies` 中的 `clickSelector`
- `customJs`、`automation.customJs` 和 `scriptRefs` 引用脚本的 `body`

`userAgentProfile` 支持：

- `desktop-chrome`：使用内置桌面 Chrome UA。
- `system`、`default`、`mobile` 或空值：使用系统 WebView UA。
- 其他非空值：直接作为自定义 UA。

### 4.4 高级脚本

```json
{
  "id": "example-bootstrap",
  "name": "示例动态播放器启动",
  "enabled": true,
  "body": "(function(){/* site-specific code */})();"
}
```

当前运行时只读取启用脚本的 `id` 和 `body`，通过 profile 的 `scriptRefs` 拼接后在 `onPageFinished` 注入。

## 5. 规则匹配算法

匹配使用最终主页面 URL 的规范化 host 和 path：

1. 如果 source 指定 `profileId`，先查找该 profile。
2. 指定 profile 只有在 host/path 也匹配时才生效。
3. 否则遍历所有 profile，选择匹配分数最高者。
4. 精确 host 高于允许的子域匹配。
5. 有 `pathRegex` 的匹配高于仅 host 匹配。
6. 同级再叠加 profile `priority`。
7. 没有普通规则命中时，才使用 id 为 `default` 或 `*` 的空 host profile。

注意：Java 正则使用 `find()`，建议始终用 `^` 约束开头，必要时用 `$` 约束结尾，避免意外命中相似路径。

## 6. 当前央视规则拆解

央视频道配置位于 `default_config.json`：

- 分组：`cctv`
- 频道：直播大全、CCTV-1 至 CCTV-17、CCTV-5+、CCTV-9 纪录、CCTV-14 少儿
- profile：`cctv-live-profile`
- 脚本：`cctv-player-bootstrap`

关键行为：

- 使用 `desktop-chrome` UA，避免 Android UA 跳到通用移动入口。
- 具体频道 URL 带 `?return=true`。
- 动态视频节点：`#h5player_player`。
- 播放按钮：`#play_or_plause_player`。
- 站点全屏按钮：`#player_fullscreen_player`、`#player_pagefullscreen_player`。
- profile 负责央视 DOM 选择器，Java 只负责通用 CSS 影院模式。
- 视频是运行时 blob/MSE 媒体，不解析或写死视频 src。

### 6.1 当前卫视频道规则

默认配置将频道按站源拆成两个组：

- `satellite`（“卫视频道”）：央视频页面当前列出的 32 个卫视入口，全部复用 `yangshipin-live-profile`。
- `satellite-external`（“其他站源”）：原来已经配置的山东、安徽、东南、江西、湖北、广西地方台官网入口，保留各自 profile，不与央视频入口混在同一组。

2026-08-07 使用桌面 Chromium 打开央视频页面并逐项点击频道，确认了下面 32 个入口的 `pid`。这证明入口和页面路由有效，不等同于目标盒子已完成每个频道的真实解码验收；网站改版、地区限制和 System WebView 差异仍需逐项复测。

| 频道 | 网页入口 | profile | 视频/播放/全屏选择器 |
|---|---|---|---|
| 北京卫视 | `https://www.yangshipin.cn/tv/home?pid=600002309` | `yangshipin-live-profile` | `video.video-js` / 自动 `play()` / `.full.full2` |
| 江苏卫视 | `https://www.yangshipin.cn/tv/home?pid=600002521` | 同上 | 同上 |
| 东方卫视 | `https://www.yangshipin.cn/tv/home?pid=600002483` | 同上 | 同上 |
| 浙江卫视 | `https://www.yangshipin.cn/tv/home?pid=600002520` | 同上 | 同上 |
| 湖南卫视 | `https://www.yangshipin.cn/tv/home?pid=600002475` | 同上 | 同上 |
| 湖北卫视 | `https://www.yangshipin.cn/tv/home?pid=600002508` | 同上 | 同上 |
| 广东卫视 | `https://www.yangshipin.cn/tv/home?pid=600002485` | 同上 | 同上 |
| 广西卫视 | `https://www.yangshipin.cn/tv/home?pid=600002509` | 同上 | 同上 |
| 黑龙江卫视 | `https://www.yangshipin.cn/tv/home?pid=600002498` | 同上 | 同上 |
| 海南卫视 | `https://www.yangshipin.cn/tv/home?pid=600002506` | 同上 | 同上 |
| 重庆卫视 | `https://www.yangshipin.cn/tv/home?pid=600002531` | 同上 | 同上 |
| 深圳卫视 | `https://www.yangshipin.cn/tv/home?pid=600002481` | 同上 | 同上 |
| 四川卫视 | `https://www.yangshipin.cn/tv/home?pid=600002516` | 同上 | 同上 |
| 河南卫视 | `https://www.yangshipin.cn/tv/home?pid=600002525` | 同上 | 同上 |
| 福建东南卫视 | `https://www.yangshipin.cn/tv/home?pid=600002484` | 同上 | 同上 |
| 贵州卫视 | `https://www.yangshipin.cn/tv/home?pid=600002490` | 同上 | 同上 |
| 江西卫视 | `https://www.yangshipin.cn/tv/home?pid=600002503` | 同上 | 同上 |
| 辽宁卫视 | `https://www.yangshipin.cn/tv/home?pid=600002505` | 同上 | 同上 |
| 安徽卫视 | `https://www.yangshipin.cn/tv/home?pid=600002532` | 同上 | 同上 |
| 河北卫视 | `https://www.yangshipin.cn/tv/home?pid=600002493` | 同上 | 同上 |
| 山东卫视 | `https://www.yangshipin.cn/tv/home?pid=600002513` | 同上 | 同上 |
| 天津卫视 | `https://www.yangshipin.cn/tv/home?pid=600152137` | 同上 | 同上 |
| 吉林卫视 | `https://www.yangshipin.cn/tv/home?pid=600190405` | 同上 | 同上 |
| 陕西卫视 | `https://www.yangshipin.cn/tv/home?pid=600190400` | 同上 | 同上 |
| 甘肃卫视 | `https://www.yangshipin.cn/tv/home?pid=600190408` | 同上 | 同上 |
| 宁夏卫视 | `https://www.yangshipin.cn/tv/home?pid=600190737` | 同上 | 同上 |
| 内蒙古卫视 | `https://www.yangshipin.cn/tv/home?pid=600190401` | 同上 | 同上 |
| 云南卫视 | `https://www.yangshipin.cn/tv/home?pid=600190402` | 同上 | 同上 |
| 山西卫视 | `https://www.yangshipin.cn/tv/home?pid=600190407` | 同上 | 同上 |
| 青海卫视 | `https://www.yangshipin.cn/tv/home?pid=600190406` | 同上 | 同上 |
| 西藏卫视 | `https://www.yangshipin.cn/tv/home?pid=600190403` | 同上 | 同上 |
| 新疆卫视 | `https://www.yangshipin.cn/tv/home?pid=600152138` | 同上 | 同上 |

“其他站源”组的六个入口和站点规则如下：

| 频道 | 网页入口 | profile | 视频/播放/全屏选择器 |
|---|---|---|---|
| 山东卫视 | `https://v.iqilu.com/live/sdtv/` | `iqilu-satellite-profile` | `#player-id_html5_api, #player-id video` / `#player-id .vjs-big-play-button` / `#player-id .vjs-fullscreen-control` |
| 安徽卫视 | `https://www.ahtv.cn/folder9000/folder20193?channelIndex=0`（最终为 `h5.ahtv.cn`） | `ahtv-satellite-profile` | `#playercontainer video` / `#playercontainer .prism-play-btn` / `#playercontainer .prism-fullscreen-btn` |
| 东南卫视 | `https://live.fjtv.net/setv/` | `fjtv-satellite-profile` | `#m2o_player video` / `#m2o_player .prism-big-play-btn` / `#m2o_player .prism-fullscreen-btn` |
| 江西卫视 | `https://www.jxntv.cn/live/` | `jxntv-satellite-profile` | `#liveplayer video` / `#liveplayer .prism-big-play-btn` / `#liveplayer .prism-fullscreen-btn` |
| 湖北卫视 | `https://news.hbtv.com.cn/app/tv/431` | `hbtv-satellite-profile` | `#player-con video` / `#player-con .prism-big-play-btn` / `#player-con .prism-fullscreen-btn` |
| 广西卫视 | `https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html` | `gxtv-satellite-profile` | `#player video` / `#player .playback-layer, #player .xbtn-playback` / `#player .xbtn-fullscreen` |

央视频的播放按钮是动态出现的，不能把 `.play.play2` 作为唯一入口；`yangshipin-player-bootstrap` 只等待 `video.video-js`、调用网页 `play()`，再尝试 `.full.full2`，最后由通用 CSS Cinema 回落。配置不保存页面暴露的 blob/m3u8 地址。

2026-08-06 已在 Android 14 / System WebView 116 盒子上确认湖南卫视、山东卫视和安徽卫视真实解码播放；其中安徽卫视还通过侧栏触摸单击切台验证。其余卫视规则已完成页面/DOM 实测，仍应在目标盒子逐项验收。

调研中未加入默认配置的入口：北京广播电视台跳转页要求登录；河南卫视网页只提供短时试看并要求使用 App；四川卫视官方页也要求登录后才创建播放器并获取防盗链密钥。这三类页面不符合当前无账号、只播放公开网页的 MVP 边界。

广东卫视（荔枝网）已找到官方频道页，但依赖 TCDN WebSocket 动态取播放地址；在目标盒子完成解码回归前暂不放入默认频道，避免侧栏出现不可播放项。

## 7. 增加新站源的标准步骤

### 步骤 1：确认入口和使用边界

- 使用网站公开可访问的直播页面，不提取付费、登录、DRM 或受限流地址。
- 记录主入口 URL、最终重定向 URL、是否需要 query 参数。
- 分别用桌面 UA 和 Android UA 检查是否发生跳转或展示不同播放器。

### 步骤 2：调查播放器 DOM

在桌面 Chrome 和目标盒子 WebView 中确认：

- 播放器是否在主文档或跨域 iframe 中。
- `<video>` 是否静态存在，还是由脚本延迟创建。
- 稳定的视频、播放按钮和全屏按钮选择器。
- 播放器外部是否有需要隐藏的导航、广告或侧栏。
- 自动播放是否要求静音或一次真实用户操作。
- 是否依赖第三方 Cookie。

选择器优先级：稳定 id > 稳定 data 属性 > 稳定 class > DOM 层级。不要依赖易变的第几个子元素或压缩脚本生成类名。

### 步骤 3：添加分组和频道

1. 复用已有分组，或新增唯一 group `id`。
2. 每个遥控器/触摸可切换的频道必须是独立 channel。
3. 为每个 channel 配置完整网页 URL，不在客户端硬编码频道表。
4. source `profileId` 指向对应站点 profile。
5. 同频道存在备用网页入口时才增加多个 source，并设置明确 priority。

### 步骤 4：添加或复用 profile

1. `match.hosts` 只写域名，不带 scheme、端口或路径。
2. 非必要不要开启 `includeSubdomains`。
3. `pathRegex` 使用锚点限制到实际直播路径。
4. 先尝试结构化字段：UA、Cookie、video、播放按钮、隐藏项、全屏按钮。
5. 多个频道 DOM 相同则共用一个 profile，不复制相同规则。

### 步骤 5：仅为站点差异添加 JS

只有结构化字段不够时才增加 script：

- 使用 IIFE，避免污染网页全局变量。
- 通用或共享脚本优先使用 Android 5 早期 WebView 可解析的 ES5 写法；不要依赖箭头函数、`NodeList.forEach`、`Element.isConnected` 等较新能力。
- 脚本必须幂等，多次运行不能重复破坏 DOM。
- 动态节点使用有次数上限的轮询或可停止的观察器。
- 所有 `querySelector`、`play()` 和站点方法调用都要容错。
- 不使用无限定时器；推荐 250-500ms 间隔、总时长不超过 15-30 秒。
- 不把通用全屏、遥控器、触摸或配置服务逻辑复制进站点脚本。
- 不解析 blob/MSE/HLS 内部地址。

### 步骤 6：配置校验和匹配测试

通过 Web 页面完成：

1. 保存草稿。
2. 在“测试与维护”中输入每类频道 URL执行匹配测试。
3. 确认命中预期 profile，而不是其他站点或默认 profile。
4. 执行配置校验，解决全部错误后再应用。

也可直接调用局域网 API：

```powershell
$base = "http://192.168.31.126:6688"
$json = Get-Content app\src\main\assets\default_config.json -Raw
Invoke-RestMethod "$base/api/config/draft" -Method Put -ContentType "application/json" -Body $json
Invoke-RestMethod "$base/api/config/validate" -Method Post -ContentType "application/json" -Body $json
Invoke-RestMethod "$base/api/rules/match-test" -Method Post -ContentType "application/json" -Body '{"url":"https://live.example.com/channel/1"}'
Invoke-RestMethod "$base/api/config/apply" -Method Post -ContentType "application/json" -Body '{}'
```

### 步骤 7：构建检查

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

预期：构建和 Lint 成功，运行时依赖报告仍为 `No dependencies`。

### 步骤 8：目标盒子真机验证

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.webtvshell.app
adb shell am start -n com.webtvshell.app/.MainActivity
adb logcat -c
adb logcat -v time WebTvShell.WebView:D chromium:I AndroidRuntime:E *:S
```

逐项验证：

- 页面最终 URL 正确，没有跳到移动首页或错误页面。
- 直播确实在解码和播放，而不是只有静态封面。
- 自动进入纯视频全屏，网页导航和广告没有覆盖视频。
- MENU 和触摸点按能打开频道侧栏。
- 遥控器上下/确认和触摸单击能切换到正确频道。
- 滑动换台和分组切换不吞掉网页正常点击。
- `http://盒子IP:6688` 在同一局域网电脑/手机上仍可访问。
- 应用配置后 Activity 重建、版本递增、进程不崩溃。

### 步骤 9：失败时回滚

- 不要直接改 active 文件。
- 在 Web 页面历史版本中选择上一个正常版本回滚。
- 如果配置服务不可达，重新启动 App 后再访问。
- 若新 APK 的 Java 改动导致启动失败，安装上一个已验证 APK；应用数据和配置历史默认会保留。

## 8. 当前实现限制

以下字段目前会被保存或校验，但运行时语义尚不完整：

- `navigationAllowlist`：当前校验域名格式，但 BrowserController 尚未按它阻止 HTTP(S) 跨域顶层导航。
- source `timeoutMs`：当前校验但未驱动加载超时和切源。
- script `hosts/phase/timeoutMs/oncePerNavigation/version`：Web 页面可编辑，当前运行时只读取 `id/body/enabled`，统一在 `onPageFinished` 执行。
- `requestVideoFullscreen` 和 `cssCinema`：当前由通用回落逻辑执行；配置数组主要为表达意图，运行时没有完整动作 DSL 调度器。
- Fullscreen API 从 `evaluateJavascript()` 调用时可能不被 Chromium 视为可信手势，因此必须保留通用 CSS Cinema 回落。

后续 AI 不得在文档、UI 或验收说明中声称这些预留能力已经完整实现。若要补齐，需同时更新解析器、运行时、校验器、Web 编辑器、本文和真机测试。

## 9. 新站源验收清单

- [ ] 每个频道有唯一 id、正确 groupId 和完整网页 URL。
- [ ] 所有 URL 可访问且没有意外重定向。
- [ ] profile host/path 精确匹配，匹配测试通过。
- [ ] 站点专用 selector 和 JS 只存在于配置中。
- [ ] JS 幂等、有超时、错误可恢复。
- [ ] 真机确认视频解码、声音和纯视频全屏。
- [ ] 遥控器和触摸均可打开侧栏并单次操作切台。
- [ ] 局域网配置页面仍可访问。
- [ ] apply/recreate 无崩溃，active 版本已递增。
- [ ] Debug、Release、Lint 和零依赖检查通过。
