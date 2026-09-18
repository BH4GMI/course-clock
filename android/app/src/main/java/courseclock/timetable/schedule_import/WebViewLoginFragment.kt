package courseclock.timetable.schedule_import

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import courseclock.timetable.R
import courseclock.timetable.base_view.BaseFragment
import courseclock.timetable.databinding.FragmentWebViewLoginBinding
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.ViewUtils
import es.dmoral.toasty.Toasty

class WebViewLoginFragment : BaseFragment() {

    private var _binding: FragmentWebViewLoginBinding? = null

    /**
     * 只在**同步**路径上用（`onViewCreated`、点击回调）——那时视图必然存在。
     *
     * 异步回调一律先判 `_binding`：`onDestroyView` 之后它们仍然可能被派发。最典型的是
     * `View.postDelayed` 投出去的消息**不会**因为视图销毁而取消（Robolectric 实测：视图从
     * 窗口摘掉之后 runnable 照跑），在途的 `evaluateJavascript` 回调与 JS 桥同理。
     * 这些回调是"给这个视图看的"，视图没了就无事可做，直接收工。
     */
    private val binding get() = _binding!!

    private lateinit var url: String
    private val viewModel by activityViewModels<ImportViewModel>()
    private var zoom = 100

    // SUES 引导式导入的自动导航状态。登录仍由用户本人完成，这里只管登录之后的导航。
    // 只有一个停止位：一旦置位就彻底停手，杜绝任何形式的来回跳。
    private var suesAutoDone = false

    /** 自动脚本是否在等课表页：等到了才继续，落点不符就立刻退出自动。 */
    private var suesExpectCourseTable = false

    /**
     * `onPageFinished` 的去重账本。
     *
     * 同一份文档会被重复回调，必须只处理一次；但**换文档不等于换地址**——统一身份认证页在提交
     * 账号之前和之后地址逐字节相同（表单提交到未改写的主机名，再 302 回同一个 WebVPN 地址），
     * 用地址当身份会把登录之后那一份整个吞掉：勾选「保密提示」、点掉密码过期提示都挂在它下面。
     */
    private val suesDocs = SuesDocLedger()

    /** 已经点过几次「点击跳过」。点过一次又被弹回同一页，说明这次必须改密码。 */
    private var suesSkipCount = 0

    /** 门户探测的重试计数与它正在探测的地址；换了地址就重新计数。 */
    private var suesProbeAttempts = 0
    private var suesProbeUrl: String? = null

    /**
     * 门户探测的下一发。
     *
     * 必须存下来：探测是**自续**的（每一发自己排下一发），而 `postDelayed` 投出的消息不会
     * 随着 `onDestroyView` 消失，不取消就会在页面已经离开之后继续跑（见 [binding] 的说明）。
     */
    private var suesProbeTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            url = it.getString("url")!!
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        _binding = FragmentWebViewLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewUtils.resizeStatusBar(requireContext().applicationContext, binding.root.findViewById(R.id.v_status))

        // 本应用只服务 SUES，入口固定是学校 WebVPN，不再给用户输入网址的地方。
        startVisit()

        MaterialAlertDialogBuilder(requireActivity())
                .setTitle("注意事项")
                .setMessage("1. 请完成统一身份认证登录（账号密码或微信扫码，可能有人机验证）\n" +
                        "2. 登录完成后会自动带你进入课表页，不用自己找菜单\n" +
                        "3. 看到课表后，点右下角的圆形按钮完成导入")
                .setPositiveButton("我知道啦", null)
                .setCancelable(false)
                .show()

        binding.wvCourse.settings.javaScriptEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.wvCourse.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.wvCourse.addJavascriptInterface(InJavaScriptLocalObj(), "local_obj")
        binding.wvCourse.webViewClient = object : WebViewClient() {

            /** 主文档开始加载：这是唯一可靠的「换了一份文档」信号，不能用地址代替。 */
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                suesDocs.onDocumentStarted()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                suesAutoEnter(url)
            }

