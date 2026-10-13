# Strip all android.util.Log calls from release builds. Logging goes through tv.tube.TLog,
# which is itself a no-op in release, but this removes any stray direct calls too.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Keep crash stack traces readable without leaking source paths.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class tv.tube.**$$serializer { *; }
-keepclassmembers class tv.tube.** { *** Companion; }
-keepclasseswithmembers class tv.tube.** { kotlinx.serialization.KSerializer serializer(...); }

# XmlPull interfaces come from the Android framework at runtime.
-dontwarn org.xmlpull.**
-dontwarn org.kxml2.**
