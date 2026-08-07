# WebTvShell 项目实施规划

> 状态：最小可用版本已实现，并已在 Android 14 盒子上完成央视直播、全屏、遥控器、触摸、局域网配置和热更新验证。
>
> 产品定位：运行在 Android 电视盒子上的“受控站点播放器壳”。App 直接打开频道官网，通过规则和 JS 完成自动播放/全屏；盒子自身提供局域网 Web 配置页面。

## 1. 已确认需求

- 客户端主要运行在 Android 电视盒子。
- 使用网站现成页面和播放器，不解析、不代理、不转码视频流。
- 频道、站源、URL 匹配规则和全屏注入脚本不写死在业务代码中。
- 配置页面只在局域网使用，不设计用户、登录、角色或权限。
- 配置应用成功后，App 自动重启并加载新配置。
- 遥控器必须支持 MENU、上/下/左/右、DPAD_CENTER/ENTER 和 BACK。
- 触摸必须支持点按打开频道列表、单击选台和横纵滑动切换分组/频道。
- 依赖、包体和模块数量以最小可用为准，避免独立后端、数据库和非必要框架。

## 2. 简化后的总体架构

配置服务直接运行在 Android App 内，不再部署独立服务器：

```text
手机/电脑浏览器
       │ http://盒子IP:6688
       ▼
┌──────────── Android 电视盒子 App ────────────┐
│ EmbeddedConfigServer                         │
│  ├─ 静态 Web 配置页面                         │
│  ├─ 配置 REST API                            │
│  └─ 草稿校验、应用、历史版本和回滚              │
│                    │                         │
│                    ▼                         │
│ ConfigStore -> RuleRuntime -> System WebView │
│                    │              │          │
│                遥控器输入       JS/全屏       │
└────────────────────┼──────────────┼──────────┘
                     ▼              ▼
                  频道列表       频道官网页面
```

这个架构不需要 Node 服务、数据库、Docker、CDN、账号系统或远程签名链。配置数据仍与 App 代码解耦，保存在盒子的应用数据目录，并支持浏览器编辑和 JSON 导入导出。

## 3. 项目结构

```text
WebTvShell/
  app/                      Android TV 应用模块
    src/main/java/          Java 客户端、配置仓库和 HTTP 服务
    src/main/assets/www/    零框架局域网配置页面
    src/main/assets/        默认 JSON 配置
  PROJECT_PLAN.md           方案与范围
  README.md                 构建和使用入口
```

配置页面直接以静态 HTML/CSS/JavaScript 放入 APK assets，由盒子内嵌 HTTP 服务提供。配置页面升级需要更新 APK，但频道和规则修改不需要更新 APK。

## 4. 技术选型

### 4.1 Android 客户端

- Java，单 Activity，View/XML UI。
- System Android WebView 作为 MVP 唯一浏览器内核。
- Android Framework `WebView/WebChromeClient/WebViewClient`。
- Android Framework `org.json`：配置解析和校验。
- 原子 JSON 文件：active、previous、draft 和历史快照。
- Java `ServerSocket` 实现受限 HTTP 子集，只提供静态页面和固定 REST API。
- APK 运行时第三方依赖为零。

选择 View/XML 而不是 Compose，是因为主界面主要由 WebView、Custom View 全屏容器和少量电视 Overlay 组成；传统 View 的遥控器焦点和 WebChromeClient 集成更直接。

MVP 支持从 Android 5.0/API 21 起步。老电视盒子通常同时存在陈旧 WebView、TLS 和 H5 视频兼容问题；Android 5/6 设备能否播放具体直播站点仍取决于系统 WebView 是否可更新。是否增加 X5 版本应由目标盒子实测决定。

### 4.2 Web 配置页面

- 纯 HTML、CSS 和 vanilla JavaScript。
- 原生 textarea 提供 JSON 与高级 JS 编辑。
- 响应式布局，支持手机和桌面浏览器。
- 不需要 npm 构建，不使用外部 CDN。
- 页面只调用当前盒子的相对路径 REST API，不涉及跨域和外部服务。

