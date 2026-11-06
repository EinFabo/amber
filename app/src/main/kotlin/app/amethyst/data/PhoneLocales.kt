package app.amethyst.data

import android.content.res.Resources

/** The languages set on the phone, for the defaults in [app.amethyst.core.settings.PhoneLanguages]. */
object PhoneLocales {

    /**
     * BCP-47 tags, most preferred first. The system's list rather than LocaleList.getDefault():
     * a per-app language picked for Amber's screens says nothing about what the user speaks.
     */
    fun tags(): List<String> {
        val locales = Resources.getSystem().configuration.locales
        return List(locales.size()) { locales[it].toLanguageTag() }
    }
}
