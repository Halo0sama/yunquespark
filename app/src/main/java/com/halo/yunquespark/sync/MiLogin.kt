package com.halo.yunquespark.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.halo.yunquespark.App
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 小米账号自动登录（WebView 画屏 bug 的修复方案）：
 * 在一个 1x1 不可见 WebView 里加载官方登录页，用 JS 自动填账号/密码并点击登录。
 *
 * 关键设计：
 *  - 设备 ID 持久化（首次短信验证后设备可信，之后自动续期不再触发验证）；
 *  - 遇到 identity/verifyPhone（短信安全验证）：自动点「获取验证码」，进入 OTP 等待态，
 *    由用户把手机收到的验证码经 submitOtp() 注入（验证码就发在这台手机上）；
 *  - 心跳由 Handler 独立驱动（不依赖 evaluateJavascript 回调），硬超时兜底；
 *  - 全程状态落库（mi_login_state）供诊断与 UI 联动。
 */
object MiLogin {

    class LoginException(message: String) : Exception(message)

    private const val TICK_MS = 2500L
    private const val NORMAL_TIMEOUT_MS = 100_000L
    private const val OTP_TIMEOUT_MS = 8 * 60_000L

    /** 进行中的登录会话（等待验证码时可注入 OTP）。 */
    private class Session(val wv: WebView, val handler: Handler) {
        var otpSent = false
        var otpSince = 0L
    }

    @Volatile private var session: Session? = null

    /** 用户输入短信验证码后调用（验证码发在本机，用户看短信输入）。 */
    fun submitOtp(code: String): Boolean {
        val s = session ?: return false
        if (code.isBlank()) return false
        setState("otp: 提交验证码…")
        s.handler.post {
            s.wv.evaluateJavascript(otpFillJs(code)) { }
        }
        return true
    }

    fun waitingForOtp(): Boolean = session != null && App.instance.db.getSetting("mi_login_state").startsWith("otp")

    fun login(context: Context, account: String, password: String, onResult: (cookie: String?, error: String?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val deviceId = ensureDeviceId()
            val wv = try {
                createWebView(deviceId)
            } catch (e: Exception) {
                onResult(null, "WebView 创建失败：${e.message}")
                return@post
            }
            var done = false
            var deadline = System.currentTimeMillis() + NORMAL_TIMEOUT_MS

            fun finish(cookie: String?, error: String?) {
                if (done) return
                done = true
                if (session?.wv === wv) session = null
                handler.removeCallbacksAndMessages(null)
                handler.post { runCatching { wv.destroy() } }
                onResult(cookie, error)
            }

            val ticker = object : Runnable {
                override fun run() {
                    if (done) return
                    try {
                        val c = CookieManager.getInstance().getCookie("https://i.mi.com") ?: ""
                        if (c.contains("serviceToken=") && c.contains("userId=")) {
                            setState("success")
                            finish(c, null)
                            return
                        }
                        val url = wv.url ?: ""
                        if (url.contains("verifyPhone") || url.contains("/identity/") || url.contains("verify")) {
                            // 短信验证阶段：自动点「获取验证码」（一次），放宽超时，等 submitOtp
                            if (deadline < System.currentTimeMillis() + OTP_TIMEOUT_MS) {
                                deadline = System.currentTimeMillis() + OTP_TIMEOUT_MS
                            }
                            val s = session
                            if (s != null && !s.otpSent) {
                                wv.evaluateJavascript(otpSendJs()) { r ->
                                    if (r?.contains("SENT") == true) s.otpSent = true
                                }
                            }
                            setState("otp: 短信验证码已发送至本机（若未收到请检查短信），请输入验证码")
                        } else {
                            setState("tick url=${url.take(70)}")
                            wv.evaluateJavascript(fillJs(account, password)) { }
                            wv.evaluateJavascript(errJs()) { res ->
                                val e = res?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                                if (e != null && !done) finish(null, "登录失败：$e")
                            }
                        }
                        if (System.currentTimeMillis() > deadline) {
                            finish(null, "登录超时：${
                                App.instance.db.getSetting("mi_login_state").take(80)
                            }")
                            return
                        }
                    } catch (e: Exception) {
                        setState("tick-err: ${e.message}")
                    }
                    handler.postDelayed(this, TICK_MS)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (done) return
                    setState("page $url")
                    val c = CookieManager.getInstance().getCookie("https://i.mi.com") ?: ""
                    if (url.contains("i.mi.com") && !url.contains("account.xiaomi.com") &&
                        c.contains("serviceToken=") && c.contains("userId=")
                    ) finish(c, null)
                }
            }
            session = Session(wv, handler)
            setState("loading i.mi.com")
            wv.loadUrl("https://i.mi.com/note")
            handler.postDelayed(ticker, 4000L)
        }
    }

