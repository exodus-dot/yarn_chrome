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
