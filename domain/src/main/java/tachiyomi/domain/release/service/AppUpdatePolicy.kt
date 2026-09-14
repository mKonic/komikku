package tachiyomi.domain.release.service

class AppUpdatePolicy {
    companion object {
        const val DEVICE_ONLY_ON_WIFI = "wifi"
        const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
        const val DEVICE_CHARGING = "ac"
        const val DISABLE_AUTO_DOWNLOAD = "disable"

        // KMK -->
        /** How many days to leave between update checks. Zero checks on every app launch. */
        const val CHECK_INTERVAL_KEY = "app_update_check_interval"
        const val CHECK_INTERVAL_DEFAULT = 2
        // KMK <--
    }
}
