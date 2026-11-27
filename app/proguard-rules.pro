# Keep line numbers so release crash stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp, WorkManager, Media3 and Play services ship their own consumer rules.
# org.json is part of Android, so nothing else is needed here.