# Release 打包（ProGuard）规则。
# Compose Desktop 已由插件注入 Skiko/Compose/Coroutines 的基础规则，这里补齐第三方库。

# OkHttp 会按运行平台反射加载 Conscrypt / BouncyCastle / OpenJSSE / Android 实现，
# 桌面端没有这些可选依赖，ProGuard 不应因此判定构建失败。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA 通过反射绑定本地方法/结构体（FileKit 的 Windows 文件对话框依赖）。
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
# FileKit 的 JNA 接口/结构体同样按签名反射绑定，保留类名与成员。
-keep class io.github.vinceglb.filekit.** { *; }