            /**
             * 主文档加载失败时必须撤掉遮罩：否则用户既看不到内容、也点不到东西，
             * 等于被锁在一个死页面上。
             */
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (_binding?.llAutoMask?.visibility != View.VISIBLE) return
                if (failingUrl != null && failingUrl != view.url) return
                suesDegrade("页面加载失败")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // 这个 WebView 里会出现统一身份认证的账号密码，证书不可信时必须由用户决定，
                // 不能因为发行渠道不同就静默放行——放行等于把密码暴露给中间人。
                val host = activity
                if (host == null) {
                    // 页面已经离开：默认拒绝，别把一个悬着的 handler 留在那里。
                    handler.cancel()
                    return
                }
                MaterialAlertDialogBuilder(host)
                        .setMessage("SSL证书验证失败")
                        .setPositiveButton("继续浏览") { _, _ ->
                            handler.proceed()
                        }
                        .setNegativeButton("取消") { _, _ ->
                            handler.cancel()
                        }
                        .setCancelable(false)
                        .show()
            }

        }
        binding.wvCourse.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 进度回调也可能晚于 onDestroyView（见 binding 的说明）：没视图就不更新进度条。
                val bar = _binding?.pbLoad ?: return
                if (newProgress == 100) {
                    bar.progress = newProgress
                    bar.visibility = View.GONE
                } else {
                    bar.progress = newProgress * 5
                    bar.visibility = View.VISIBLE
                }
            }
        }
        // 设置自适应屏幕，两者合用
        binding.wvCourse.settings.useWideViewPort = true //将图片调整到适合WebView的大小
        binding.wvCourse.settings.loadWithOverviewMode = true // 缩放至屏幕的大小
        // 缩放操作
        binding.wvCourse.settings.setSupportZoom(true) //支持缩放，默认为true。是下面那个的前提。
        binding.wvCourse.settings.builtInZoomControls = true //设置内置的缩放控件。若为false，则该WebView不可缩放
        binding.wvCourse.settings.displayZoomControls = false //隐藏原生的缩放控件wvCourse.settings
        binding.wvCourse.settings.javaScriptCanOpenWindowsAutomatically = true
        binding.wvCourse.settings.domStorageEnabled = true
        binding.wvCourse.settings.userAgentString = binding.wvCourse.settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
        binding.wvCourse.settings.textZoom = 100
        initEvent()
    }

    private fun initEvent() {

        binding.chipMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.wvCourse.settings.userAgentString = binding.wvCourse.settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
            } else {
                binding.wvCourse.settings.userAgentString = binding.wvCourse.settings.userAgentString.replace("eliboM", "Mobile").replace("diordnA", "Android")
            }
            binding.wvCourse.reload()
        }

        binding.chipZoom.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("设置缩放")
                    .setView(R.layout.dialog_edit_text)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.sure, null)
                    .create()
            dialog.show()
            val inputLayout = dialog.findViewById<TextInputLayout>(R.id.text_input_layout)
            val editText = dialog.findViewById<TextInputEditText>(R.id.edit_text)
            inputLayout?.helperText = "范围 10 ~ 200"
            inputLayout?.suffixText = "%"
            editText?.inputType = InputType.TYPE_CLASS_NUMBER
            val valueStr = zoom.toString()
            editText?.setText(valueStr)
            editText?.setSelection(valueStr.length)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = editText?.text
                if (value.isNullOrBlank()) {
                    inputLayout?.error = "数值不能为空哦>_<"
                    return@setOnClickListener
                }
                val valueInt = try {
                    value.toString().toInt()
                } catch (e: Exception) {
                    inputLayout?.error = "输入异常>_<"
                    return@setOnClickListener
                }
                if (valueInt < 10 || valueInt > 200) {
                    inputLayout?.error = "注意范围 10 ~ 200"
                    return@setOnClickListener
                }
                zoom = valueInt
                binding.wvCourse.settings.textZoom = zoom
                binding.chipZoom.text = "文字缩放 $zoom%"
                binding.wvCourse.reload()
                dialog.dismiss()
            }
        }

        binding.fabImport.setOnClickListener {
            binding.wvCourse.loadUrl(SUES_FETCH_JS)
        }

        binding.btnAutoStop.setOnClickListener {
            suesStopByUser()
        }

        binding.btnBack.setOnClickListener {
            if (binding.wvCourse.canGoBack()) {
                binding.wvCourse.goBack()
            }
        }
    }

    /**
     * 登录完成后，替用户把剩下的导航走完，直到课表页。
     *
     * 只管导航，绝不经手凭据：遇到登录页就停下来等用户，人机验证同理。目标地址一律从当前
     * 页面里读出来、或从当前地址推出来，不写死 WebVPN 那串按目标主机算出来的编码——写死它
     * 等于把网关的部署细节钉进代码，网关一改整条路就断。
     *
     * 支点是教学服务中心（jxfw）的 SSO 入口：从服务门户点「教学服务信息系统」走的就是它，
     * 免二次登录。门户里「新教务系统学生登入」那条会落到 /student/login 要二次登录，必须避开。
     */
    private fun suesAutoEnter(url: String) {
        if (suesAutoDone) return
        // onPageFinished 会对同一份文档重复回调：这份文档处理过就直接跳过。
        // 只认文档身份、不认地址——地址相同也可能是新的一份文档，统一身份认证页就是如此。
        if (!suesDocs.claim()) {
            android.util.Log.d("SUES导入", "忽略重复回调 url=$url")
            return
        }
        android.util.Log.d("SUES导入", "onPageFinished url=$url 等课表页=$suesExpectCourseTable")

        // 每一跳都先验收上一跳的落点。落点不是预期的那一页，说明页面已经不受控，立刻退出自动。
        if (!suesExpectMet(url)) {
            suesDegrade()
            return
        }

        when {
            // 已到课表页：脚本继续往下走，直接取数
            url.contains(SUES_COURSE_TABLE_PATH) -> suesArrive()

            // 已经在教学服务中心里：直接进课表。
            // 这一档必须排在探测之前——jxfw 页面上若也有指向自己的链接，
            // 先探测就会在「打 SSO → 回首页 → 又探测」之间来回跳，形成死循环。
            SUES_JXFW_URL.containsMatchIn(url) -> {
                if (url.contains("/student/login")) {
                    suesDegrade("教学服务中心要求二次登录")
                } else {
                    suesExpectCourseTable = true
                    _binding?.wvCourse?.loadUrl(url.substringBefore("/student/") + SUES_COURSE_TABLE_PATH)
                }
            }

            // 还在统一身份认证页：做两件不涉及凭据的小事，然后等用户完成登录
            url.contains("/cas/login") -> suesHandleCasLoginPage(url)

            // 其余页面：用页面内容判断登录是否完成，而不是看 URL 长得像什么
            else -> suesProbePage(url)
        }
    }

    /** 上一跳的落点是否达预期。预期只验收一次，通过后就清掉，免得影响后面正常的跳转。 */
    private fun suesExpectMet(url: String): Boolean {
        if (!suesExpectCourseTable) return true
        val met = url.contains(SUES_COURSE_TABLE_PATH)
        if (met) suesExpectCourseTable = false
        return met
    }

    /** 打一次 jxfw 的 SSO 入口。它免二次登录，是整条自动链路的支点。 */
    private fun loadSuesSso(prefix: String) {
        _binding?.wvCourse?.loadUrl(prefix + SUES_SSO_PATH)
    }

    /**
     * 到达课表页：脚本继续接管到底——直接取数入库，读完由 [InJavaScriptLocalObj.showSuesData]
     * 结束这个页面、退回课表页。
     *
     * 遮罩保持不动：这一刻用户仍然不该插手。取数失败由 [InJavaScriptLocalObj.suesFailed] 降级成手动；
     * 万一取数脚本没有任何回调，遮罩上的「停止自动脚本」是用户的退路。
     */
    private fun suesArrive() {
        val views = _binding ?: return
        suesAutoDone = true
        views.wvCourse.loadUrl(SUES_FETCH_JS)
    }

    /**
     * 出现未预料到的页面、或某一步没走通时，立刻退出自动并降级为手动。
     *
     * 不重试、不猜兜底路线——猜错只会把用户带得更远。撤掉遮罩、留一行提示，最省事也最安全。
     */
    private fun suesDegrade(reason: String? = null) {
        val views = _binding ?: return // 视图已销毁，没有遮罩要撤、也没有人看提示
        android.util.Log.d("SUES导入", "降级 reason=${reason ?: "(未预料页面)"} url=${views.wvCourse.url}")
        suesAutoDone = true
        suesHideMask()
        if (!reason.isNullOrEmpty()) {
            activity?.let { Toasty.warning(it, reason, Toast.LENGTH_LONG).show() }
        }
        suesShowHint("自己进到「我的课表」，再点右下角按钮")
    }

    /** 手动阶段的一行提示。 */
    private fun suesShowHint(text: String) {
        val views = _binding ?: return
        views.tvAutoHint.text = text
        views.tvAutoHint.visibility = View.VISIBLE
    }

    /**
     * 脚本接管导航时盖上半透明遮罩。
     *
     * 只在脚本真正开始跳转之后才盖：登录页绝不盖，否则用户自己也没法输密码、没法过人机验证。
     */
    private fun suesShowMask() {
        _binding?.llAutoMask?.visibility = View.VISIBLE
    }

    private fun suesHideMask() {
        _binding?.llAutoMask?.visibility = View.GONE
    }

    /** 用户按下「强制停止」：立刻停手并撤掉遮罩，页面交回给用户。 */
    private fun suesStopByUser() {
        suesAutoDone = true
        suesHideMask()
        activity?.let { Toasty.warning(it, "已停止自动脚本，页面交给你自己操作", Toast.LENGTH_LONG).show() }
    }

    /**
     * 探测当前页面上有没有教学服务中心的入口。
     *
     * 「页面上出现了只在认证之后才有的资源卡片」就是登录完成的证据，比拿 URL 形状去猜可靠：
     * 门户是前端应用，认证成功之后没有别的显式信号可用。
     *
     * **必须重试**：门户那些资源卡片是它的前端脚本在页面加载之后才渲染进 DOM 的，
     * `onPageFinished` 触发时往往还没有，只探一次就会永远探不到。探到上限仍探不到时明确降级、
     * 交还给用户——绝不静默停摆，那等于把用户挂在一个不动也不报错的页面上。
     *
     * 回调里把当前地址和探测时的地址复核一次：页面若已经跳走，这次结论就作废。
     */
    @VisibleForTesting
    internal fun suesProbePage(probedUrl: String) {
        val views = _binding ?: return // 视图已销毁：这一发探测无事可做（见 binding 的说明）
        if (suesProbeUrl != probedUrl) {
            suesProbeUrl = probedUrl
            suesProbeAttempts = 0
        }
        views.wvCourse.evaluateJavascript(SUES_PROBE_JS) { value ->
            val current = _binding ?: return@evaluateJavascript
            if (!suesSamePage(probedUrl, current.wvCourse.url)) return@evaluateJavascript
            val result = value.orEmpty().trim().trim('"').replace("\\/", "/")
            if (!result.startsWith(SUES_PROBE_PORTAL)) {
                if (suesProbeAttempts++ < SUES_PROBE_MAX_ATTEMPTS) {
                    postProbeTick(probedUrl)
                } else {
                    suesDegrade("页面没加载出教学服务中心入口")
                }
                return@evaluateJavascript
            }
            val href = result.substringAfter('|', "")
            val prefix = suesAbsolute(suesOrigin(probedUrl), href).orEmpty().substringBefore("/student/")
            if (!prefix.contains("/https/")) {
                suesDegrade("没找到教学服务中心入口")
            } else {
                suesShowMask()
                loadSuesSso(prefix)
            }
        }
    }

    /** 排下一发探测，并把句柄记在 [suesProbeTick] 上，好让 [onDestroyView] 取消它。 */
    private fun postProbeTick(probedUrl: String) {
        val views = _binding ?: return
        val tick = Runnable {
            suesProbeTick = null
            suesProbePage(probedUrl)
        }
        suesProbeTick = tick
        views.wvCourse.postDelayed(tick, SUES_PROBE_INTERVAL_MS)
    }

    private fun suesOrigin(url: String): String =
            Regex("""^https?://[^/]+""").find(url)?.value.orEmpty()

    private fun suesAbsolute(origin: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        return if (href.startsWith("http")) href else origin + href
    }

    /** 两个地址是不是同一个页面。只忽略末尾斜杠，避免把「跳走后的新页面」误判成原页面。 */
    private fun suesSamePage(probed: String?, current: String?): Boolean {
        if (probed.isNullOrEmpty() || current.isNullOrEmpty()) return false
        return probed.trimEnd('/') == current.trimEnd('/')
    }

    /**
     * 统一身份认证页上的两件小事，都不涉及凭据。
     *
     * 一是勾上那个挂着保密提示的复选框（name=rememberMe，勾上等于「记住我」，不是「我已阅读」）；
     * 二是密码过期提示里的「点击跳过」——它会挡住后面所有步骤。
     *
     * 密码过期分两种情况，必须分开处理：跳过之后能往下走，就继续；跳过之后又被弹回同一页，
     * 说明这次不是「可以跳过」而是「必须改密码」，那就不再重试，直接退出自动交还给用户。
     */
    private fun suesHandleCasLoginPage(probedUrl: String) {
        val views = _binding ?: return
        views.wvCourse.evaluateJavascript(SUES_TICK_NOTICE_JS, null)
        views.wvCourse.evaluateJavascript(SUES_PROBE_JS) { value ->
            val current = _binding ?: return@evaluateJavascript
            if (!suesSamePage(probedUrl, current.wvCourse.url)) return@evaluateJavascript
            if (!value.orEmpty().contains(SUES_PROBE_EXPIRED)) return@evaluateJavascript
            if (suesSkipCount > 0) {
                suesDegrade("密码需要先修改")
                return@evaluateJavascript
            }
            suesSkipCount++
            current.wvCourse.evaluateJavascript(SUES_SKIP_EXPIRED_PASSWORD_JS) { skipped ->
                android.util.Log.d("SUES导入", "密码过期提示处理结果=$skipped")
            }
        }
    }

    private fun startVisit() {
        binding.wvCourse.visibility = View.VISIBLE
        binding.llError.visibility = View.GONE
        binding.wvCourse.loadUrl(url)
    }

    internal inner class InJavaScriptLocalObj {

        /**
         * SUES 课表接口返回的 JSON 原文。
         *
         * 课表数据是独立接口给的 JSON，页面上渲染出来的表格反而不带楼宇分组，所以这里直接入库。
         */
        @JavascriptInterface
        fun showSuesData(json: String) {
            launch {
                try {
                    val result = viewModel.importFromSues(json)
                    Toasty.success(requireActivity(),
                            "成功导入 ${result.courseCount} 门课程(ﾟ▽ﾟ)/\n已切换到导入的课表").show()
                    // 教务系统里"有课、但一条时间都没排"的课程不会进课表，把名字带回课表页，
                    // 由那边的导入提示一并说清楚——不能让人自己去发现少了一门课。
                    requireActivity().setResult(RESULT_OK, Intent().apply {
                        putStringArrayListExtra(Const.EXTRA_UNSCHEDULED_COURSES,
                                ArrayList(result.coursesWithoutSchedule))
                    })
                    requireActivity().finish()
                } catch (e: Exception) {
                    // 入库失败也不能把遮罩留着：那会把用户锁在一个点不动的页面上
                    suesDegrade("导入失败：${e.message}")
                }
            }
        }

        /**
         * 取数流程失败：说明原因并降级为手动，用户可以在课表页自己点右下角按钮重试。
         */
        @JavascriptInterface
        fun suesFailed(message: String) {
            suesDegrade(message)
        }

        /** 取数脚本实际用的接口地址，排查「取到的不是课表」时唯一有用的线索。 */
        @JavascriptInterface
        fun suesDebug(info: String) {
            android.util.Log.d("SUES导入", info)
        }
    }

    override fun onDestroyView() {
        // 先取消还没跑的那一发探测：postDelayed 投出的消息不会因为视图销毁而消失，
        // 而它下一步就会去读 binding（见 binding 的说明）。
        suesProbeTick?.let { binding.wvCourse.removeCallbacks(it) }
        suesProbeTick = null
        binding.wvCourse.webViewClient = WebViewClient()
        binding.wvCourse.webChromeClient = WebChromeClient()
        binding.wvCourse.clearCache(true)
        binding.wvCourse.clearHistory()
        binding.wvCourse.removeAllViews()
        binding.wvCourse.destroy()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 课表页路径。 */
        private const val SUES_COURSE_TABLE_PATH = "/student/for-std/course-table"

        /** 教学服务中心（jxfw）的 SSO 入口：从服务门户点「教学服务信息系统」走的就是它，免二次登录。 */
        private const val SUES_SSO_PATH = "/student/sso/login"

        /** 教学服务中心在 WebVPN 下的地址形态：/https/<主机编码>/student/… */
        private val SUES_JXFW_URL = Regex("""/https/[0-9a-f]+/student/""")

        /** 探测结果里表示「已登录的 WebVPN 门户，且页面上带着教学服务中心入口」。 */
        private const val SUES_PROBE_PORTAL = "portal"

        /** 门户的资源卡片由前端脚本后渲染，探测按这个间隔重试这么多轮（约 9 秒）。 */
        private const val SUES_PROBE_INTERVAL_MS = 600L
        private const val SUES_PROBE_MAX_ATTEMPTS = 15

        /** 探测结果里表示「密码过期提示」。 */
        private const val SUES_PROBE_EXPIRED = "expired"

        /**
         * 探测当前页面的身份，返回 expired / cas / portal|<入口地址> / other。
         *
         * 判据全部取自真实页面。**顺序有讲究**：密码过期那一页同样带着 #rememberMe 复选框，
         * 所以必须先判更具体的「点击跳过」按钮，否则过期页会被误判成普通登录页，永远跳不过去。
         *
         * 入口地址有两条判据：首选卡片上的主机名；门户在窄屏布局下若不显示主机名，退而用
         * 「改写地址是 /https/ 且文字含教务」——`/https/` 这一条足以把老教务系统
         * （`jxxt`，走的是 `/http/`）排除在外。
         */
        private const val SUES_PROBE_JS = "(function(){try{" +
                "var b=document.querySelectorAll('input.login_btn');" +
                "for(var i=0;i<b.length;i++){if(b[i].value==='点击跳过')return 'expired';}" +
                "if(document.getElementById('username')||document.getElementById('password')||" +
                "document.getElementById('rememberMe'))return 'cas';" +
                "var a=document.querySelectorAll('a[href]');" +
                "for(var j=0;j<a.length;j++){var t=a[j].textContent||'';" +
                "if(t.indexOf('jxfw.sues.edu.cn')>=0)return 'portal|'+(a[j].getAttribute('href')||'');}" +
                "for(var k=0;k<a.length;k++){var t2=a[k].textContent||'';var h2=a[k].getAttribute('href')||'';" +
                "if(h2.indexOf('/https/')>=0&&t2.indexOf('教务')>=0)return 'portal|'+h2;}" +
                "return 'other';}catch(e){return 'error';}})()"

        /** 勾选统一身份认证页上那个挂着保密提示的复选框（name=rememberMe）。 */
        private const val SUES_TICK_NOTICE_JS = "(function(){" +
                "var e=document.getElementById('rememberMe');" +
                "if(!e||e.checked)return;" +
                "e.checked=true;" +
                "e.dispatchEvent(new Event('change',{bubbles:true}));})()"

        /**
         * 点掉密码过期提示里的「点击跳过」。
         *
         * 这个提示只对密码已过期的账号出现，会挡住后面所有步骤；「点击跳过」的 onclick 就是
         * 重新加载当前页，跳过之后 CAS 才把会话放行到 service。只认 value 完全等于「点击跳过」
         * 的按钮，避免误点。
         */
        private const val SUES_SKIP_EXPIRED_PASSWORD_JS = "(function(){" +
                "var e=document.querySelectorAll('input.login_btn');" +
                "for(var i=0;i<e.length;i++){" +
                "if(e[i].value==='点击跳过'){e[i].click();return 'skipped';}}" +
                "return 'na';})()"

        /**
         * SUES 课表取数脚本。
         *
         * 课表接口地址里的 semesterId 与 dataId 只有页面自己知道，所以不去猜：页面既然已经为渲染
         * 课表发过那个请求，浏览器的资源计时里就存着它的完整地址（含 WebVPN 改写后的形式），
         * 直接拿它用同一会话、同源再取一次原文即可。这样既不依赖任何 URL 规则，也不受 WebVPN
         * 把路径改写成什么样子影响。
         */
        private const val SUES_FETCH_JS = "javascript:(function(){" +
                "var urls=[];" +
                "try{var es=performance.getEntriesByType('resource');" +
                "for(var i=0;i<es.length;i++){" +
                "if(es[i].name.indexOf('course-table/get-data')>=0){urls.push(es[i].name);}}}catch(e){}" +
                "if(!urls.length){" +
                "window.local_obj.suesFailed('没有找到课表接口请求。请确认已经登录并停在课表页面，再点一次按钮。');" +
                "return;}" +
                "var target=urls[urls.length-1];" +
                "window.local_obj.suesDebug(target);" +
                "fetch(target,{credentials:'include'}).then(function(r){return r.text();})" +
                ".then(function(t){window.local_obj.showSuesData(t);})" +
                ".catch(function(e){window.local_obj.suesFailed('取回课表数据失败：'+e);});})()"

        @JvmStatic
        fun newInstance(url: String = "") =
                WebViewLoginFragment().apply {
                    arguments = Bundle().apply {
                        putString("url", url)
                    }
                }
    }
}

/**
 * `onPageFinished` 的去重账本。
 *
 * 同一份文档可能被重复回调，必须只处理一次；但**换文档不等于换地址**，所以身份只能来自
 * 「第几份文档」，不能来自地址。统一身份认证页就是活例子：提交账号密码的前后地址逐字节相同
 * （表单 action 是未被网关改写的主机名，POST 之后 302 回同一个 WebVPN 地址），拿地址当身份
 * 会把登录之后那一份整个吞掉。
 */
internal class SuesDocLedger {

    private var started = 0
    private var handled = -1

    /** 主文档开始加载：换了一份新文档。 */
    fun onDocumentStarted() {
        started++
    }

    /** 这份文档是否还没处理过。是则认领（记下已处理），否则说明是同一份文档的重复回调。 */
    fun claim(): Boolean {
        if (handled == started) return false
        handled = started
        return true
    }
}
