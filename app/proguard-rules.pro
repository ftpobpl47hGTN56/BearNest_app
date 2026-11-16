# libxray gomobile bindings
-keep class libxray.** { *; }
-keep class go.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
