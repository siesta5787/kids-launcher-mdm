# Add project specific ProGuard rules here.
-dontobfuscate
-dontoptimize
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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

# see app/build/outputs/mapping/release/missing_rules.txt
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedAnnotationTypes
-dontwarn javax.annotation.processing.SupportedSourceVersion

# dnsjava (KidVpnService's DNS message parsing) references lombok's compile-time-only @Generated
# annotation and an optional slf4j logging binding this app doesn't include - neither has any
# runtime footprint we need, dnsjava just no-ops logging without a real slf4j binding present.
-dontwarn lombok.Generated
-dontwarn org.slf4j.**
