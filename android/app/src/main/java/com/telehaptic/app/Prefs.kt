package com.telehaptic.app

import android.content.Context

/** Remembered answers and settings. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("telehaptic", Context.MODE_PRIVATE)

    var capsuleAddress: String
        get() = p.getString("capsule", "") ?: ""
        set(v) = p.edit().putString("capsule", v).apply()
    var detailed: Boolean
        get() = p.getBoolean("detailed", false)
        set(v) = p.edit().putBoolean("detailed", v).apply()
    /** "4x1.5", "5x3", "8x2" or "" if not chosen yet. */
    var magnet: String
        get() = p.getString("magnet", "") ?: ""
        set(v) = p.edit().putString("magnet", v).apply()
    /** Distance between the two magnetometers, mm; 0 = not entered. */
    var distanceMm: Int
        get() = p.getInt("distance_mm", 0)
        set(v) = p.edit().putInt("distance_mm", v).apply()
    var githubToken: String
        get() = p.getString("gh_token", "") ?: ""
        set(v) = p.edit().putString("gh_token", v.trim()).apply()
    var githubRepo: String
        get() = p.getString("gh_repo", "ivan-kalachikov/telehaptic") ?: "ivan-kalachikov/telehaptic"
        set(v) = p.edit().putString("gh_repo", v.trim()).apply()
    var githubBranch: String
        get() = p.getString("gh_branch", "main") ?: "main"
        set(v) = p.edit().putString("gh_branch", v.trim()).apply()
    /** Typing: say the word on space, the variant on *, "erase" on #. */
    var speakTyping: Boolean
        get() = p.getBoolean("speak_typing", true)
        set(v) = p.edit().putBoolean("speak_typing", v).apply()
    /** Typing: the word list may overrule a doubtful touch (soft T9). Off = classic T9 on the model's first choice. */
    var correctKeys: Boolean
        get() = p.getBoolean("correct_keys", true)
        set(v) = p.edit().putBoolean("correct_keys", v).apply()
    var autoUpload: Boolean
        get() = p.getBoolean("auto_upload", true)
        set(v) = p.edit().putBoolean("auto_upload", v).apply()

    companion object {
        val MAGNETS = listOf("4x1.5" to "disc 4×1.5 mm", "5x3" to "cylinder 5×3 mm", "8x2" to "disc 8×2 mm")
        fun magnetTitle(code: String) = MAGNETS.firstOrNull { it.first == code }?.second ?: "not chosen"
    }
}
