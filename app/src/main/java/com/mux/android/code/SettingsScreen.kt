package com.mux.android.code

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun hasAllFiles(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

private fun mask(s: String) = if (s.isEmpty()) "not set" else if (s.length <= 8) "••••" else "••••" + s.takeLast(4)

private val MODELS = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5")

@Composable
fun SettingsScreen() {
    val c = Hud.colors
    val ctx = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var edit by remember { mutableStateOf<Triple<String, String, String>?>(null) } // key, title, hint

    // theme choices: built-in + from extensions
    val themes = listOf("mux" to "MUX HUD", "vs-dark" to "VS DARK", "vs" to "VS LIGHT", "hc-black" to "HIGH CONTRAST") + Ext.themeList.map { it.first to it.second.uppercase() }
    val themeLabel = themes.firstOrNull { it.first == Prefs.edTheme }?.second ?: "MUX HUD"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HudPanel(Modifier.fillMaxWidth(), "EDITOR") {
            SettingRow("EDITOR THEME", "built-in or from an installed extension", themeLabel) {
                val i = themes.indexOfFirst { it.first == Prefs.edTheme }
                Prefs.edTheme = themes[(i + 1) % themes.size].first
                Prefs.commit(); Ed.options()
            }
            val sizes = listOf(10, 12, 13, 14, 16, 18, 20)
            SettingRow("EDITOR TEXT SIZE", null, "${Prefs.edFont} PX") {
                Prefs.edFont = sizes[(sizes.indexOf(Prefs.edFont) + 1) % sizes.size]; Prefs.commit(); Ed.options()
            }
            SettingRow("WORD WRAP", "wrap long lines", if (Prefs.wrap) "ON" else "OFF") { Prefs.wrap = !Prefs.wrap; Prefs.commit(); Ed.options() }
            SettingRow("MINIMAP", "small overview on the right", if (Prefs.minimap) "ON" else "OFF") { Prefs.minimap = !Prefs.minimap; Prefs.commit(); Ed.options() }
            SettingRow("TAB SIZE", null, "${Prefs.tabSize}") {
                Prefs.tabSize = when (Prefs.tabSize) { 2 -> 4; 4 -> 8; else -> 2 }; Prefs.commit(); Ed.options()
            }
            SettingRow("AUTO SAVE", "save 1 second after typing", if (Prefs.autoSave) "ON" else "OFF") { Prefs.autoSave = !Prefs.autoSave; Prefs.commit(); Ed.options() }
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "APP AND TERMINAL") {
            SettingRow("HUD COLOUR", "cyan or amber", if (Prefs.amber) "AMBER" else "CYAN") { Prefs.amber = !Prefs.amber; Prefs.commit(); Ed.options() }
            val sizes = listOf(10, 11, 12, 13, 14, 16, 18)
            SettingRow("TERMINAL TEXT SIZE", null, "${Prefs.fontSize} SP") {
                Prefs.fontSize = sizes[(sizes.indexOf(Prefs.fontSize) + 1) % sizes.size]; Prefs.commit()
            }
            SettingRow("TERMINAL EXTRA KEYS", "TAB, arrows, ^C, symbols", if (Prefs.extraKeys) "ON" else "OFF") { Prefs.extraKeys = !Prefs.extraKeys; Prefs.commit() }
            SettingRow("KEEP SCREEN ON", null, if (Prefs.keepAwake) "ON" else "OFF") { Prefs.keepAwake = !Prefs.keepAwake; Prefs.commit() }
            SettingRow("COMMAND HISTORY", "${Prefs.history.size} saved", "CLEAR", KeyKind.DANGER) { Prefs.clearHistory(); Util.toast(ctx, "HISTORY CLEARED") }
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "AI AGENT") {
            HudText("Uses the Anthropic API with your own key (console.anthropic.com). Calls are billed to your account. The key stays on this phone.", color = c.dim, size = 10.sp)
            Spacer(Modifier.height(4.dp))
            SettingRow("API KEY", mask(Prefs.apiKey), "CHANGE") { edit = Triple("apiKey", "ANTHROPIC API KEY", "sk-ant-...") }
            SettingRow("MODEL", Prefs.model, "NEXT") {
                val i = MODELS.indexOf(Prefs.model)
                Prefs.model = MODELS[(i + 1) % MODELS.size]; Prefs.commit()
            }
            SettingRow("CUSTOM MODEL ID", null, "TYPE") { edit = Triple("model", "MODEL ID", "claude-sonnet-5-5") }
            SettingRow("API URL", Prefs.baseUrl, "CHANGE") { edit = Triple("baseUrl", "API BASE URL", "https://api.anthropic.com") }
            SettingRow("PERMISSIONS", "ASK = confirm every write, command, git change", if (Prefs.approveAuto) "AUTO" else "ASK") {
                Prefs.approveAuto = !Prefs.approveAuto; Prefs.commit()
            }
            SettingRow("MAX STEPS", "tool calls per message", "${Prefs.maxSteps}") {
                Prefs.maxSteps = when (Prefs.maxSteps) { 6 -> 12; 12 -> 25; else -> 6 }; Prefs.commit()
            }
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "GIT") {
            SettingRow("AUTHOR NAME", Prefs.gitName.ifEmpty { "not set" }, "CHANGE") { edit = Triple("gitName", "AUTHOR NAME", "Your Name") }
            SettingRow("AUTHOR EMAIL", Prefs.gitEmail.ifEmpty { "not set" }, "CHANGE") { edit = Triple("gitEmail", "AUTHOR EMAIL", "you@example.com") }
            SettingRow("USERNAME", Prefs.gitUser.ifEmpty { "optional" }, "CHANGE") { edit = Triple("gitUser", "GIT USERNAME", "github-username") }
            SettingRow("TOKEN", mask(Prefs.gitToken), "CHANGE") { edit = Triple("gitToken", "PERSONAL ACCESS TOKEN", "ghp_...") }
            HudText("Push, pull and private clone use HTTPS with a personal access token. SSH keys are not supported.", color = c.dim, size = 9.sp)
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "STORAGE ACCESS") {
            HudText("The app workspace needs no permission. To open folders on the phone storage (/sdcard) allow all-files access.", color = c.text, size = 11.sp)
            HudText(
                if (hasAllFiles()) "ALL FILES ACCESS: GRANTED" else "ALL FILES ACCESS: NOT GRANTED",
                color = if (hasAllFiles()) c.good else c.accent, size = 11.sp, bold = true
            )
            Spacer(Modifier.height(6.dp))
            HudButton("GRANT ALL FILES ACCESS", Modifier.fillMaxWidth().height(44.dp), KeyKind.ACCENT, fontSize = 12.sp) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Util.openAppSettings(ctx)
                    }
                } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    edit?.let { (key, title, hint) ->
        val cur = when (key) {
            "apiKey" -> Prefs.apiKey; "model" -> Prefs.model; "baseUrl" -> Prefs.baseUrl
            "gitName" -> Prefs.gitName; "gitEmail" -> Prefs.gitEmail; "gitUser" -> Prefs.gitUser; else -> Prefs.gitToken
        }
        PromptDialog(title, cur, hint, "SAVE", onDone = { v ->
            val t = v.trim()
            when (key) {
                "apiKey" -> Prefs.apiKey = t
                "model" -> if (t.isNotEmpty()) Prefs.model = t
                "baseUrl" -> if (t.startsWith("http")) Prefs.baseUrl = t
                "gitName" -> Prefs.gitName = t
                "gitEmail" -> Prefs.gitEmail = t
                "gitUser" -> Prefs.gitUser = t
                else -> Prefs.gitToken = t
            }
            Prefs.commit()
            edit = null
        }, onCancel = { edit = null })
    }
}
