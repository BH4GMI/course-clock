package courseclock.timetable.schedule_import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面侧胶水的**契约测试**（纯 JVM，不启动 WebView）。
 *
 * 这三段 JS 是驱动学校真页面 captcha.js 的，正确性只能对着那个 33 KB 的第三方实现来判，
 * 所以行为验证放在浏览器验证台里做（`_crop/cas-src/sim/prod.html`，加载的就是这几段常量的
 * 原文；导出方式见 `_crop/DumpCaptchaJsTest.kt.txt`）。这里只锁住几条**改错了就会静默失效**
 * 的契约与踩过的坑，好在重构时立刻报警，不必每次重开浏览器。
 */
class SuesCaptchaGlueTest {

    private val watch = WebViewLoginFragment.SUES_CAPTCHA_WATCH_JS
    private val stop = WebViewLoginFragment.SUES_CAPTCHA_STOP_JS
    private fun drag(x: Int) = WebViewLoginFragment.suesCaptchaDragJs(x)

    /**
     * 拖动脚本必须**只由 x 参数化**：验证台就是把 `var X=272;` 换成别的值来跑生产字符串的，
     * 一旦有人在模板里再写死一个数字，验证台验的就不再是线上代码。
     */
    @Test
    fun 拖动脚本只由x参数化() {
        val t = drag(272)
        assertEquals("var X=272; 在模板里必须恰好出现一次", 2, t.split("var X=272;").size)
        assertEquals("除 X 以外不得有任何差异", drag(123), t.replace("var X=272;", "var X=123;"))
        assertTrue("越界值也要落到 badx 分支而不是拼出坏语法", drag(-1).contains("var X=-1;"))
    }

    /**
     * 滑轨宽度只能取自控件的**行内宽度**（zoomView 写死 60·zoom 的那个值）。
     * `.ap-bar-ctr` 带 1px 边框，offsetWidth 是 47 而不是 45，用它算出的滑轨会差 2px，
     * 提交值正好偏出服务端容差——这是实测踩过的坑。
     */
    @Test
    fun 滑轨宽度取自行内宽度而非offsetWidth() {
        val t = drag(272)
        assertTrue("必须读 ctr.style.width", t.contains("parseFloat(ctr.style.width)"))
        assertTrue("不得读 offsetWidth（含 1px 边框，会差 2px）", !t.contains("offsetWidth"))
        assertTrue("读不到行内宽度时要能退回容器宽", t.contains("box.clientWidth*(440-60)/440"))
    }

    /**
     * 监视器要报**两件事**，而且顺序不能反：先「该接管了」，再「滑块就绪」。
     *
     * 真页面把「滑动登录」单独做成了一步（表单里只有 execution、_eventId=checkCaptchaSubmit、
     * rememberMe 和滑块，没有任何凭据），滑块就绪与「用户按过登录」并不是同一时刻——滑块是
     * 页面自己渲染的。绑在一起就会出现「按了登录什么都不发生」的空窗。
     */
    @Test
    fun 接管与识别拖动是两个时机() {
        val arm = watch.indexOf("window.local_obj.suesCaptchaArmed(a,expectsSlide())")
        val drag = watch.indexOf("window.local_obj.suesCaptcha(bg.src,sl.src)")
        assertTrue("必须先报接管", arm > 0)
        assertTrue("再报滑块就绪", drag > arm)
        assertTrue("且滑块就绪要在接管之后才可能上报（armed 前置判断）",
                watch.indexOf("if(!armed)return;") in 0 until drag)
    }

    /**
     * 「用户按过登录」必须用 `submit` 事件抓，**不能用轮询**。
     *
     * `checkPassLogin` 在同一个点击里就地把密码 RSA 加密成 256 位并让表单提交，之后页面立刻
     * 跳走；400ms 一次的采样几乎必然错过那一瞬间，于是遮罩永远不出现。而且只能在**真的要提交**
     * 时才算数——用户名密码为空时 `checkPassLogin` 会 alert 并 return false，那时接管就会把
     * 用户关在遮罩后面，连填账号的地方都没有。
     */
    @Test
    fun 按过登录要靠submit事件而不是轮询() {
        assertTrue("必须监听 submit 事件",
                watch.contains("document.addEventListener('submit',function(e){"))
        assertTrue("只认带密码框的那张表单",
                watch.contains("f.querySelector('input[type=password]')"))
        assertTrue("提交那一刻立刻上报（不等下一 tick）",
                watch.contains("submitted=true;report();"))
        assertTrue("不得再靠采样密码长度判断", !watch.contains("pw.value.length===256"))
    }

