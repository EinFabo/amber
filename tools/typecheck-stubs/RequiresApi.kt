// Stub for typecheck-android.sh only - never compiled into the app. Mirrors the real
// androidx.annotation.RequiresApi closely enough for call sites to resolve.
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)
