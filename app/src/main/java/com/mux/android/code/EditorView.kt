package com.mux.android.code

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/** Talks to the Monaco editor (the VS Code editor) that runs inside one WebView kept alive for the whole app. */
object Ed {
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private val loaded = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    private fun q(s: String) = JSONObject.quote(s)

    fun js(code: String) {
        if (!ready) { queue.add(code); return }
        val w = web ?: return
        main.post { w.evaluateJavascript(code, null) }
    }

    fun options() {
        val theme = when {
            Prefs.edTheme == "mux" -> if (Prefs.amber) "mux-amber" else "mux-cyan"
            else -> Prefs.edTheme
        }
        val o = JSONObject()
            .put("theme", theme).put("fontSize", Prefs.edFont)
            .put("wordWrap", if (Prefs.wrap) "on" else "off")
            .put("minimap", Prefs.minimap).put("tabSize", Prefs.tabSize).put("autoSave", Prefs.autoSave)
        js("MUX.setOptions($o)")
    }

    fun loadExtensions() {
        js("MUX.loadExtensions(${Ext.editorPayload()})")
        options()
    }

    fun openInJs(path: String) {
        val f = File(path)
        if (!f.isFile) return
        if (path in loaded) { js("MUX.show(${q(path)})"); return }
        val text = try { f.readText() } catch (e: Exception) { return }
        loaded.add(path)
        js("MUX.open(${q(path)},${q(text)},${q(Ws.lang(f.name))})")
    }

    fun show(path: String) { if (path in loaded) js("MUX.show(${q(path)})") else openInJs(path) }
    fun close(path: String) { loaded.remove(path); js("MUX.close(${q(path)})") }
    fun requestSave(path: String) = js("MUX.requestSave(${q(path)})")
    fun markSaved(path: String, ver: String) = js("MUX.markSaved(${q(path)},${q(ver)})")
    fun goto(line: Int) = js("MUX.goto($line,1)")
    fun trigger(id: String) = js("MUX.trigger(${q(id)})")
    fun type(t: String) = js("MUX.type(${q(t)})")
    fun key(k: String) = js("MUX.key(${q(k)})")
    fun askSelection() = js("MUX.selection()")

    /** Reload a file from disk (after the AI agent or git changed it). Skips files with unsaved edits. */
    fun reloadFromDisk(path: String) {
        if (path !in loaded) return
        if (Ws.dirty[path] == true) return
        val text = try { File(path).readText() } catch (e: Exception) { return }
        js("MUX.reload(${q(path)},${q(text)})")
    }

    fun reloadAllClean() { for (p in Ws.open) reloadFromDisk(p) }

    private class Bridge {
        @JavascriptInterface fun ready() {
            main.post {
                ready = true
                val w = web
                val pending = ArrayList(queue)
                queue.clear()
                options()
                loadExtensions()
                for (p in Ws.open) openInJs(p)
                Ws.active?.let { show(it) }
                for (c in pending) w?.evaluateJavascript(c, null)
            }
        }

        @JavascriptInterface fun dirty(path: String, d: Boolean) {
            main.post {
                Ws.dirty[path] = d
                if (!d) Ws.dirty.remove(path)
            }
        }

        @JavascriptInterface fun save(path: String, text: String, ver: String) {
            main.post { Ws.saved(path, text, ver) }
        }

        @JavascriptInterface fun selection(text: String, path: String) {
            main.post { Agent.fromSelection(path, text) }
        }

        @JavascriptInterface fun log(m: String) {
            main.post { Util.toastApp(m.take(80)) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context): WebView {
        val cw = MutableContextWrapper(app)
        wrapper = cw
        val w = WebView(cw)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.cacheMode = WebSettings.LOAD_DEFAULT
        w.setBackgroundColor(Color.parseColor("#040A12"))
        w.overScrollMode = WebView.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Bridge(), "Android")
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        w.loadUrl("https://appassets.androidplatform.net/assets/editor.html")
        return w
    }

    fun view(activityCtx: Context): WebView {
        val w = web ?: create(activityCtx.applicationContext).also { web = it }
        wrapper?.baseContext = activityCtx
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }
}

@Composable
fun EditorView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> Ed.view(ctx) },
        update = { }
    )
}
