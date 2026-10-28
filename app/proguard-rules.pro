# Settings are (de)serialised by kotlinx.serialization; keep the generated serializers.
-keepclassmembers class app.amethyst.core.settings.** {
    *** Companion;
}
-keepclasseswithmembers class app.amethyst.core.settings.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# whisper_jni.cpp looks these up by name.
-keep class app.amethyst.core.engine.whisper.WhisperRuntimeException { <init>(java.lang.String); }
-keepclasseswithmembernames class app.amethyst.engine.WhisperNative { native <methods>; }
