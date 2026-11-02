# kotlinx.serialization：保留序列化器（库自带规则已覆盖大部分情况，这里补充导航路由等用到的类）
-keepattributes *Annotation*, InnerClasses
-keepattributes SourceFile,LineNumberTable
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class isomeria.aichat.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# MMKV 通过 JNI 回调
-keep class com.tencent.mmkv.** { *; }

# release 包移除 android.util.Log 的 v/d/i 调用（日志只在 debug 包输出）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
