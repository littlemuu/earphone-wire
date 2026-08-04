# 耳机线 Android 手机探测器

第二关的第一阶段只在 Mate 30 本机读取 QQ 音乐的活动媒体会话，不连接 Cloudflare，也没有联网权限。

## 打开工程

在 Android Studio 欢迎页选择 **Open**，打开本 `android` 目录。首次同步会下载 Gradle 与 Android Gradle Plugin。

工程要求：

- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools
- JDK 17（使用 Android Studio 自带的 JDK 即可）

## 真机验收

1. 在 Mate 30 打开开发者选项与 USB 调试。
2. 用 USB 数据线连接电脑，手机上同意调试授权。
3. 在 Android Studio 选择 Mate 30，运行 `app`。
4. 打开耳机线 App，点击“去授予通知使用权”，在系统页面开启“耳机线”。华为/Honor 设备会优先打开通知使用权列表；其他设备会先尝试本应用详情页，再回退到列表和普通设置。若系统页面未打开，请在手机设置中搜索“通知使用权”，然后开启“耳机线”。
5. 在 QQ 音乐播放一首歌，返回耳机线，点击“读取当前播放”。

通过标准：手机屏幕显示真实歌名、歌手、播放状态和应用包名 `com.tencent.qqmusic`。

## 权限边界

Android 的 `MediaSessionManager.getActiveSessions()` 要求应用拥有系统级媒体控制权限，或者成为用户明确启用的通知监听服务。本工程使用后者，但不实现通知回调、不读取通知正文。Manifest 中也没有网络、存储、麦克风、无障碍或截屏权限。