## 5. Android 客户端模块

| 模块 | 职责 |
|---|---|
| `app-shell` | Activity、沉浸式界面、屏保控制、生命周期 |
| `channel-ui` | 频道列表、分组、当前频道和加载状态 |
| `input-router` | 遥控器按键优先级和焦点管理 |
| `browser-core` | WebView 设置、导航限制、Custom View 全屏和 renderer 恢复 |
| `rule-runtime` | URL 匹配、动作 DSL、JS 注入和消息处理 |
| `playback-supervisor` | 主页面错误处理和备用站源切换 |
| `config-store` | 草稿、校验、激活、历史快照、导入导出和回滚 |
| `config-server` | 局域网 HTTP、静态页面和固定 REST API |
| `diagnostics` | 当前 URL、命中规则、注入阶段和脱敏错误记录 |

可以预留 `BrowserEngine` 内部接口，但 MVP 只实现 System WebView，避免一开始维护 X5/GeckoView 多套行为。

## 6. 局域网配置服务

### 6.1 生命周期

- App 启动后在局域网监听默认端口 `6688`。
- 频道列表底部和无频道页面展示 `http://盒子IP:6688`。
- 手机或电脑手工输入该地址访问配置页面。
- App 退出后服务停止，不创建常驻后台服务。
- 可在设置中关闭 Web 配置服务；关闭不影响已保存配置和播放。

### 6.2 信任边界

MVP 没有账号和权限。任何可以访问盒子 `6688` 端口的局域网设备都能查看、修改和发布配置，这是明确接受的产品约束。

因此仍需做到：

- 仅监听私网网络接口，不做公网端口映射。
- 页面明显显示当前盒子名称和 IP，防止配置错设备。
- 所有写操作只能写 draft，必须先校验再显式“应用配置”。
- 保留 previous 和历史快照，错误配置可以一键回滚。
- API 不提供文件系统、shell、Intent 或任意 Android 方法。

### 6.3 REST API

```text
GET  /api/status
GET  /api/config/active
GET  /api/config/draft
PUT  /api/config/draft
POST /api/config/validate
POST /api/config/apply
GET  /api/config/history
POST /api/config/rollback/{version}
GET  /api/config/export
POST /api/config/import
POST /api/rules/match-test
```

约束：

- 请求体设大小上限。
- 所有写入先保存临时文件，再原子替换。
- `apply` 前必须完成 Schema、URL、域名、正则和 JS 语法校验。
- `apply` 成功返回浏览器结果后延迟重建 Activity，重新创建 WebView、配置服务并读取新的 active 配置；不依赖精确闹钟或杀进程。
- 配置修改和回滚串行执行，避免同时写文件。

## 7. 本地配置与版本

应用数据目录保存：

```text
config/
  factory.json              APK 内置的最小配置首次复制
  active.json               当前生效配置
  previous.json             上一生效配置
  draft.json                Web 页面正在编辑的草稿
  history/
    000001.json
    000002.json
```

- 历史默认保留最近 10 个版本。
- 配置带 `schemaVersion` 和单调递增 `configVersion`。
- 每份文件保存 SHA-256，读取损坏时依次退回 previous 和 factory。
- 支持导出单个 JSON 文件，在另一台盒子导入。
- 配置 Schema 升级由 App 执行显式迁移，未知 Schema 不加载。

## 8. 配置数据模型

### 8.1 顶层配置

```json
{
  "schemaVersion": 1,
  "configVersion": 12,
  "updatedAt": "2026-08-06T10:00:00Z",
  "groups": [],
  "channels": [],
  "profiles": [],
  "scripts": []
}
```

| 实体 | 主要字段 |
|---|---|
| `ChannelGroup` | `id/name/order/enabled` |
| `Channel` | `id/name/logo/groupId/order/sources[]` |
| `ChannelSource` | `id/url/profileId/priority/timeoutMs/enabled` |
| `SiteProfile` | `match/navigationAllowlist/webView/automation/fullscreen` |
| `Script` | `id/version/phase/body/timeoutMs/oncePerNavigation` |

