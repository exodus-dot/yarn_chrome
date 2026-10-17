# Yarn 浏览器

带域名白名单的 Android 浏览器，支持手机与平板（minSdk 24）。

## 功能

- 白名单：只放行列表内域名及其子域名；非白名单的跳转、重定向、弹窗被拦截，页面停留在当前位置。列表第一项作为首页。
- 全屏：工具栏按钮，或外接键盘 F11 / Ctrl+Shift+F；Esc、返回键退出。
- 刷新：工具栏按钮，或键盘 F5 / Ctrl+R。
- 全屏下显示低透明度悬浮按钮（刷新、退出全屏），可拖动到不挡内容的位置。

## 打包

推送到 `main` 分支或手动触发 Actions 的 `Build APK`，在运行结果的 Artifacts 下载 `yarn-chrome-apk`。
每次构建成功后，APK 自动发布到仓库的 Releases：`main` 分支推送生成 `build-<序号>`，推送 `v*` 标签（如 `v1.0.0`）则以该标签命名。

APK 使用仓库内的 `app/debug.keystore` 签名，每次构建签名一致，可直接覆盖安装。
上架应用商店前需要换成正式密钥库。

## 页面卡死检测与崩溃恢复

两个平台行为一致：

- 每 3 秒往页面主线程投一条空脚本，回调延迟就是页面的响应延迟。连续 3 次超过 1.5 秒，自动重载当前页。
- 一条探测 10 秒不返回，判定渲染进程挂死，销毁并重建 WebView，回到刚才的页面。Cookie 保存在进程级存储里，登录状态不丢。
- 渲染进程被系统回收或崩溃时不再带崩整个应用，自动重建；10 秒内再次崩溃则退回首页，避免死循环。
- 页面加载中、视频全屏、加载完成后 5 秒内不检测；两次恢复之间至少间隔 30 秒。
- Android 额外把渲染进程优先级设为 important，低内存时系统更晚回收它，应用回到前台立即体检一次。
- Windows 额外通过 WebView2 启动参数关闭后台与被遮挡窗口的节流，页面持续渲染时不会被降频。

## 实时页面延迟（Android）

针对持续接收推送数据的页面（监控大屏、数字孪生）：页面处理消息跟不上推送时，画面会越来越落后于现场，刷新后才恢复。以下功能默认关闭。

- 诊断模式：菜单里开关，只在本次运行有效，开关时当前页会重新加载。页面脚本运行前包装 WebSocket / EventSource 的消息回调，只计时，不改参数、返回值和异常；每秒在右上角浮层显示，同时写入 CSV。
  - 采集项：连接数、消息数/秒、数据量/秒（文本按字符数计）、回调耗时、帧率、长任务、事件循环延迟、JS 堆，以及从消息里的时间戳算出的载荷延迟和"较打开时"多落后的量。
  - 不采集网址、域名、消息内容和字段名。时间戳只在页面内解析，上报的只有延迟数值。
  - 上报周期明显超过 1 秒、回调耗时接近 1000 ms/秒，说明主线程已经被消息处理占满，延迟会持续累积。
  - 日志位置：`Android/data/com.exodus.yarnchrome/files/diag/diag-<时间>.csv`（UTF-8 带 BOM），USB 连接电脑后可直接拷出。每次刷新、追帧、卡死恢复都会记一行事件，方便对照。
  - 统计不到：在 Web Worker 里收的消息、fetch / XHR 轮询。
- 按延迟自动追帧（设置页）：阈值可选 2 / 5 / 10 秒。以本次打开后最小的载荷延迟为基准（抵消两端时钟差），比基准多落后超过阈值、连续 3 秒，就重新加载；两次之间至少间隔 30 秒。消息里没有可识别的时间戳时不生效，开启后会提示一次。
- 渲染分辨率上限（设置页）：可选不超过 1.5 倍或 1 倍。压低页面读到的 `devicePixelRatio`，three.js、Unity WebGL 等按它确定画布分辨率，每帧绘制量成倍减少；CSS 布局和网页文字不受影响。
- 依赖系统 WebView 支持 `DOCUMENT_START_SCRIPT` 与 `WEB_MESSAGE_LISTENER`（较新的 Android System WebView 都支持），不支持时会提示。

## Windows 版

位于 `windows/`，WebView2 + .NET 8 WinForms，支持 Windows 10 和 11（x64）。功能与 Android 版一致：白名单、书签与快捷方格、定时刷新、全屏（F11 / Ctrl+Shift+F）、刷新（F5 / Ctrl+R）、Ctrl+L 定位地址栏、屏幕常亮。

- 数据保存在 `%APPDATA%\YarnChrome\data.json`，浏览器缓存与 Cookie 在 `%APPDATA%\YarnChrome\webview`。
- 需要系统已安装 WebView2 运行时：Windows 11 自带，Windows 10 随新版 Edge 安装，缺失时启动会提示。
- 推送到 `main` 且改动涉及 `windows/`，或推送 `v*` 标签，Actions 的 `Build Windows` 会生成 `yarn-chrome-windows-x64-build<序号>.zip`，同时发布到 Releases（标签名 `windows-build-<序号>`）。解压后运行 `YarnChrome.exe`。
- 本地构建需要 .NET 8 SDK：`dotnet publish windows/YarnChrome.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o publish`

## 本地构建

需要 JDK 17、Android SDK 34 与 Gradle 8.9：

```
gradle assembleRelease
```
