


















-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi





-keepclassmembers class com.tianercode.opencode.bridge.OpenCodeBridge {
    @android.webkit.JavascriptInterface <methods>;
}




-keep class com.tianercode.opencode.bridge.OpenCodeBridge { *; }
-keep interface com.tianercode.opencode.bridge.OpenCodeBridge$Host { *; }





-dontwarn com.google.android.gms.**



-dontwarn org.mozilla.thirdparty.**
