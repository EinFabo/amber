// Stub for typecheck-android.sh only - never compiled into the app. Mirrors the real
// androidx.annotation.StringRes closely enough for call sites to resolve.
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class StringRes
