package app.harvester.companion.settings.privacy

import android.content.Context
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.components.Components
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.CookieBannerHandlingMode
import mozilla.components.concept.engine.EngineSession.SafeBrowsingPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.CookiePolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory

object PrivacyEngineSync {

    fun applyPreset(ctx: Context, components: Components?, preset: String) {
        SettingsPrefs.setString(ctx, SettingsPrefs.K_PRIVACY_LEVEL, preset)
        when (preset) {
            SettingsPrefs.PRIVACY_STANDARD -> {
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_ADS, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_HTTPS, false)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, false)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_SCRIPT, false)
                SettingsPrefs.setInt(ctx, SettingsPrefs.K_PG_COOKIES, 1)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_SAFE_BROWSING, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_DNT, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_GPC, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_DEAMP, false)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_SECURE_DNS, false)
                SettingsPrefs.setInt(ctx, SettingsPrefs.K_WEBRTC_POLICY, 0)
            }
            SettingsPrefs.PRIVACY_STRICT -> {
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_ADS, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_HTTPS, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_SCRIPT, true)
                SettingsPrefs.setInt(ctx, SettingsPrefs.K_PG_COOKIES, 1)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_SAFE_BROWSING, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_DNT, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_GPC, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_DEAMP, true)
                SettingsPrefs.setBool(ctx, SettingsPrefs.K_ADV_SECURE_DNS, true)
                SettingsPrefs.setInt(ctx, SettingsPrefs.K_WEBRTC_POLICY, 1)
            }
        }
        applyAll(ctx, components)
    }

    fun applyAll(ctx: Context, components: Components?) {
        try {
            val blockAds = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_ADS, false)
            val forceHttps = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_HTTPS, false)
            val antiFingerprint = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, false)
            val cookieChoice = SettingsPrefs.getInt(ctx, SettingsPrefs.K_PG_COOKIES, 0)
            val safeBrowsing = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_SAFE_BROWSING, false)
            val gpc = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_GPC, false)
            val secureDns = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_SECURE_DNS, false)

            val cookiePolicy = when (cookieChoice) {
                1 -> CookiePolicy.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                else -> CookiePolicy.ACCEPT_ALL
            }

            val categories = mutableListOf<TrackingCategory>()
            if (blockAds) {
                categories.add(TrackingCategory.AD)
                categories.add(TrackingCategory.ANALYTICS)
                categories.add(TrackingCategory.SOCIAL)
                categories.add(TrackingCategory.CRYPTOMINING)
                categories.add(TrackingCategory.EMAIL)
            }
            if (antiFingerprint) {
                categories.add(TrackingCategory.FINGERPRINTING)
            }

            val tpPolicy = if (categories.isEmpty() && cookieChoice == 0) {
                TrackingProtectionPolicy.none()
            } else {
                val cats = if (categories.isEmpty()) {
                    arrayOf(TrackingCategory.CRYPTOMINING)
                } else {
                    categories.toTypedArray()
                }
                TrackingProtectionPolicy.select(
                    trackingCategories = cats,
                    cookiePolicy = cookiePolicy,
                    cookiePolicyPrivateMode = CookiePolicy.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
                    strictSocialTrackingProtection = blockAds,
                    cookiePurging = blockAds
                )
            }

            components?.engine?.settings?.let { settings ->
                settings.trackingProtectionPolicy = tpPolicy
                settings.httpsOnlyMode = if (forceHttps) {
                    Engine.HttpsOnlyMode.ENABLED
                } else {
                    Engine.HttpsOnlyMode.DISABLED
                }
                settings.globalPrivacyControlEnabled = gpc
                settings.fingerprintingProtection = antiFingerprint
                settings.fingerprintingProtectionPrivateBrowsing = antiFingerprint
                settings.safeBrowsingPolicy = if (safeBrowsing) {
                    arrayOf(SafeBrowsingPolicy.RECOMMENDED)
                } else {
                    arrayOf(SafeBrowsingPolicy.NONE)
                }
                settings.dohSettingsMode = if (secureDns) {
                    Engine.DohSettingsMode.INCREASED
                } else {
                    Engine.DohSettingsMode.DEFAULT
                }
                if (secureDns) {
                    settings.dohProviderUrl = "https://mozilla.cloudflare-dns.com/dns-query"
                }
                settings.cookieBannerHandlingMode = if (blockAds) {
                    CookieBannerHandlingMode.REJECT_OR_ACCEPT_ALL
                } else {
                    CookieBannerHandlingMode.DISABLED
                }
                settings.cookieBannerHandlingModePrivateBrowsing = CookieBannerHandlingMode.REJECT_OR_ACCEPT_ALL
            }

            components?.runtime?.settings?.let { rtSettings ->
                try {
                    rtSettings.setGlobalPrivacyControl(gpc)
                    rtSettings.setFingerprintingProtection(antiFingerprint)
                    rtSettings.setFingerprintingProtectionPrivateBrowsing(antiFingerprint)
                    rtSettings.setTrustedRecursiveResolverMode(if (secureDns) 2 else 0)
                    if (secureDns) {
                        rtSettings.setTrustedRecursiveResolverUri("https://mozilla.cloudflare-dns.com/dns-query")
                    }
                } catch (_: Exception) {}
            }

            components?.store?.state?.tabs?.forEach { tab ->
                try {
                    tab.engineState.engineSession?.updateTrackingProtection(tpPolicy)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            UHLog.w("privacy", "Engine privacy sync failed: ${e.message}")
        }

        GeckoWebExtensionBridge.pushCspBypassConfig()
        GeckoWebExtensionBridge.pushPrivacyConfig(ctx)
    }

    fun presetDisplayName(preset: String): String = when (preset) {
        SettingsPrefs.PRIVACY_STRICT -> "Strict"
        SettingsPrefs.PRIVACY_CUSTOM -> "Custom"
        else -> "Standard"
    }
}