    suspend fun loginSuspend(context: Context, account: String, password: String): String =
        suspendCancellableCoroutine { cont ->
            login(context, account, password) { cookie, error ->
                if (cont.isActive) {
                    if (cookie != null) cont.resume(cookie)
                    else cont.resumeWithException(LoginException(error ?: "登录失败"))
                }
            }
        }

    private fun setState(s: String) {
        runCatching { App.instance.db.setSetting("mi_login_state", s) }
    }

    /** 持久化设备 ID：短信验证一次后设备可信，自动续期不再要验证码。 */
    private fun ensureDeviceId(): String {
        val db = App.instance.db
        var id = db.getSetting("mi_device_id")
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            db.setSetting("mi_device_id", id)
        }
        return id
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(deviceId: String): WebView =
        WebView(App.instance).apply {
            layout(0, 0, 1, 1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            if (com.halo.yunquespark.BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            // 清掉过期 token，再固定 deviceId cookie（设备信任的载体）
            cm.removeAllCookies(null)
            cm.setCookie("https://account.xiaomi.com/", "deviceId=$deviceId")
            cm.setCookie("https://account.xiaomi.com/", "pass_ua=web")
            cm.flush()
        }

    /** 填账号/密码并处理协议弹窗/勾选同意，最后点击 登录/下一步（React 需走原生 setter）。 */
    private fun fillJs(account: String, password: String): String = """
        (function(){
          function setv(el,v){
            var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
            d.set.call(el,v);
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }
          function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
          var btns=Array.from(document.querySelectorAll('button,[role=button]'));
          var modalBtn=btns.find(function(e){var t=(e.textContent||'').trim();return /^同意并(继续|关闭提示)$/.test(t)&&vis(e);});
          if(modalBtn){modalBtn.click();return 'MODAL'}
          var u=document.querySelector('input[placeholder*="手机"]')||document.querySelector('input[placeholder*="邮箱"]')||document.querySelector('input[name=user]');
          var p=document.querySelector('input[type=password]');
          if(u&&u.value===''){setv(u,${JSONObject.quote(account)})}
          if(p&&p.value===''){setv(p,${JSONObject.quote(password)})}
          var cb=document.querySelector('input[type=checkbox],input[class*=checkbox]');
          if(cb&&!cb.checked&&vis(cb)){
            (cb.closest('label')||cb.closest('[class*=check]')||cb).click();
            return 'CHECK'
          }
          var b=btns.find(function(e){var t=(e.textContent||'').trim();return (t==='登录'||t==='下一步'||t==='确定')&&vis(e);});
          var canClick=b&&!b.disabled&&(u&&u.value!=='')&&(!p||p.value!=='');
          if(b&&canClick){b.click();return 'CLICK'}
          if(u&&(!p||p.value!==''))return 'READY';
          return 'WAIT';
        })()
    """.trimIndent()

    /** 验证码页：点「获取验证码/重新发送」（页面加载即自动点，只点一次）。 */
    private fun otpSendJs(): String = """
        (function(){
          function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
          var btns=Array.from(document.querySelectorAll('button,[role=button],a,span'));
          var b=btns.find(function(e){var t=(e.textContent||'').trim();return /^获取(短信)?验证码$|^获取验证码$/.test(t)&&vis(e);});
          if(b){b.click();return 'SENT'}
          return 'ALREADY';
        })()
    """.trimIndent()

    /** 注入验证码：兼容单输入框与 N 格分离输入框，然后点确认。 */
    private fun otpFillJs(code: String): String = """
        (function(){
          function setv(el,v){
            var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
            d.set.call(el,v);
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }
          function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
          var ins=Array.from(document.querySelectorAll('input')).filter(function(i){
            var p=i.placeholder||'';var t=i.type||'';
            return /验证码/.test(p)||t==='tel'||t==='number'||/code/i.test(i.name||'');
          });
          var code=${JSONObject.quote(code)};
          if(ins.length>1&&code.length===ins.length){
            ins.forEach(function(i,idx){setv(i,code[idx])});
          } else if(ins.length){
            setv(ins[0],code);
            if(ins.length>1)for(var k=1;k<ins.length;k++)setv(ins[k],'');
          }
          setTimeout(function(){
            var btns=Array.from(document.querySelectorAll('button,[role=button]'));
            var b=btns.find(function(e){var t=(e.textContent||'').trim();return /^(验证|确定|下一步|登录|提交)$/.test(t)&&vis(e);});
            if(b&&!b.disabled)b.click();
          },400);
          return 'OTP_FILLED';
        })()
    """.trimIndent()

    /** 抓页面上的登录错误文案（仅匹配强信号；安全验证由 verifyPhone URL 分支处理，不算错误）。 */
    private fun errJs(): String = """
        (function(){
          var t=document.body?document.body.innerText:'';
          if(t.length>6000)return '';
          var m=t.match(/(密码|账号)(或[^。\n]{0,4})?(不正确|错误)|次数过多|已?冻结|已?锁定|网络异常/);
          return m?m[0]:'';
        })()
    """.trimIndent()
}
