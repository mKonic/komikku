package mihon.telemetry

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

object TelemetryConfig {
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context, isPreviewBuildType: Boolean, commitCount: String) {
        // To stop forks/test builds from polluting our data
        if (!context.isMihonProductionApp()) return

        FirebaseApp.initializeApp(context)
        crashlytics = FirebaseCrashlytics.getInstance()
        // KMK -->
        // Crash reports only: analytics is not collected. The preview build's commit count goes on each report
        // instead of an analytics user property, so a preview crash still says which build it came from.
        if (isPreviewBuildType) {
            crashlytics?.setCustomKey("preview_version", commitCount)
        }
        // KMK <--
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isMihonProductionApp(): Boolean {
        if (packageName !in MIHON_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == MIHON_CERTIFICATE_FINGERPRINT }
    }
}

private val MIHON_PACKAGES = hashSetOf("app.komikku", "app.komikku.beta")

// KMK: this fork's release key (alias komikku-pf), which signs both release and CI preview builds
private const val MIHON_CERTIFICATE_FINGERPRINT =
    "69:EE:01:C0:4F:9A:60:52:F1:E2:28:56:5E:83:8D:B2:30:0F:D1:41:16:03:B3:37:C7:34:97:F2:3E:63:53:C0"
