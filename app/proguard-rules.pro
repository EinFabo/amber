# Settings are (de)serialised by kotlinx.serialization; keep the generated serializers.
-keepclassmembers class app.nocturne.core.settings.** {
    *** Companion;
}
-keepclasseswithmembers class app.nocturne.core.settings.** {
    kotlinx.serialization.KSerializer serializer(...);
}
