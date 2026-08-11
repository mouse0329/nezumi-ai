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

# Suppress R8 warnings for Kotlin metadata from newer Kotlin versions.
-dontwarn kotlin.Metadata

# ============================================================================
# ONNX Runtime - Keep all classes to prevent NoSuchMethodError
# ============================================================================
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ============================================================================
# Google Play Services TFLite - Keep required classes
# ============================================================================
-keep class com.google.android.gms.tflite.** { *; }
-dontwarn com.google.android.gms.tflite.**

# ============================================================================
# Kotlin Serialization - Required for kotlinx-serialization-json
# ============================================================================
-keep class kotlin.serialization.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# ============================================================================
# Keep model classes parsed by Gson to prevent R8 from making them
# abstract/obfuscated in a way that breaks reflection-based deserialization.
# Adjust if you add other Gson-parsed data classes in different packages.
# ============================================================================
# Keep nested data classes inside ImageModelBrowser (TreeEntry, LfsInfo)
-keep class com.nezumi_ai.data.inference.ImageModelBrowser$* { *; }
# VOICEVOX Coreなどが参照している missing class の警告を無視
-dontwarn jakarta.annotation.**
-dontwarn javax.annotation.**

# ============================================================================
# Apache POI (Word/Excel生成) および付随ライブラリが参照する
# デスクトップ限定・オプション機能への参照。Android上では到達しない
# コードパスのため、クラス欠落を許容してR8を通す。
# ============================================================================
# java.awt.* : POIのセル幅計算やグラフィック描画で参照されるが、
# Android の帳票/変換用途では実際には呼ばれない。
-dontwarn java.awt.**
# OSGi (log4jのバンドル検出、実行時は使用されない)
-dontwarn org.osgi.framework.**
# bnd / findbugs のビルド時アノテーション (実行時には不要)
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
# Saxon (xmlbeansのオプションXPathエンジン。同梱していないため未使用)
-dontwarn net.sf.saxon.**
# StAX (javax.xml.stream) 実装。標準APIだがAndroidのブートクラスパスには
# 含まれないため、xmlbeans経由の参照を許容する。
-dontwarn javax.xml.stream.**
# JPEG2000 (JP2) コーデック。pdfbox の JPXFilter がオプション参照するのみ。
-dontwarn com.gemalto.jp2.**