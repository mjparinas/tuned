# Room, Media3, WorkManager, OkHttp and kotlinx.serialization ship their own keep rules.
# Navigation routes are @Serializable classes; the serialization plugin's rules keep their serializers.
-dontwarn org.xmlpull.v1.**
