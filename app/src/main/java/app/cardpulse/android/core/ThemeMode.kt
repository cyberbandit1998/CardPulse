package app.cardpulse.android.core

/** Which colours the app is drawn in: always light, always dark, or whatever the phone is set to. */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    SYSTEM("system", "Same as phone");

    /** Whether to draw the dark colours, given whether the phone itself is set to dark. */
    fun isDark(phoneIsDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> phoneIsDark
    }

    companion object {
        /** What the app looked like before there was a choice, so nothing changes until the user picks something. */
        val DEFAULT = DARK

        /** The mode saved under [key]; a missing or unknown value (an older or newer version of the app wrote it) is the default. */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
