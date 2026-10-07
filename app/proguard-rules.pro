-keep class com.example.dicepredictor.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

-keepattributes *Annotation*
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
