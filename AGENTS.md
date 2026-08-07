# Agent Instructions

# WebTvShell AI 维护说明

后续 AI 修改本项目之前必须先阅读：

1. `docs/SOURCE_RULE_GUIDE.md`
2. `PROJECT_PLAN.md`
3. 与任务直接相关的 Java/JSON/Web 文件

## 项目目标

WebTvShell 是最小化 Android TV 网页直播壳：System WebView 播放频道官网，App 内嵌局域网配置服务。保持单模块、纯 Java、零运行时第三方依赖。

## 不可破坏的边界

- 不实现视频流解析、中转、转码、抓取或 DRM 绕过。
- 不增加独立后端、数据库、账号权限、Docker 或云服务。
- 不随意增加依赖、Compose、X5、GeckoView 或媒体 SDK。
- 不忽略 SSL 错误，不开放 file/content access，不添加通用 `addJavascriptInterface`。
- 不把站点域名、频道 URL、DOM id/class 或站点专用脚本写进 Java。
- Java 只承载通用能力；站点差异必须进入 JSON profile/script。
- 配置服务保持局域网无账号模式，不擅自扩展为公网服务。

## 当前架构

- `MainActivity.java`：Activity 生命周期、频道侧栏、遥控器和触摸。
- `BrowserController.java`：System WebView、注入、播放、原生全屏和通用 CSS Cinema。
- `ConfigRepository.java`：配置校验、原子写入、版本和回滚。
- `LanConfigServer.java`：端口 `6688` 的最小 HTTP 服务。
- `model/`：配置运行时模型和 profile 匹配。
- `assets/default_config.json`：出厂站源、频道和脚本。
- `assets/www/`：零框架 Web 配置页面。

默认配置的 `satellite`（“卫视频道”）分组包含央视频页面当前列出的 32 个卫视频道；原山东、安徽、东南、江西、湖北、广西地方官网入口保留在 `satellite-external`（“其他站源”）分组。新增卫视前应先按 `docs/SOURCE_RULE_GUIDE.md` 完成页面和视频节点实测。

## 配置修改规则

- 修改配置模型时必须同步检查五处：运行时解析、校验器、Web 编辑器、默认 JSON、维护文档。
- `default_config.json` 只影响全新应用数据；真机已有 active 配置必须经 `6688` API 应用。
- `profileId` 不是无条件覆盖，profile 仍须匹配最终 URL host/path。
- `pathRegex` 建议使用 `^`/`$` 锚点。
- 站点 JS 必须幂等、异常隔离、轮询有上限。
- 不声称 `navigationAllowlist`、source timeout、script phase/hosts/timeout 已完整执行；当前限制见维护指南第 8 节。

## UI 输入约束

- MENU 或播放页单击打开频道列表。
- 频道列表顶部必须显示所有启用分组的可触摸标签，不能只靠隐藏的左右键发现其他分组。
- 播放态上下切频道、左右切分组。
- 菜单态上下移动、左右切分组、CENTER/ENTER 确认。
- 菜单浏览分组和当前播放分组必须分离；未确认就关闭菜单时不能改变当前播放分组。
- channel `order` 是组内排序，管理页必须按 group `order` 分段后再按 channel `order` 展示。
- 频道项必须支持触摸单击直接切台，不能要求先聚焦再点第二次。
- 触摸手势不得吞掉 WebView 和 ScrollView 的正常点击/滚动。
- BACK 优先关闭菜单，其次退出全屏，再退出 Activity。

## 全屏约束

- 优先尝试配置中的站点按钮和视频 Fullscreen API。
- 必须保留通用 CSS Cinema 回落。
- 通用影院代码不能包含 `#player`、`#h5player_player` 等站点命名。
- 站点特有的视频、播放键和全屏键 selector 只放 profile/script。
- 动态播放器可能覆盖样式，通用影院模式需能持续维持并在退出时完整恢复 DOM。

## 配置服务约束

- 服务监听 `6688`，随 Activity 生命周期运行，并在 `onDestroy()` 中停止。
- 配置端口必须避开 Chromium 受限端口，尤其不能使用 IRC 端口段 `6665-6669`，否则浏览器会报 `ERR_UNSAFE_PORT`。
- apply/rollback 响应发送完成后重建 Activity，不杀进程、不依赖精确闹钟。
- 停服与线程池关闭存在并发边界；不得移除 `RejectedExecutionException` 防护。
- 修改 restart/stop/accept 流程后必须真机执行一次 apply 并确认无崩溃。

## 构建环境与打包

- 使用 JDK 17。当前 Windows 开发机默认 `java` 可能是 JDK 25，构建前必须显式设置 `JAVA_HOME` 为 JDK 17。
- 需要 Android SDK Platform 33 和 Android Build Tools 34 或更高版本；当前项目使用 Gradle Wrapper 8.5、Android Gradle Plugin 8.2.0、`compileSdk 33`、`minSdk 21`（Android 5.0）、`targetSdk 33`。
- 真机安装和调试需要 Android SDK Platform-Tools（`adb`）。
- App 纯 Java、没有 `.so`/NDK 代码，同一个 APK 支持 `armeabi-v7a`（32 位 ARM）和 `arm64-v8a`；设备仍需提供可用的系统 WebView。
- Android 5 会加载的类不得在字段或方法签名中直接引用 API 23+ 类型；`WebResourceError` 和 renderer 回调必须继续隔离在 `Api23Client` / `Api26Client`。
- 通用注入脚本需兼容早期 Chromium：不要改回 `NodeList.forEach`、仅 CSS `inset` 或 `Element.isConnected`；站点自身是否支持旧 WebView 仍需真机确认。
- 当前开发机环境为 `JAVA_HOME=D:\Program Files\Java\jdk-17`、Android SDK 为 `D:\Android\Sdk`。其他机器可使用自己的同等版本 SDK，并在 `local.properties` 设置 `sdk.dir`。
- 不需要 Node/npm、Docker、数据库或运行时第三方依赖；`debugRuntimeClasspath` 应报告 `No dependencies`。

Windows PowerShell 构建与依赖检查：

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-17'
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

真机安装命令：

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.webtvshell.app
adb shell am start -n com.webtvshell.app/.MainActivity
```

## 必做验证

涉及站源、WebView、全屏、输入或配置服务时，还必须在 ADB 设备上验证：

- APK 安装和启动。
- `http://盒子IP:6688/api/status` 可访问。
- 视频真实解码播放和纯视频全屏。
- 遥控器、触摸、频道滚动和单击切台。
- 配置 apply 后 active 版本递增，Activity 重建且无 `AndroidRuntime` 崩溃。

当前已验证基线：Android 14/API 34、arm64、System WebView 116、央视直播页面。站点实时可用性仍受网站改版、节目版权、地区和网络影响。

## 修改纪律

- 工作区可能有用户未提交改动，禁止 reset/checkout 覆盖。
- 保持修改范围小，不做无关重构和格式化。
- 新增能力前先确认现有 JSON/Java/Web 模式能否覆盖。
- 行为改变后更新 `README.md`、`PROJECT_PLAN.md` 或 `docs/SOURCE_RULE_GUIDE.md` 中对应事实。
- 最终说明必须区分：代码构建通过、模拟验证通过、真机验证通过。
