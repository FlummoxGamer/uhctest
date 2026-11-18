package app.harvester.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object UserscriptStore {

    private const val NAME = "uh_scripts"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_PERMS_PREFIX = "perms_"
    private const val KEY_DIRTY = "dirty"

    data class UserscriptPermissions(
        val allowAllGrants: Boolean = true,
        val bypassCsp: Boolean = true,
        val allowAllConnect: Boolean = true,
        val allFrames: Boolean = false,
        val overrideMatches: List<String> = emptyList(),
        val overrideExcludes: List<String> = emptyList()
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED_PREFIX + name, true)

    fun setEnabled(ctx: Context, name: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED_PREFIX + name, on).apply()
        markDirty(ctx)
    }

    fun getPermissions(ctx: Context, name: String): UserscriptPermissions {
        val raw = prefs(ctx).getString(KEY_PERMS_PREFIX + name, null) ?: return UserscriptPermissions()
        return try {
            val o = JSONObject(raw)
            val matches = buildList {
                val arr = o.optJSONArray("matches")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            val excludes = buildList {
                val arr = o.optJSONArray("excludes")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            UserscriptPermissions(
                allowAllGrants = o.optBoolean("allowAllGrants", true),
                bypassCsp = o.optBoolean("bypassCsp", true),
                allowAllConnect = o.optBoolean("allowAllConnect", true),
                allFrames = o.optBoolean("allFrames", false),
                overrideMatches = matches,
                overrideExcludes = excludes
            )
        } catch (_: Exception) {
            UserscriptPermissions()
        }
    }

    fun setPermissions(ctx: Context, name: String, perms: UserscriptPermissions) {
        val o = JSONObject().apply {
            put("allowAllGrants", perms.allowAllGrants)
            put("bypassCsp", perms.bypassCsp)
            put("allowAllConnect", perms.allowAllConnect)
            put("allFrames", perms.allFrames)
            put("matches", JSONArray(perms.overrideMatches))
            put("excludes", JSONArray(perms.overrideExcludes))
        }
        prefs(ctx).edit().putString(KEY_PERMS_PREFIX + name, o.toString()).apply()
        markDirty(ctx)
    }

    fun forget(ctx: Context, name: String) {
        prefs(ctx).edit()
            .remove(KEY_ENABLED_PREFIX + name)
            .remove(KEY_PERMS_PREFIX + name)
            .apply()
        markDirty(ctx)
    }

    fun markDirty(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_DIRTY, true).apply()
    }

    fun consumeDirty(ctx: Context): Boolean {
        val p = prefs(ctx)
        val was = p.getBoolean(KEY_DIRTY, false)
        if (was) p.edit().putBoolean(KEY_DIRTY, false).apply()
        return was
    }
}