    /**
     * 安全不变量：**只要这一份文档里有密码框，就必须等用户真的提交了登录**才允许自动拖——
     * 拖成功会让页面自己 submit fm1，绝不能替用户提交他还没确认过的凭据。反过来，没有密码框
     * 的那一步（滑动登录）拖动只推进 `_eventId=checkCaptchaSubmit`，不含任何凭据，可以拖。
     */
    @Test
    fun 有密码框时必须等用户提交登录() {
        assertTrue("用有没有密码框区分是哪一步", watch.contains("return !document.getElementById('password');"))
        assertTrue("有密码框且还没提交 → 不接管", watch.contains("if(!expectsSlide())return false;"))
        assertTrue("没密码框时以滑块容器出现为接管信号",
                watch.contains("return !!document.querySelector('.ap-container');"))
    }

    /** 页面把主动权交回用户时（如密码错了被弹回登录页）必须报 armed=false，好让遮罩撤掉。 */
    @Test
    fun 交回用户时要撤销接管() {
        assertTrue("状态变化才报，包括 true→false", watch.contains("if(a===armed)return;armed=a;"))
    }

    /**
     * 身份必须在**上报之前**记账：万一桥抛异常（tick 的 catch 会吞掉），这一张就此作罢、
     * 不会每 400ms 重试同一张坏图；`last=id` 若放在上报之后就会变成无限重试。
     */
    @Test
    fun 身份记账在上报之前() {
        val consume = watch.indexOf("last=id;")
        val report = watch.indexOf("window.local_obj.suesCaptcha(bg.src,sl.src)")
        assertTrue(consume > 0 && report > consume)
    }

    /** 同一份文档只装一份监视器：守卫标志挡住重复装表，否则先装的那份计时器再也停不掉。 */
    @Test
    fun 监视器同一文档只装一次() {
        val guard = watch.indexOf("if(window.__suesCaptchaWatch)return;")
        val set = watch.indexOf("window.__suesCaptchaWatch=true;")
        val interval = watch.indexOf("window.__suesCaptchaTimer=setInterval(")
        assertTrue(guard in 0 until set)
        assertTrue(set < interval)
    }

    /** 停表停的必须是装表装的那个计时器；三个桥名都要与 @JavascriptInterface 方法名一致。 */
    @Test
    fun 停表与桥名对得上() {
        assertTrue(watch.contains("window.__suesCaptchaTimer=setInterval(tick,400)"))
        assertTrue(stop.contains("clearInterval(window.__suesCaptchaTimer)"))
        assertTrue(watch.contains("window.local_obj.suesCaptchaArmed(a,expectsSlide())"))
        assertTrue(watch.contains("window.local_obj.suesCaptcha(bg.src,sl.src)"))
    }

    /**
     * 事件序列要与真人一致：mousedown 打在手柄上（captcha.js 在这里挂监听），
     * mousemove/mouseup 打在 document 上（页面是按下之后才在 document 上挂的）。
     * 并且起拖前必须先勾 rememberMe——startFunc 未勾选会直接 return，拖动根本不开始。
     */
    @Test
    fun 事件序列与真人一致且先勾选协议() {
        val t = drag(272)
        assertTrue(t.contains("mev('mousedown',sx,ctr)"))
        assertTrue(t.contains("mev('mousemove',sx+bestC-cur,document)"))
        assertTrue(t.contains("mev('mouseup',sx+bestC-cur,document)"))
        assertTrue("先勾 rememberMe 再按下", t.indexOf("rememberMe") < t.indexOf("mev('mousedown'"))
    }

    /** cutScope 取两张图的自然宽之差（页面从服务端 JSON 拿，闭包读不到；两者实测都是 360）。 */
    @Test
    fun cutScope取自然宽之差() {
        val t = drag(272)
        assertTrue(t.contains("bgImg.naturalWidth-slImg.naturalWidth"))
        assertTrue(t.contains(":360;"))
    }

    /**
     * 这两个数字被写进了**用户可见的说明**（README「最多试 3 次」「等待滑块超过 20 秒」、
     * CHANGELOG 1.1 同），改了常量就会让文档开始说谎，所以在这里钉住。
     * 上限取 3 不是随手定的：页面第 4 次失败会 `maxError` 把滑块整个藏起来，再拖也没有机会。
     */
    @Test
    fun 上限与等待时长与用户可见说明一致() {
        assertEquals("页面第 4 次失败会藏起滑块，自动尝试不能超过 3 次",
                3, WebViewLoginFragment.SUES_CAPTCHA_MAX_ATTEMPTS)
        assertEquals("说明里承诺的是「接管后 20 秒」",
                20_000L, WebViewLoginFragment.SUES_CAPTCHA_ARMED_TIMEOUT_MS)
    }
}