### 8.2 站点规则示例

```json
{
  "id": "example-tv",
  "priority": 100,
  "match": {
    "hosts": ["live.example.com"],
    "includeSubdomains": false,
    "pathRegex": "^/channel/"
  },
  "navigationAllowlist": ["live.example.com", "player.examplecdn.com"],
  "webView": {
    "userAgentProfile": "desktop-chrome",
    "thirdPartyCookies": false,
    "blockImages": true
  },
  "automation": {
    "videoSelector": "video",
    "playButtonSelector": ".play-button",
    "hideSelectors": ["header", ".advertisement"]
  },
  "fullscreen": {
    "strategies": [
      {"type": "clickSelector", "selector": ".fullscreen"},
      {"type": "requestVideoFullscreen"},
      {"type": "cssCinema"}
    ]
  },
  "scriptRefs": []
}
```

### 8.3 规则匹配顺序

```text
ChannelSource 显式 profileId
  > exact host + path
  > exact host
  > suffix host（明确允许子域）
  > 通用 profile
```

同级再按最长 path、priority 和稳定 ID 排序。域名必须通过 URL Parser 规范化后精确比较，不能使用 `url.contains(domain)`。

## 9. Web 配置页面

### 9.1 页面组成

- **状态**：盒子名称、IP、App/WebView 版本、active 配置版本。
- **频道**：分组、排序、图标、多个站源、启停。
- **站点规则**：URL 匹配、UA、Cookie、图片加载、播放器选择器。
- **全屏动作**：播放按钮、全屏按钮、CSS Cinema 和动作顺序。
- **高级脚本**：JS 编辑、注入阶段、域名范围和超时。
- **匹配测试**：输入 URL，显示命中 profile 和优先级。
- **校验与应用**：错误列表、版本 Diff、应用并重载。
- **历史版本**：查看、导出和一键回滚。

网页无法可靠 iframe 预览多数官网，因为存在 CSP、X-Frame-Options 和跨域限制。实际效果应在盒子上验证，配置页面只负责静态校验和规则匹配测试。

### 9.2 动作 DSL 与高级 JS

常见适配优先使用结构化动作：

- 查找 video 或播放器容器。
- 点击播放/全屏按钮。
- 隐藏页面元素。
- 修改属性、class 和 `object-fit`。
- 调用 `play()`、`requestFullscreen()`。
- 设置轮询、超时和重试。

高级 JS 作为特殊站点兜底保留。它必须绑定精确域名，并设置脚本体积、执行次数、轮询频率和总时限上限。Web 页面应明显标记高级模式的风险。

## 10. 页面加载状态机

```text
SELECT_CHANNEL
  -> LOAD_ENTRY
  -> NAVIGATING
  -> RULE_MATCHED
  -> BOOTSTRAP_INJECTED
  -> DOM_PROBING
  -> TARGET_FOUND
  -> PLAY_ATTEMPT
  -> PLAYING
  -> FULLSCREEN_ACTIVE
```

关键行为：

1. 每次顶层跳转创建新的 `navigationId`，旧页面事件立即失效。
2. 重定向后重新匹配最终 URL，越出白名单就停止注入。
3. 优先 document-start 注入；不支持时退化到 `onPageCommitVisible/onPageFinished`。
4. 脚本必须幂等，以 `MutationObserver` 和有上限轮询等待动态播放器。
5. 主页面明确加载失败时切换同频道的下一个站源。
6. WebView renderer 崩溃时按加载失败处理；完整心跳监督后置。

## 11. 自动播放和全屏

### 11.1 自动播放

- 设置 `mediaPlaybackRequiresUserGesture=false`。
- 规则查找 video/播放器并调用 `play()` 或点击播放按钮。
- 网站拒绝自动播放时尝试静音播放。
- 仍需要可信手势时，在屏幕提示“按确认键播放”，由真实遥控器事件触发。

不能承诺所有网站 100% 无操作播放，这是浏览器和网站播放器的限制。

### 11.2 全屏降级

