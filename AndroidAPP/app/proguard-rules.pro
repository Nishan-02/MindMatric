# ==== MindMetrics ProGuard Rules ====
# Keep the JS interface class so WebView can call Android functions

-keepclassmembers class com.mindmetrics.app.MainActivity$AndroidBridge {
    public *;
}

# Keep the class name so `addJavascriptInterface()` still works
-keep class com.mindmetrics.app.MainActivity$AndroidBridge

# Keep annotations used for bridging
-keepattributes *Annotation*

# Preserve line numbers (optional but recommended for debugging)
-keepattributes SourceFile,LineNumberTable
