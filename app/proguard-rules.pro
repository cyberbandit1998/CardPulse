# R8 rules for CardPulse. Retrofit, OkHttp, kotlinx.serialization, Coil, CameraX, DataStore and Compose ship their own;
# these are only what this app needs on top of them.

# Keep the names. A phone-only app has nowhere to send a mapping file, so a crash has to be readable as it is, and
# renaming saves only a few percent once the unused code is gone.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The framework creates ViewModels by reflection, through these constructors.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }

# Every shape the server's JSON is read into or written from. Their serializers are found by lookup, so none of it
# may be trimmed (there are a few dozen small classes, so keeping them whole costs next to nothing).
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class app.cardpulse.android.** { *; }

# Coil finds its OkHttp network fetcher through a service file (META-INF/services), which is easy to lose when shrinking.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
