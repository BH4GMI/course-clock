# 时间测试版

## 安装与入口

在 `android` 目录执行 `./gradlew.bat :app:assembleSimulation`，产物为
`app/build/outputs/apk/simulation/app-simulation.apk`。
名称为“课钟测试版”，包名 `courseclock.timetable.test`。使用 debug 签名，可与普通版共存，
课表、设置、通知、小组件分别存储。测试包不会自动读取普通版的私人课程，请单独导入课表。
Android Studio 的 Build Variants 选择 `simulation`，从设置顶部进入“时间实验室”。

## 同步到普通版

通知和小组件业务位于 `src/main`，由普通版和测试版共用，无需复制测试版代码。
普通版 `debug` / `release` 使用 `src/standard` 的真实时钟，不包含 `simulation` 的
时间实验室、倍速控制和测试服务。同步功能不迁移测试课表、模拟时间或测试版设置。

普通包 `courseclock.timetable` 继续使用真实时间。最新课前通知持续显示小时和分钟，日视图超过
60 分钟显示一位小数小时，60 分钟以内显示分钟；这套业务与 Android 原生实时通知均由两版共用。
本机签名使用用户提供的 DPAPI 脚本，无需读取或输入口令；debug 构建即使使用发布证书签名，
也仍是 debug 构建，不等同于启用 R8 的 release 构建。
普通版检查命令为 `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。

## 时间控制

- 日期按钮选择测试日期，时间按钮输入时分；全天滑块定位到秒，松手提交并暂停。
- 左右按钮分别后退、前进一分钟；播放按钮继续或暂停。
- 倍速支持 0.25、0.5、1、2、5、10、30、60、120；播放中换速连续推进，不跳时间。
- 真实模式下选择倍速会进入暂停模拟，点击播放后开始推进。
- 拖动或选日期属于重新回放：清除测试包的旧提醒及隐藏记录，重新计算后续事件，不补发跳过的旧提醒。
- “恢复真实时间”重排当前真实时刻的课程提醒并停止测试服务，不修改手机时间。
- 进程退出再打开时恢复上次保存的模拟时刻，但始终暂停，避免后台补跑。

## 通知与后台

课程时间由 `CourseTime` 统一注入。普通 debug/release 编译真实时钟实现，继续使用
`AlarmManager.setExactAndAllowWhileIdle` / `setExact`；simulation 单独编译模拟时钟、控制页与服务。
模拟推进基于 `SystemClock.elapsedRealtime`，使用 `Handler.postAtTime` 投递同一业务
`PendingIntent`，不把倍速映射成受系统限流的密集闹钟。旧时间会话的广播不会重新投递课程提醒。

模拟期间显示独立的“测试时间控制”前台服务通知，可暂停、继续、恢复真实时间。
播放持有 CPU 锁，暂停及服务销毁释放；服务不自动重启。测试结束应恢复真实时间，减少耗电。
课程通知仍遵守测试包自己的总开关、普通提醒、状态模式和系统权限，不绕过通知授权。
Android 原生倒计时仅按真实时间运行，因此模拟时使用虚拟剩余分钟，状态和小组件最多每真实秒刷新一次。

Android 原生实时通知是否提升展示、锁屏与后台限制仍由系统控制；倍速测试不能代替
真实 Doze、准确响铃时机或原生实时通知授权的实机验收。不使用 HyperOS 焦点协议。
极高倍速时中间视觉帧可能被跳过，课程边界仍独立调度。课前普通状态按分钟变化，课程进行中才请求实时通知。

## 研究与验证

采用已有 Android SDK / AndroidX / Kotlin 协程，无新增第三方依赖。
参考机制：Android `SystemClock`、`Handler`、`AlarmManager` 和前台服务，当前 compileSdk 36。
初次时钟开发时官方站点访问超时；后续原生实时通知改造已核对 Android 官方文档，见实现手册。
未采用修改系统时钟、Hook 或密集系统闹钟，避免影响其它应用及系统限流。

回归命令：`./gradlew.bat :app:testDebugUnitTest :app:testSimulationUnitTest :app:lintSimulation :app:assembleSimulation`。
`TimelineTest` 覆盖暂停、倍速与截止点计算；`SimulationClockTest` 覆盖真实时间隔离、恢复暂停、
PendingIntent 加速投递及旧会话失效。完整回归同时覆盖普通版已有通知、小组件与设置行为。
