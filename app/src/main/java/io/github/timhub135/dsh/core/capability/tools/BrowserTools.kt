package io.github.timhub135.dsh.core.capability.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** A headless in-app browser (WebView laid out off-screen) the agent can drive. */
@Singleton
class BrowserTools @Inject constructor(@ApplicationContext private val context: Context) : ToolSet {
    private var web: WebView? = null

    /**
     * One navigation the tool is waiting for. onPageFinished only completes it after a page *started*
     * since the wait began, so a late finish from an earlier page, frame or redirect cannot end it early.
     */
    private class Nav { val done = CompletableDeferred<Unit>(); @Volatile var started = false }
    @Volatile private var nav = Nav()
    private fun newNav(): Nav = Nav().also { nav = it }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun view(): WebView = withContext(Dispatchers.Main) {
        web ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { nav.started = true }
                override fun onPageFinished(view: WebView?, url: String?) { nav.let { if (it.started) it.done.complete(Unit) } }
            }
            val m = context.resources.displayMetrics
            measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY))
            layout(0, 0, m.widthPixels, m.heightPixels)
        }.also { web = it }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<String>()
        view().evaluateJavascript(script) { d.complete(it ?: "null") }
        d.await()
    }.let { raw -> runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull() ?: raw }

    /** True when the page finished loading within the timeout. */
    private suspend fun awaitLoad(n: Nav, timeoutMs: Long = 20_000): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) { n.done.await() } != null
        delay(600)
        return ok
    }

    /** Page text plus numbered interactive elements (data-dsh-id). */
    private suspend fun read(maxChars: Int = 8000): String = js(READ_JS).take(maxChars)

    override val tools = listOf(
        object : Tool("browser_open", ToolGroup.Browser, Risk.read, false,
            "Open a web page in the agent's browser and return its text and numbered interactive elements.", {
                string("url", "URL", required = true)
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                var url = args.reqStr("url")
                if (!url.contains("://")) url = "https://$url"
                val n = newNav()
                withContext(Dispatchers.Main) { view().loadUrl(url) }
                val loaded = awaitLoad(n)
                return ToolResult.text((if (loaded) "" else "(the page was still loading after 20s; content may be partial)\n") + read())
            }
        },
        object : Tool("browser_read", ToolGroup.Browser, Risk.read, false, "Re-read the current page (text + interactive elements).", {
            int("maxChars", "Default 8000")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = ToolResult.text(read((args.int("maxChars") ?: 8000).coerceIn(500, 40000)))
        },
        object : Tool("browser_click", ToolGroup.Browser, Risk.write, false, "Click an element by its [id] from browser_read.", {
            int("id", "Element id", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val id = args.int("id") ?: throw InvalidArgsException("id")
                val n = newNav()
                val r = js("(()=>{const e=document.querySelector('[data-dsh-id=\"$id\"]');if(!e)return 'missing';e.scrollIntoView({block:'center'});e.click();return 'ok'})()")
                if (r == "missing") throw InvalidArgsException("No element $id; call browser_read")
                withTimeoutOrNull(4_000) { n.done.await() }
                delay(800)
                return ToolResult.text("Clicked $id.\n" + read())
            }
        },
        object : Tool("browser_type", ToolGroup.Browser, Risk.write, false, "Type into an input by [id]; submit=true presses Enter / submits the form.", {
            int("id", "Element id", required = true); string("text", "Text", required = true)
            bool("submit", "Press Enter / submit the form afterwards")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val id = args.int("id") ?: throw InvalidArgsException("id")
                val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(args.reqStr("text")))
                val submit = args.bool("submit") == true
                val n = newNav()
                val r = js("""(()=>{const e=document.querySelector('[data-dsh-id="$id"]');if(!e)return 'missing';e.focus();
                    if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,$text);}
                    else if(!('value' in e))return 'not_editable';
                    else{const s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');s&&s.set?s.set.call(e,$text):(e.value=$text);
                    e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
                    if($submit){e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));if(e.form)e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}
                    return 'ok'})()""")
                if (r == "missing") throw InvalidArgsException("No element $id; call browser_read")
                if (r == "not_editable") throw InvalidArgsException("Element $id is not a text field; pick an input/textarea from browser_read")
                if (r != "ok") return ToolResult.error("failed", "Typing failed in the page: $r")
                if (submit) withTimeoutOrNull(6_000) { n.done.await() }
                delay(700)
                return ToolResult.text("Typed.\n" + read())
            }
        },
        object : Tool("browser_scroll", ToolGroup.Browser, Risk.read, false, "Scroll the page.", {
            string("direction", "", required = true, enum = listOf("down", "up", "top", "bottom"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                js(when (args.reqStr("direction")) {
                    "up" -> "window.scrollBy(0,-window.innerHeight*0.8)"; "top" -> "window.scrollTo(0,0)"
                    "bottom" -> "window.scrollTo(0,document.body.scrollHeight)"; else -> "window.scrollBy(0,window.innerHeight*0.8)"
                })
                delay(500)
                return ToolResult.text(read())
            }
        },
        object : Tool("browser_back", ToolGroup.Browser, Risk.read, false, "Go back in browser history.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (!withContext(Dispatchers.Main) { view().canGoBack() }) return ToolResult.error("no_history", "There is no previous page in the browser history.")
                val n = newNav()
                withContext(Dispatchers.Main) { view().goBack() }
                awaitLoad(n, 10_000)
                return ToolResult.text(read())
            }
        },
        object : Tool("browser_screenshot", ToolGroup.Browser, Risk.read, false, "Screenshot of the visible part of the page.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.Main) {
                val v = view()
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                ToolResult.image(Images.MIME, Base64.encodeToString(Images.encode(bmp).bytes, Base64.NO_WRAP))
            }
        },
    )

    private companion object {
        val READ_JS = """
(()=>{let n=0;const out=[];const seen=new Set();
document.querySelectorAll('[data-dsh-id]').forEach(e=>e.removeAttribute('data-dsh-id'));
const sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[onclick],[contenteditable=true]';
const items=[];document.querySelectorAll(sel).forEach(e=>{const r=e.getBoundingClientRect();if(r.width<2||r.height<2)return;
 const t=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||e.alt||'').trim().replace(/\s+/g,' ').slice(0,80);
 const id=n++;e.setAttribute('data-dsh-id',id);items.push('['+id+'] '+e.tagName.toLowerCase()+(e.type?':'+e.type:'')+' "'+t+'"'+(e.href?' → '+e.href.slice(0,120):''));});
const text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,6000);
return 'URL: '+location.href+'\nTitle: '+document.title+'\n\n'+text+'\n\n--- interactive ---\n'+items.slice(0,150).join('\n');})()
""".trimIndent()
    }
}
