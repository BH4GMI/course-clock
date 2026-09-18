package courseclock.timetable.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单（Doze 豁免）。
 *
 * ## 为什么这个 App 必须管这件事（真机实测）
 *
 * 提醒走的是 `AlarmManager.setExactAndAllowWhileIdle`。按 AOSP 的设计，这类闹钟在 Doze 里
 * 也能送达，只受一个"每小时若干次"的配额限制。但在这台 Xiaomi/HyperOS 上实测不是这样：
 *
 * ```
 * RTC_WAKEUP ... courseclock.timetable      tag=*walarm*:WAKEUP_REFRESH_TODAY
 *   type=RTC_WAKEUP origWhen=2026-09-15 19:33:18.135 window=0 exactAllowReason=compat
 *   policyWhenElapsed: requester=-1m47s819ms ... ssru=+364d23h58m7s181ms power_pending=--
 *   whenElapsed=+364d23h58m7s181ms                    ← 被推到约一年后
 * ```
 *
 * `ssru` 是 MIUI 私有的一条限制项，最终 `whenElapsed` 取各策略最大值，于是这枚已经过点
 * 107 秒的闹钟**继续躺在表里不动**，亮屏也不解冻。同一时刻 `com.google.android.gms` 的
 * `ssru` 是负数（不受限）—— 差别只有一个：它在 `dumpsys deviceidle whitelist` 里。
 *
 * 用 `dumpsys deviceidle whitelist +courseclock.timetable` 加上去之后立刻复测：
 * 同一枚闹钟的 `ssru` 变成负数、`flags` 从 `0x21`（compat 逐空配额）变成 `0x9`，
 * 积压的三枚闹钟在 20 秒内全部送达。**结论：要不要得到 Doze 豁免，决定了提醒在整夜待机
 * 之后还在不在。**
 *
 * ## 为什么是"申请"而不是"让用户自己去设置里找"
 *
 * 上面的白名单是系统设置里的一项，用户当然可以手动打开；但正确的做法是 App 主动发起
 * [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]，由系统弹出**一次确认框**，
 * 点一下即可 —— 这正是平台为此提供的机制（配合清单里的
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限）。把用户丢进设置里翻菜单不是"用户的事"，
 * 那是 App 没把话说清楚。用户仍然拥有最终决定权：不点就是不加白名单，提醒照旧按系统
 * 允许的方式工作，只是整夜待机后可能迟到。
 *
 * ## 不做什么
 *
 * 不用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 作为首选：它只把用户送到设置列表，
 * 还得自己找 App。只有在系统不支持直接申请（老版本）时才退回它。
 */
object BatteryOptimization {

    fun miuiIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setPackage("com.miui.securitycenter")
            .setData(Uri.parse("package:${context.packageName}"))

    fun hasMiuiSettings(context: Context): Boolean =
            miuiIntent(context).resolveActivity(context.packageManager) != null

    fun needsMiuiStep(context: Context): Boolean = hasMiuiSettings(context) &&
            !context.getPrefer().getBoolean(Const.KEY_HYPEROS_BATTERY_CONFIRMED, false)

    fun openMiuiForResult(activity: Activity, requestCode: Int): Boolean = try {
        activity.startActivityForResult(miuiIntent(activity), requestCode)
        true
    } catch (e: RuntimeException) {
        false
    }

    fun aospIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setPackage("com.android.settings")
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * 本 App 现在是否已被豁免电池优化。
     *
     * `PowerManager.isIgnoringBatteryOptimizations` 就是 Doze 自己的判定入口，
     * 与 `dumpsys deviceidle whitelist` 看的是同一份名单，不要自己另建一份状态。
     *
     * Doze 白名单本身就是 API 23 引入的：这个方法在 API 21/22 上**根本不存在**
     * （SDK 的 `api-versions.xml` 记 `since=23`，而本工程 `minSdk 21`），直调会
     * `NoSuchMethodError` —— 而它被 `SettingsList.batteryStateText()` 在每次构建设置页
     * 那一行时调用，于是 Android 5.0/5.1 打开设置页就崩。老机器上的正确答案是 `false`：
     * 那里没有白名单可查，这一行该显示「未设置」。
     *
     * 版本判断必须**直读 `Build.VERSION.SDK_INT` 且与本调用同处一个方法**：lint 的
     * `NewApi` 只认这种形态。把版本改成参数传进来，lint 立刻失去这条保护（实测），
     * 所以这里不为测试开口子 —— 兼容下限由 lint 把关，见下方单测说明。
     */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 申请豁免。已豁免时什么都不做（调用方应先查 [isExempt]）。
     *
     * 用 `package:` 形式的 data 让系统直接定位到本 App，弹出的是"是否允许 [App] 不受
     * 电池优化限制"的点按确认框，而不是让用户自己去列表里找。
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val intent = aospIntent(context).apply {
            // 从设置页/广播接收器发起时可能不在前台，加一个任务栈标志更稳。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 个别定制系统没有这个 Activity。退回"打开电池优化设置列表"，让用户自己找 —— 
            // 这是降级路径，不是主路径，所以只在真的点不动时才走，并且要让用户看到说明。
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .setPackage("com.android.settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (fallback: RuntimeException) {
                android.widget.Toast.makeText(context, "无法打开系统电池设置，请在系统设置中搜索电池优化", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 明确交给 Android 设置，避免 HyperOS 优先级更高的处理器截获。
     * 结果码不代表授权；返回前台后重新调用 isExempt。返回值表示页面是否打开。
     */
    @SuppressLint("BatteryLife")
    fun requestForResult(activity: Activity, requestCode: Int): Boolean {
        val intent = aospIntent(activity)
        return try {
            activity.startActivityForResult(intent, requestCode)
            true
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .setPackage("com.android.settings"))
                true
            } catch (fallback: RuntimeException) {
                false
            }
        }
    }

    /**
     * 打开 HyperOS/MIUI 的「省电策略」页（用户手动设置的「无限制」就在这里）。
     * 私有组件：厂商可能改名或移除，调用方必须准备好降级。返回是否成功拉起。
     */
    fun openMiuiPowerKeeper(activity: Activity): Boolean = try {
        activity.startActivity(Intent().apply {
            component = android.content.ComponentName(
                    "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            putExtra("package_name", activity.packageName)
            putExtra("package_label", activity.getString(activity.applicationInfo.labelRes))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (e: Exception) {
        false
    }
}
