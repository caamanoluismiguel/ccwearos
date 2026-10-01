# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- CCWEAROS --------------------------------------------------------------
# RTDB models. Reads go through hand-written RtdbMappers (no reflection), but
# keep the classes and their no-arg constructors / getters intact in case any
# code path ever uses DataSnapshot.getValue(Model::class.java) again: the
# Firebase CustomClassMapper resolves fields by reflective name.
-keep class com.caamano.ccwearos.data.Metrics { *; }
-keep class com.caamano.ccwearos.data.PendingCommand { *; }
-keep class com.caamano.ccwearos.data.ClaudeStatus { *; }
-keep class com.caamano.ccwearos.data.ToolEvent { *; }
-keep class com.caamano.ccwearos.data.SharedSessionMeta { *; }
-keep class com.caamano.ccwearos.data.RecentSession { *; }
-keep class com.caamano.ccwearos.data.PendingClaim { *; }
-keep class com.caamano.ccwearos.data.ClaimResult { *; }

# Readable crash stacks.
-keepattributes SourceFile,LineNumberTable