1. 调用站点全屏按钮或 `requestFullscreen()`，由 `WebChromeClient.onShowCustomView` 接管。
2. 需要可信用户手势时，在确认键事件中点击全屏按钮。
3. 进入 CSS Cinema Mode：播放器 `position: fixed; inset: 0`，隐藏其他页面内容。

模拟 `F` 键、双击和特殊 DOM 操作只写入对应站点规则，不进入通用代码。

## 12. 遥控器交互

### 12.1 播放态

| 按键 | 行为 |
|---|---|
| MENU (`KEYCODE_MENU`) | 打开频道列表，事件不传给网页 |
| 上/下 | 直接切换上一/下一频道 |
| 左/右 | 切换上一/下一频道分组 |
| 确认 (`DPAD_CENTER/ENTER`) | 完成待播放/待全屏手势；无等待动作时显示频道列表 |
| BACK | 退出网页全屏/Cinema，之后关闭菜单，再次返回确认退出 |

### 12.2 频道列表态

| 按键 | 行为 |
|---|---|
| 上/下 | 移动频道焦点 |
| 左/右 | 切换频道分组 |
| 确认 (`DPAD_CENTER/ENTER`) | 播放当前选中频道并关闭列表 |
| MENU/BACK | 关闭频道列表 |

实现约束：

- Activity 层优先拦截 MENU、D-pad、CENTER/ENTER 和 BACK，网页不能抢走基础遥控器控制。
- 同时兼容 `KEYCODE_DPAD_CENTER` 和 `KEYCODE_ENTER`。
- 长按和重复按键节流，防止一次按键触发多次页面加载。
- 列表打开时焦点始终位于 Android 原生 View，不落入网页 DOM。
- 音量键交给 Android 系统。

### 12.3 触摸交互

- 播放态单击打开频道列表。
- 频道项单击立即播放并关闭列表，列表可上下滚动。
- 侧栏顶部显示所有启用分组的标签，触摸标签可直接切换分组。
- 上下滑动切换上一/下一频道，左右滑动切换上一/下一分组。
- 手势只观察不吞掉事件，网页播放器控件仍可正常点击。

## 13. JS 注入边界

MVP 采用 Android 向网页单向 `evaluateJavascript` 注入，不注册 `addJavascriptInterface`，也不提供网页到 Android 的通用 Bridge。自动播放、按钮点击、全屏请求和 CSS Cinema 全部在注入脚本内部完成。

这会放弃精细的播放心跳和页面事件上报，但显著减少依赖、代码量和远程脚本能够调用的原生能力。后续只有在真实站点验证证明必要时，才增加固定枚举、精确域名限制的最小消息通道。

## 14. WebView 安全基线

- 频道网页只允许 HTTPS；局域网配置页面可以使用 HTTP。
- SSL 错误必须取消，绝不忽略证书错误。
- 禁用 file/content access、file URL 跨域访问和 mixed content。
- Safe Browsing 开启；WebView debugging 只在 debug 构建开启。
- 摄像头、麦克风、定位、通知、下载、文件选择、多窗口和外部 scheme 默认关闭。
- 顶层导航按 profile 白名单检查，拒绝 `intent/file/content/javascript` 等 scheme。
- 重定向后再次校验域名，未匹配规则的页面不注入高级脚本。
- 日志不记录 Cookie、页面内容、完整 query token 或用户账号信息。

## 15. MVP 范围

### 15.1 包含

- Android 盒子单 Activity + System WebView。
- Android 5.0/API 21 起步，纯 Java APK 同时覆盖 `armeabi-v7a` 32 位 ARM 和 `arm64-v8a`；不包含 NDK/native `.so`。
- 本地 JSON 频道、分组、多站源和规则匹配。
- MENU 打开频道列表，完整 D-pad 和确认键交互。
- 声明式动作与高级 JS 注入。
- 原生 Custom View 和 CSS Cinema 全屏。
- 主页面加载失败后的备用站源切换。
- App 内嵌局域网 HTTP 服务并显示访问地址。
- 手机/电脑 Web 页面编辑、校验、应用、导入导出和回滚。
- factory/active/previous/history 本地配置保护。
- 隐藏诊断面板。

