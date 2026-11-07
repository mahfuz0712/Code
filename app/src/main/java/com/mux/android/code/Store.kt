package com.mux.android.code

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** All settings. Plain SharedPreferences (keys and tokens are stored on the device only). */
object Prefs {
    private var sp: SharedPreferences? = null

    // app + terminal
    var amber by mutableStateOf(false)
    var fontSize by mutableIntStateOf(13)          // terminal text size
    var keepAwake by mutableStateOf(true)
    var extraKeys by mutableStateOf(true)
    var maxLines by mutableIntStateOf(3000)
    var aliases by mutableStateOf<Map<String, String>>(emptyMap())
    var userEnv by mutableStateOf<Map<String, String>>(emptyMap())
    val history = ArrayList<String>()

    // editor
    var edTheme by mutableStateOf("mux")           // mux | vs-dark | vs | hc-black | ext:<id>
    var edFont by mutableIntStateOf(14)
    var wrap by mutableStateOf(false)
    var minimap by mutableStateOf(false)
    var tabSize by mutableIntStateOf(4)
    var autoSave by mutableStateOf(false)

    // ai agent
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("claude-sonnet-5-5")
    var baseUrl by mutableStateOf("https://api.anthropic.com")
    var approveAuto by mutableStateOf(false)
    var maxSteps by mutableIntStateOf(12)

    // git
    var gitName by mutableStateOf("")
    var gitEmail by mutableStateOf("")
    var gitUser by mutableStateOf("")
    var gitToken by mutableStateOf("")

    // workspace
    var rootPath by mutableStateOf("")
    var openPaths by mutableStateOf<List<String>>(emptyList())
    var activePath by mutableStateOf("")

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("mux_code", Context.MODE_PRIVATE)
        sp = p
        amber = p.getBoolean("amber", false)
        fontSize = p.getInt("font", 13)
        keepAwake = p.getBoolean("awake", true)
        extraKeys = p.getBoolean("xkeys", true)
        maxLines = p.getInt("maxlines", 3000)
        edTheme = p.getString("edTheme", "mux") ?: "mux"
        edFont = p.getInt("edFont", 14)
        wrap = p.getBoolean("wrap", false)
        minimap = p.getBoolean("minimap", false)
        tabSize = p.getInt("tabSize", 4)
        autoSave = p.getBoolean("autoSave", false)
        apiKey = p.getString("apiKey", "") ?: ""
        model = p.getString("model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        baseUrl = p.getString("baseUrl", "https://api.anthropic.com") ?: "https://api.anthropic.com"
        approveAuto = p.getBoolean("approveAuto", false)
        maxSteps = p.getInt("maxSteps", 12)
        gitName = p.getString("gitName", "") ?: ""
        gitEmail = p.getString("gitEmail", "") ?: ""
        gitUser = p.getString("gitUser", "") ?: ""
        gitToken = p.getString("gitToken", "") ?: ""
        rootPath = p.getString("rootPath", "") ?: ""
        activePath = p.getString("activePath", "") ?: ""
        openPaths = readList(p.getString("openPaths", "[]") ?: "[]")
        aliases = readMap(p.getString("aliases", null) ?: "{\"ll\":\"ls -la\",\"la\":\"ls -a\",\"..\":\"cd ..\",\"sdcard\":\"cd /storage/emulated/0\"}")
        userEnv = readMap(p.getString("env", "{}") ?: "{}")
        history.addAll(readList(p.getString("history", "[]") ?: "[]"))
    }

    private fun readList(s: String): List<String> {
        val l = ArrayList<String>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) l.add(a.getString(i))
        } catch (e: Exception) {
        }
        return l
    }

    private fun readMap(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        try {
            val o = JSONObject(s)
            for (k in o.keys()) m[k] = o.getString(k)
        } catch (e: Exception) {
        }
        return m
    }

    fun commit() {
        sp?.edit()
            ?.putBoolean("amber", amber)?.putInt("font", fontSize)?.putBoolean("awake", keepAwake)
            ?.putBoolean("xkeys", extraKeys)?.putInt("maxlines", maxLines)
            ?.putString("edTheme", edTheme)?.putInt("edFont", edFont)?.putBoolean("wrap", wrap)
            ?.putBoolean("minimap", minimap)?.putInt("tabSize", tabSize)?.putBoolean("autoSave", autoSave)
            ?.putString("apiKey", apiKey)?.putString("model", model)?.putString("baseUrl", baseUrl)
            ?.putBoolean("approveAuto", approveAuto)?.putInt("maxSteps", maxSteps)
            ?.putString("gitName", gitName)?.putString("gitEmail", gitEmail)
            ?.putString("gitUser", gitUser)?.putString("gitToken", gitToken)
            ?.putString("rootPath", rootPath)?.putString("activePath", activePath)
            ?.putString("openPaths", JSONArray(openPaths).toString())
            ?.putString("aliases", JSONObject(aliases as Map<*, *>).toString())
            ?.putString("env", JSONObject(userEnv as Map<*, *>).toString())
            ?.apply()
    }

    fun addHistory(cmd: String) {
        if (history.lastOrNull() == cmd) return
        history.add(cmd)
        while (history.size > 300) history.removeAt(0)
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    private fun saveHistory() {
        sp?.edit()?.putString("history", JSONArray(history).toString())?.apply()
    }
}
