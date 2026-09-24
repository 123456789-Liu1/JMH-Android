# JMH 混淆规则
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# 保留 Media3 播放器相关（部分通过反射/动态加载使用）
-dontwarn androidx.media3.**

# 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 加密相关类不需要混淆（避免混淆后调试困难）
-keep class com.jmh.app.crypto.** { *; }
