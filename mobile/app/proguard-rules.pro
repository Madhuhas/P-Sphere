# R8 rules for release builds.

# Gson fills these classes by field name through reflection. If R8 renames or strips the
# fields, every server response deserialises to nulls, so keep the network models as-is.
-keep class com.psphere.network.** { *; }
-keepclassmembers class com.psphere.network.** { <fields>; <init>(...); }

# Generic signatures and annotations are needed by Retrofit and Gson at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# Retrofit service interface (suspend functions return types are read reflectively).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# WorkManager instantiates the worker by class name.
-keep class com.psphere.sync.SyncWorker { <init>(...); }

# Keep line numbers so crash reports from release builds are readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
