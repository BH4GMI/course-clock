package courseclock.timetable.schedule_import

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import courseclock.timetable.schedule.ScheduleActivity
import courseclock.timetable.base_view.BaseActivity
import courseclock.timetable.utils.AppWidgetUtils
import courseclock.timetable.utils.Const
import es.dmoral.toasty.Toasty

class LoginWebActivity : BaseActivity() {

    private companion object {
        const val TAG = "LoginWebActivity"
    }

    private val viewModel by viewModels<ImportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.extras?.getString("import_type")?.let {
            viewModel.importType = it
        }
        intent.extras?.getString("school_name")?.let {
            viewModel.school = it
        }

        val fragment = when (viewModel.importType) {
            "file" -> {
                FileImportFragment()
            }
            Common.TYPE_SUES -> {
                // 入口契约：这条路径必须带 url。带不齐就明确结束，别落到一个空白页上，
                // 更不是在这里崩给用户看（本 Activity 因为要接外部「打开分享文件」是导出的，
                // extras 由调用方说了算）。
                val url = intent.getStringExtra("url")
                if (url.isNullOrBlank()) {
                    Toasty.error(this, "导入参数不完整，请回到 App 里重新开始导入>_<",
                            Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                WebViewLoginFragment.newInstance(url)
            }
            else -> null
        }
        fragment?.let { frag ->
            val transaction = supportFragmentManager.beginTransaction()
            transaction.add(android.R.id.content, frag, viewModel.school)
            transaction.commit()
        }

        if (fragment == null && intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            // 判定与导入器**共用同一个函数**（path **或** 显示名）。以前这里自己抄了一份"只看
            // uri.path"，而真实的「分享 / 文件管理器打开」给的是 content:// URI，path 长这样：
            // `/document/1234` —— 文件名只在 DISPLAY_NAME 里。于是合法文件在入口就被误拒。
            if (uri == null || !viewModel.looksLikeWakeupSchedule(uri)) {
                Toasty.error(this@LoginWebActivity, "只能打开 .wakeup_schedule 文件哦>_<", Toast.LENGTH_LONG).show()
                // 这里**不能** backToSchedule()：那是 FLAG_ACTIVITY_CLEAR_TASK or NEW_TASK，
                // 等于把整个任务清掉、重启到首页 —— 用户看到的就是"闪退 + App 自己重启"。
                // 这条分支没有任何新数据要展示，结束自己、回到用户来的地方就够了。
                finish()
                return
            }
            // 这条路径只负责导入：不再顺手把 FileImportFragment 加上来——导入本身与那个
            // 文件选择页无关，失败时 toast 叠在一个无意义的页面只会让人困惑。
            launch {
                try {
                    viewModel.importFromFile(uri)
                    Toasty.success(this@LoginWebActivity, "导入成功(ﾟ▽ﾟ)/已切换到导入的课表", Toast.LENGTH_LONG).show()
                    // 这条路经 CLEAR_TASK 重启，主页的 onActivityResult 永远收不到结果；
                    // 小部件必须在这里刷新，否则周视图停留在旧课表上。
                    AppWidgetUtils.refreshAllWidgets(applicationContext)
                    backToSchedule()
                } catch (e: Exception) {
                    // 只报 ViewModel 给的**人话**（"打不开 / 格式不对 / 文件过大"都已经在那边
                    // 收敛成面向用户的句子）；意外异常没有 message 时用兜底文案。绝不把
                    // `open failed: ENOENT (No such file or directory)` 这类系统原文甩给用户。
                    Toasty.error(this@LoginWebActivity,
                            e.message ?: "导入失败>_<文件可能已损坏或读不到", Toast.LENGTH_LONG).show()
                    // 失败后必须**自己结束**：这条路刻意没有加任何 Fragment，停在原地就是一个
                    // 空白页（toast 一消失什么都看不到），用户只能靠返回手势猜着出去。
                    // 导入没成功就没有新数据，不需要像成功那样重启到首页，直接退出回到来的地方。
                    finish()
                }
            }
        } else if (fragment == null) {
            // 既没有可识别的 import_type、也不是「打开文件」：没有任何内容可展示，更要紧的是
            // **用户什么都没发起**。本 Activity 是导出的（要接外部分享），而它的 intent-filter
            // 只能粗到 MIME 类型 —— 任何应用发一个 `application/octet-stream` 的 ACTION_VIEW
            // （这是"未知二进制文件"的通用兜底类型）都可能把这里拉起来，甚至不带 data。
            // 这种"被误拉起"不该弹一句"导入参数不完整"：用户没有导入动作，那句话只会让人以为
            // 是自己把导入弄坏了。静默结束，留一条日志给排查用。
            Log.i(TAG, "既无 import_type 也非 ACTION_VIEW 的外部启动，直接结束")
            finish()
        }
    }

    /** 导入动作已经把结果写进数据库，重启到首页才能让用户看见新表。 */
    private fun backToSchedule() {
        val intent = Intent(this@LoginWebActivity, ScheduleActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == Const.REQUEST_CODE_IMPORT_FILE) {
            launch {
                try {
                    viewModel.importFromFile(data?.data)
                    Toasty.success(this@LoginWebActivity, "导入成功(ﾟ▽ﾟ)/已切换到导入的课表", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    Toasty.error(this@LoginWebActivity,
                            e.message ?: "导入失败>_<文件可能已损坏或读不到", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
