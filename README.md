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

## 本地构建

需要 JDK 17、Android SDK 34 与 Gradle 8.9：

```
gradle assembleRelease
```
