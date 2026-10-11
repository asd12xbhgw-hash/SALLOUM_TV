# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the app's data model so Gson reflection keeps working under R8.
-keep class com.salloum.tv.data.** { *; }

# Media3 ships its own consumer rules; these keep the core player classes safe.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# If you keep the line number information, uncomment to hide the original source file name.
#-renamesourcefileattribute SourceFile