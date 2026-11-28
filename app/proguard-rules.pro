-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.digifello.compressorfordrive.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

-dontoptimize
-dontobfuscate