### 15.2 不包含

- 外部配置服务器、Docker、数据库和云服务。
- 用户、登录、权限、审批和多人协作。
- 视频流解析、中转、转码或 DRM 处理。
- X5/GeckoView 多内核。
- 登录账号同步、远程设备控制和 App 自更新。

### 15.3 验收标准

- 全新安装和断网启动时可以读取 factory/active 配置。
- 错误 Schema、非法 URL、冲突规则和 JS 语法错误不能应用。
- 应用配置失败不会破坏当前 active 配置。
- 可从手机/电脑访问盒子的 Web 配置页面并完成频道新增。
- Web 页面应用配置后 App 能自动重启并加载新的 active 版本。
- MENU、D-pad、CENTER/ENTER 和 BACK 在目标遥控器上行为一致。
- 规则只在声明域名运行，重定向越界后停止注入。
- 目标站点可以自动或通过一次确认键进入电视全屏体验。
- 单站规则失败不会导致其他频道不可用。
- 可以一键回滚到上一配置版本。

## 16. 实施顺序

### 阶段 A：浏览器与遥控器验证

- 建立最小 WebView Host、Custom View 和 CSS Cinema。
- 使用本地测试页验证自动播放、动态 video 和全屏降级。
- 完成 MENU、D-pad、CENTER/ENTER、BACK 和焦点路由。
- 在目标盒子上验证 System WebView。

### 阶段 B：配置运行时

- 建立 JSON Schema、ConfigStore、规则匹配和动作 DSL。
- 完成页面状态机、单向脚本注入和备用站源恢复。
- 使用本地配置适配 3-5 个首批测试站点。

### 阶段 C：局域网 Web 配置

- 嵌入 HTTP Server，提供 REST API 和静态资源。
- 完成 IP 地址展示和固定端口服务。
- 完成 Web Console 的频道、规则、脚本、校验和匹配测试页面。
- 完成 draft、apply、history、import/export 和 rollback。

### 阶段 D：稳定性与交付

- 补充配置损坏、弱网、断网、renderer crash 和脚本超时测试。
- 完成目标设备/站点回归矩阵和脱敏诊断。
- 根据实测结果决定是否增加 X5 兼容构建。

## 17. 主要风险

| 风险 | 应对 |
|---|---|
| 网站 DOM/播放器改版 | 局域网规则编辑、动作 DSL、历史版本和回滚 |
| 自动播放需要用户手势 | 确认键触发，CSS Cinema 兜底 |
| 跨域 iframe 无法访问内部 video | 使用站点按钮/网页自身全屏；无法适配时标记不支持 |
| 老盒子 WebView 不兼容 | 设最低版本，实测后再决定 X5 构建 |
| 高级 JS 配置错误 | 精确域名、静态校验、超时、active/previous 回滚 |
| 无登录配置端口被访问 | 仅可信 LAN、可关闭服务、禁止公网端口映射 |
| App 退出后无法配置 | 配置时保持 App 前台，设置页显示服务状态和地址 |
| 第三方网站使用限制 | 不绕过登录、付费、广告、地域或 DRM 限制 |

## 18. 开发中仍需补充

1. **最低系统**：Android 5.0/API 21 起步；更老系统不在支持范围内。
2. **浏览器内核**：MVP 是否只做 System WebView？推荐是，X5 后置。
3. **脚本模式**：是否接受 DSL 优先，同时保留高级 JS？推荐接受。
4. **首批站点**：需要提供 3-5 个具体官网频道 URL 做真实站点验证。
5. **登录站点**：MVP 默认排除登录、验证码、付费和 DRM 网站。
6. **分发方式**：当前按自用侧载设计；如计划进入应用商店需补充合规检查。
7. **左右键含义**：当前定义为切换频道分组；后续可按实机习惯调整。

当前先按推荐默认值完成可安装 MVP，再结合目标盒子和首批站点做兼容调整。
