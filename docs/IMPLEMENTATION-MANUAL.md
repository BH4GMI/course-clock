# 课钟 · 实现审查与改进手册

> 审查日期：2026-09-15。范围：全仓库（android/、coursetime/、design/、docs/、构建配置）。
> 审查当时还在本机的 `tools/` 开发脚本已于 2026-09-18 随仓库重建一并删除，本文提及它的地方
> 保留原结论但不再指向该目录。
> 方法：逐文件通读约 1.5 万行源码 + 逐项联网核对依赖现状与平台机制；所有结论都给出
> 代码位置（`文件:行`）或外部来源，未验证的内容一律标注。
>
> 本手册只记录与建议，不随本审查修改任何现有文件。改进项按优先级分级：
> **P0 正确性缺陷** → **P1 依赖与构建现代化** → **P2 平台能力与路线** → **P3 一致性**。
> 每一项都标注了"证据"（现状）与"建议"（更优实现）以及验证方式。

---

## 0. 总体结论

### 2026-09-21：课程通知实现更新

本节记录当前实现，取代旧版「连堂抑制下一课提醒」「通知跟随小组件每分钟更新」规则。

- `CourseNotificationSettings` 保存用户意愿：总闸默认关；新安装上课偏好开、提前 20 分钟，
  下课偏好关、提前 0 分钟；同时提醒合并默认开。升级保留旧开关，旧常驻开启迁移为仅通知栏。
- `CourseReminderScheduler.Occurrence` 是提醒与状态共用的课程实例，含完整名称、教室、起止绝对时间。
  下课墙钟早于上课按次日处理；起止相等或缺失作息不生成实例。昨日跨夜课、今日与次日均参与状态校验。
  免听课不提醒，遵守学期、单双周和作息分组。合并只发生在相同触发时刻，不压掉或延后下一课。
- 保留 `schedule_reminder` 高重要级、`schedule_ongoing` 低重要级两个既有渠道，不绕过系统声音设置。
  普通通知展开保留每门课的完整名称、时段与教室，锁屏使用私密可见性及脱敏 `publicVersion`。
  课前提醒开课失效，准点上课提醒最多五分钟且不超过下课，下课提醒结束后五分钟失效。
  投递前重新校验当前课表及开关；持久记录清理已投递通知，跨午夜重复广播不会重新弹出。
- 状态模式为关闭、自动（Android 实时通知）、仅通知栏。有有效的当前或未来课程时持续显示；课前显示
  “距离上课 X 小时 Y 分钟”，分钟向上取整，跨日课程注明日期。正在上课显示当前所有冲突课程，
  学期内无后续有效课程时撤销。课表和作息在同一 Room 事务中读取，每条安排只展开最近的未隐藏实例，
  包含仍有效的隐藏实例供边界清理；遵守单双周、周日起始、学期范围、免听及跨午夜规则。
  纯文字下一分钟刷新使用 `setExact(RTC)`，休眠时等待设备唤醒；课程起止边界独立排一枚
  `RTC_WAKEUP`，不依赖文字刷新链续排，普通提醒保持原精度。API 24+ 继续用系统 Chronometer。
  小组件仍独立刷新。隐藏本次持久到本次课程结束，下一次实例不受影响；系统 timeout 的 deleteIntent
  依据展示有效期判定，不当成用户隐藏。总闸关、改课或换表立即重算、清理旧通知。
- 自动模式使用 Android 16 `NotificationManager.canPostPromotedNotifications` 查询权限，声明
  `POST_PROMOTED_NOTIFICATIONS`，通过官方 `EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升同一条标准通知。
  仅进行中的课程请求提升；未开始课程、低版本、未授权时保留标准通知。不再调用任何厂商焦点协议。
  模拟时间不能使用系统真实倒计时，使用 `setShortCriticalText` 显示虚拟剩余分钟。
- 设置的两个提前量分别依赖总闸及对应提醒开关；声音设置跳系统渠道页，权限状态与应用开关分开显示。
  后台限制仍使用既有 `BatteryOptimization` 引导。无新增依赖、服务、root 要求或导出接收器。

调研选型：保留现有 AndroidX Core 1.12.0、Gson 和 AlarmManager/NotificationManager；
采用 [Android 原生 Live Updates 文档](https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=en)
（2026-09-16 更新，2026-09-21 在线核验），compileSdk 36；minSdk 21、targetSdk 29 不变。
标准 BigTextStyle、非最低重要级、ongoing、标题、非自定义布局满足基础展示约束，实际提升由系统决定。
SDK 36.1 才导出 Java 常量 `EXTRA_REQUEST_PROMOTED_ONGOING`，当前 SDK 36 使用同一公开 extras 键
`android.requestPromotedOngoing`，不反射方法、不伪造提升成功标志。课程状态由用户主动开启，且保留隐藏本次动作。

历史故障：旧 HyperOS 适配缺少 `textButton[].actionTitle`，实机 `ModuleTextButton4ViewHolder`
在 `Html.fromHtml` 路径空指针并反复重启 SystemUI。已备份测试包设置并退回 SHADE 止损。
根据用户要求，不再保留修补后的厂商方案，而是彻底移除厂商实现与对应测试；新增 Android 16 原生请求、
权限关闭、仅通知栏、未来课程不提升以及低版本回退测试。课表数据不因故障恢复而修改。
未采用云推送（本地课表无须服务端）、前台服务（无持续任务）、私有 API 反射或 Hook。
不将未来日历提醒提升为 Live Update，遵守官方使用场景要求。
系统超时触发删除回调的依据为 [AOSP NotificationManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/notification/NotificationManagerService.java) 的 `REASON_TIMEOUT` 路径。

验证覆盖：迁移、开关依赖、同刻合并、冲突、跨夜、隐藏/超时区别、通知隐私、失效与去重、广播载荷及焦点参数。
原生权限查询、提升请求与实际展示是不同验收，不能仅凭请求字段存在就宣称已提升；旧 Android 真机兼容性仍需独立设备验证。

日视图课前超过 60 分钟显示“约 X.X 小时”，按向上取整的剩余分钟四舍五入到十分之一小时；
例如 61 分钟为“约 1.0 小时”、90 分钟为“约 1.5 小时”、99 分钟为“约 1.7 小时”。
60 分钟以内显示“还有N分钟上课”，不足一分钟显示 1 分钟，开课后切换原有上课/下课状态。
小时变化约每六分钟一次，仅排变化刻度；两个日视图 provider 都参与刷新，通知开关不影响小组件。
窄布局使用平台 `RelativeSizeSpan(0.85f)` 收小小数部分，仍不足时允许状态换行，保持原有蓝色及信息优先级。

本轮机制核验（2026-09-21）：主站连接超时后，已在线读取官方中国站
[AlarmManager](https://developer.android.google.cn/reference/android/app/AlarmManager)、
[RelativeSizeSpan](https://developer.android.google.cn/reference/android/text/style/RelativeSizeSpan) 和
[Android 14 通知行为](https://developer.android.google.cn/about/versions/14/behavior-changes-all)。
复用 compileSdk 36、AndroidX Core 1.12.0、Room 2.6.1，无新增依赖。
不使用每分钟唤醒、前台保活服务或强制防划走：Android 14 起 ongoing 通知仍允许用户主动划走，
应用尊重隐藏本次；“常驻”表示有课程时持续展示，不表示绕过系统与用户控制。

这是一个实现质量**远高于平均水平的单校定制 fork**。提醒调度（`CourseReminderScheduler`）、
分楼作息模型（`CourseTimes`）、时间轴渲染（`ScheduleUI` + `blockBox`）、SUES 导入链
（`WebViewLoginFragment` + `SuesEamsImporter`）都有真机实证支撑的设计文档与 95+ 项单元
测试。改进空间不在核心逻辑，而集中在四处：

1. **上游遗留代码未被 fork 的标准覆盖**（吞错、资源泄漏、弃用 API、死代码）——fork 给
   自己立的规矩（"不吞错、不在症状处打补丁"）在新代码里执行得很好，但上游搬过来的
   `CourseUtils` / `ViewUtils` / `ICalUtils` / `UpdateUtils` 里还残留着违规实现。
2. **依赖与构建体系停在 2020 年代初**：kapt、alpha 版 appcompat/material、jcenter 依赖 +
   jetifier、Gson/BRVAH/Toasty 旧版。构建功能正常，但每一项都是已知的更优做法。
3. **`targetSdk 29` 是一个有文档支撑的刻意选择**（对 MIUI 闹钟策略的权衡），但它有明确的
   升级成本清单，本手册把它整理成路线图而不是"要不要升"的争论。
4. **同一份事实存在两处定义**（作息表数据在 `coursetime` 模块与 `SuesEamsImporter` 各一份），
   目前靠人工对账。

---

## 1. 必须保留的设计（改进时不得倒退）

### 小组件自适应显示（2026-09-20）

本节描述当前实现，替代 2026-09-18 的固定空间降级顺序。底板、圆角、配色、字体家族、强调色和倒计时刷新机制不变。

- 入口为 2×2「当天课程（小）」、4×2「当天课程」、4×4「一周课程」。前两个共用日视图实现；放大日视图不会自动变成周课表。支持拖动到 5×2 和其他中间尺寸，格数对应的实际 dp 由桌面决定。
- 尺寸统一从 `AppWidgetUtils.cardBoxPx` 读取：竖屏 minWidth/maxHeight，横屏 maxWidth/minHeight；缺失时取 provider 的默认尺寸。日视图大卡从整卡 250×250dp 起生效，横条要求至少 250dp 宽且宽高比不小于 1.65。判据由表头、正文共享。
- 日视图使用 Android 原生测量确定空间预算。先收紧间距、收起已结束摘要，再去掉重复大数字，随后让下一节摘要、状态让位；优先保住课名、地点、完整时段。短课名不会挤掉长课名的当前课程。多门同时上课优先于历史记录，后续摘要会区分「同时上课」和「接下来」。
- 横条仍是日期、课程信息、状态三列；仅当完整时段和课程识别宽度足够时保留两侧辅助列，不再固定挤占中列。宽 5×2 可保留更多文字，小 4×2 可以省去重复日期。日视图正文不靠滚动。
- 课名尽量保持两行；空间不足时先收至正文基准字号，再改为一行。极小格的核心文字最低 12sp；时段宽度不足时可单独收至 10sp，再不足才分行，不省略起止时刻。系统放大字体仍参与实际测量，而不是按固定字符数裁切。
- 长课名和地点优先保留首尾；多行文字仅在最后一行中间省略，保留课程分册、实验编号与房间号。单行中间省略用 `singleLine`，避免 `maxLines=1` 在部分设备的已知兼容性问题。无障碍描述保留课程完整信息，不把省略后的显示文本当作唯一信息源。
- 表头仍为左标题、右计数的一行 12sp 字。按实际字体宽度决定是否显示计数；窄格先省去星期、保留月日，不使用固定 180dp 阈值。计数和正文复用 `TodayColorfulService.loadDay`，固定时钟测试同时驱动表头与正文。
- 四种空态保持：没有课表、开学日期无效、今天无课、全部上完。小格允许提示换行，先为完整提示留空间，再决定是否保留图标；整体居中。大日视图仅在今天无课时展示月份日历，全部上完不展示日历。
- 周视图按实例实际宽度减去内容边距绘图，不再先画屏幕宽截图再缩放。仍保留七日网格、用户行高和课程色块；一屏放不下时由原生 `ListView` 上下滚动，不能承诺任何高度都一屏显示整周。时间栏和星期表头使用一致的列宽权重，避免时间被挤出边界。
- 周课程块按完整文字行分配容量：课名优先至少两行（有足够行数时），其次地点，再是重复时间、单双周等辅助信息。窄列内边距为 2dp，字号按可用宽度适度缩小但不低于 10dp；用户原本设为 8/9dp 时不强制放大。地点最后一行优先保留尾部编号。实际文字换行与省略使用 `StaticLayout`/`TextUtils`，不手写字符宽度算法。
- 周课程块按 `CourseTimes.blockBox` 的真实区域判断覆盖，处理连堂和不同开始节次的部分交叠。本周正常课优先于免听、非本周；被覆盖课程不再绘制相互遮挡的文字，保留原有三角标记，有余量时显示「另有 N 门」。完整本周课程仍在无障碍描述中，详细冲突信息可在应用课表查看。
- 周视图服务身份改为 `content:<tableId>#<widgetId>`，日视图仍是 `content:<widgetId>`。实例 id 必须参与 `Intent.FilterComparison`，防止同一课表不同尺寸的组件共用工厂。旧周视图 URI 在下一次更新时替换，不涉及数据库迁移。
- 改变尺寸仍通过 `onAppWidgetOptionsChanged` 重画当前实例；不新增缓存历史，不增加轮询。主课表不启用周小组件专用文字预算，`TipTextView` 改尺寸或重新绑定文字时会清理旧排版缓存。
- 小组件保持纯展示，不新增点击或周次切换按钮。选择器继续只声明 `previewImage`，不启用会被厂商宿主拦截点击的 `previewLayout`。三种入口各有浅色、深色预览，来自真实布局；周预览展示实际可视部分，不把整周位图压扁。

#### 选型与核验

复用 Android 平台 `AppWidgetManager`、`View.MeasureSpec`、`TextView`、`StaticLayout`、`TextUtils`、`Rect.intersects`，以及现有 AndroidX Core 1.12.0、Robolectric 4.16。保持 minSdk 21 / compileSdk 34 / targetSdk 29，没有新增依赖、数据库 schema、构建或部署配置。

2026-09-20 在线核验 Android 官方中国镜像（主站连接超时）：
- [小组件布局与尺寸](https://developer.android.google.cn/develop/ui/views/appwidgets/layouts?hl=en)
- [StaticLayout.Builder](https://developer.android.google.cn/reference/android/text/StaticLayout.Builder?hl=en)
- [TextUtils.TruncateAt](https://developer.android.google.cn/reference/android/text/TextUtils.TruncateAt?hl=en)

未采用 Compose/Glance：当前 RemoteViews 服务及原生文字测量足以完成本次调整，迁移会扩大范围。未采用按字符数截断或全图缩放：前者不能适应中英文和字体比例，后者使课程文字与表头比例失真。未引入新库，因而无新增许可、下载体积或供应链风险。

#### 验证入口

`WidgetAdaptiveLayoutTest` 覆盖 12 个尺寸、2 个课程字号、长短文本、浅深色，检查所有子视图边界和文字完整行；另测 1.1/1.3 倍系统字体、最小空态、默认 4×4、周实例隔离、周视图实际出图宽度、部分覆盖和课程块文字预算。`DayWidgetSparseLayoutTest` 保留原有 560 组场景，包括缺地点、缺作息、单课、多课和全部结束。倒计时测试使用普通 Application 隔离显示夹具与 App 启动写库，不禁用生产逻辑或改动倒计时断言。

2026-09-20 本地验证：`:app:testDebugUnitTest` 共 357 项通过，失败、错误、跳过均为 0；`:app:lintDebug` 和 `:app:assembleDebug` 成功，保留既有 Lint baseline 与告警，未禁用规则。构建使用 Android Studio 自带 JBR；测试报告位于 `android/app/build/reports/tests/testDebugUnitTest/index.html`，调试包位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

原生渲染图位于 `android/app/build/reports/widget-adaptive/`，不是手机截图。真机只验证了现有 2×2、4×2 日视图空态和拉高/恢复后的重排；手机字体 1.1 倍，实际组件为 505×565px / 1077×565px。未修改设备课表、系统时钟或字体，未进行设备上的有课状态和 5×2、4×4 全覆盖。用户要求暂停后停止实机操作；后续改动通过本地测试和截图复核，最终调试包未重新安装，手机仍是暂停前的中间版本。设备数据库备份与截图仅在被忽略的 build 目录，不进入版本库。

### 底部面板统一：三个按钮同一种效果

首页右上角三个按钮（导入、分享、"…"）原先弹出三种观感：「…」用的是 `BottomSheetBehavior`，另两个是 `DialogFragment` 自己设窗口属性凑出来的"贴底对话框"—— 几何像面板，但没有上滑入场、不能按住往下拖关闭、拖一半也不会回弹。

统一动作发生在共享基类 `BaseDialogFragment`（全 App 7 个面板都继承它），而不是某一个 fragment：它现在继承 **`BottomSheetDialogFragment`**，于是 `BottomSheetDialog` + `BottomSheetBehavior` 由 Material 提供，上滑/下拖/回弹/遮罩联动全部交给框架，各面板不再各自实现一遍；`onResume` 里只额外做两件事：设成 `STATE_EXPANDED`（默认按 `peekHeight=AUTO` 会先落在折叠态，只露一条）和 `isHideable = true`。

面板外观仍由 `fragment_base_dialog.xml` 提供：一层 `page_background` 的圆角底（`bg_bottom_sheet_panel`）+ 把手 + 同色卡片。**为什么底要自己画**：`design_bottom_sheet` 那层 `MaterialShapeDrawable`（表面色）是 Material 在内容视图挂载**之后**才套上的，在 `onStart`/`onResume` 里改成透明都会被盖回去（实测：改完那一刻是 `ColorDrawable`，面板真正显示出来时又变回 `MaterialShapeDrawable`），露出它会在把手位置多一条框架色的横带。由面板自己的根布局铺底色不依赖任何时机。

验证：`BaseDialogFragmentWindowTest` 钉住的是**机制**而不是手搓出来的窗口属性 —— 面板必须由 `BottomSheetDialog` 承载、`design_bottom_sheet` 必须在、必须可下拖关闭、根布局必须有自己的底色且铺满宽度、内容卡片必须真的渲染出来。原先那条按 `window.attributes` 断言的用例已随实现一起替换：属性是由框架设的，再断言它们等于在断言框架。

### 数据库单例自愈（`AppDatabase.getDatabase`）

`INSTANCE` 是静态单例，而 `RoomDatabase.close()` **不会**把它置空，原实现只判 `INSTANCE == null`，于是"被关掉过的库"会被一直交出去：下一个使用者第一句查询就抛 `Cannot perform this operation because the connection pool has been closed.`，并且这个坏状态留在进程里，之后每次取库拿到的都是同一个死实例。

现在 `getDatabase` 用 Room 自己的 `isOpen()` 判活，不 open 就重建（`Room.databaseBuilder().build()` 会立刻打开库，所以刚建出来的实例 `isOpen()` 就是真，不会出现"还没查过就被判死、于是每次调用都重建"）。单例的契约是"给我一个能用的库"，这条修复把契约补齐。

这条同时是**测试可信度**的前提：`DatabaseUpgradeBehaviorTest` 会刻意 `close()` 掉单例来强制重新走版本判定（它的注释写明了这个意图），但原实现下那个意图是假的 —— 关掉之后没有任何地方会重建。整包跑时表现为"一批与数据库无关的用例集体报连接池已关闭"，而且**换一批**：单独跑全绿、整包跑就红 12 条。修复后整包与该类各自稳定。

验证：`DatabaseUpgradeFromOldVersionTest` / `DatabaseDowngradeFromNewerVersionTest`（破坏性回退确实清空旧数据）、`UpdateUtilsSeedTest`、`TableDeletionGuardTest`、`ImportFileRobustnessTest`、`WidgetProviderHostileInputTest` 在**整包**与**单类**两种跑法下结果一致。

### 首次启动的弹窗顺序由状态决定，不由计时器决定（2026-09-18）

全新安装同时满足两个弹窗条件：没有课表（`initView` → `showImportPrompt`）与没讲过许可声明（`initIntro`）。原实现里这两件事互不知情 —— 声明挂在 `postDelayed(500)` 上，导入引导挂在 initView 的协程上，谁先到谁先弹，于是用户先看到「还没有课表」、半秒后才是「关于本软件」，而后者才是"使用之前就该知道"的那一条（它 `setCancelable(false)`）。

顺序现在由两个显式状态决定，改这块时不要退回定时器：

- `introPending`：声明还没讲完（`onCreate` 里按 `KEY_HAS_INTRO` 算出）。声明用 `ui.content.post { }` 弹，只保证"不和 `setContentView` 挤在同一帧"，**没有**写死的等待时长。
- `importPromptDeferred`：`showImportPrompt()` 在 `introPending` 时只登记不弹；`finishIntro()` 讲完声明后按"此刻有没有课表"决定给哪个下一步 —— 有课表才展开底部面板，没课表就把被挡下的导入引导补上（不"展开面板 + 再压一个对话框"）。

验证：`FirstRunDialogOrderTest` 钉的是**顺序**（第一屏必须是声明、点掉声明才轮到导入引导、已讲过的老用户只看到导入引导且只弹一次），并做过变异还原（把 `if (introPending)` 改成 `if (false && introPending)` 后立即变红）。

### 侧栏抽屉与关于页（2026-09-18）

三件不许倒退的事：

- **抽屉里没有「新建课表」**。它与底部面板那个按钮是同一件事，唯一实现是 `ScheduleActivity.createNewTable()`；两个入口只会让同一个动作有两种到达方式。
- **「关于」是页脚，不是第四个导航行**。它落在抽屉最下方（靠一个 `weight = 1` 的弹簧顶上去），样式刻意与导航行不同：没有 32dp 圆底图标、没有强调色胶囊，只有一枚 16dp 线性图标 + 13sp 次要文字色的一行字，居中、行高 48dp（导航行 56dp）。导航行回答"现在在哪、还能去哪"，页脚回答"这是谁做的"—— 混在一套语言里，用户会把它读成与「设置」平级的第四项。
- **关于页是"正式文档"的面孔，而且栏目要少**：三张卡片（应用信息 / 使用说明 / 权限说明）+ 页脚署名。应用信息卡里带一行**项目地址**（可点），项目地址单独占整行、不挤在名字右边那一列 —— 那里只剩约 208dp，URL 会被折成两行。全页**一条分隔线都没有**：栏目之间与权限三行之间都靠留白分开（细线一多，整页就像一张被切碎的表格）。开源署名、免费声明与版权收在**页脚**（不占卡片、不给节标题）—— 它只有一段署名，单独立标题加卡片就是白占一栏；内容必须完整（Apache-2.0 第 4 条），版权一行与仓库根目录 `NOTICE` 一致。

`res/values/ids.xml` 里 `nav_row_new_table` / `nav_row_divider` 自本轮起无引用但**保留**：该文件按"条目顺序即 id"管理，删条目会让后面的条目整体前移。

验证：`ScheduleDrawerTest`、`AboutPageTest`；出图 `_crop/drawer_light.png`、`_crop/about_page_{light,dark}.png`。设计稿 `docs/preview-hyperos.html` 的图 3 与图 11 已同步改画。

`AboutPageTest.全页没有分隔线只有留白` 的判据是"页面里有没有裸 `android.view.View`"：本工程的细线一律写成 `<View android:layout_height="1dp" android:background="@color/list_divider"/>`，而卡片、文字、图标分别是 MaterialCardView / TextView / ImageView，都不会命中。要把线加回来，先得改这条测试。

### 校名的口径：适配，并且出现校名处必须同时声明与校方无关

本项目是**第三方独立应用**，不是上海工程技术大学的官方应用。凡是对外文案（首次启动的声明、随包 `NOTICE`、`README`）提到该校，两条同时成立：

- 用**适配**（"本软件适配上海工程技术大学的教学系统"／"is adapted for ..."），不用「面向」「只服务」「serves ... only」—— 后三种读起来像"该校自己的应用"。
- 同一处必须给出与校方无关的陈述：无隶属、无合作、无背书、非校方出品/授权。

`strings.first_run_body`、仓库根目录 `NOTICE` 与 `assets/NOTICE`（两份必须逐字一致）、`README.md` 四处口径统一，改一处就要改其余三处。验证：`FirstRunDialogOrderTest` 的两条署名用例（含直接读 `assets/NOTICE` 的那条）。

课表的**默认名字**仍是 `SuesEamsImporter.DEFAULT_TABLE_NAME = "上海工程技术大学"`：它是数据（用户可改名），不是应用的自称，因此不在上述约束内；但它确实是界面上最显眼的一处校名。

以下行为都有真机实证或安全依据，**任何"简化/重构"如果改变了这些行为，都是倒退**：

| 不变量 | 位置 | 为什么不能动 |
| --- | --- | --- |
| 提醒一律 `setExactAndAllowWhileIdle`，不用非精确闹钟/WorkManager | `CourseReminderScheduler.setExact` | 实测 HyperOS `power_pending` 策略把非精确闹钟统一推迟 3 天；WorkManager 在 Doze 下不运行（类文档已论证） |
| 未来 7 天滚动窗口，当天提醒不依赖任何每日触发器 | `CourseReminderScheduler.WINDOW_DAYS` | 00:05 闹钟/小部件更新全部可能被推迟，窗口是唯一保证 |
| 时刻一律按 `(节次, 分组)` 查表，绝不按 node 索引计算 | `CourseTimes` | 上午 3~5 节三套方案两两交叠、互不包含 |
| 广播携带完整课程实例，投递前校验课表、偏好和有效期 | `ReminderPayload` / `validEntries` | 防止已改课、已关开关和已过期广播仍然提醒；时间使用绝对时段与系统计时器 |
| requestCode 分段（START/END 各 250）与 cancelAll 扫描上界是同一个数 | `MAX_REMINDERS_PER_KIND` | 两个用途必须同一常量，否则静默丢闹钟/取消不掉 |
| PendingIntent 工厂"取或建"，永不返回 null | `nextDayPendingIntent` 等 | `FLAG_NO_CREATE` 版本曾导致全新安装每次重排都 NPE（类文档） |
| 默认作息分组固定 B 方案，不随选课楼栋漂移 | `SuesEamsImporter.DEFAULT_SCHEME` | 左侧时间栏是学校公布的固定作息，不是统计结果 |
| 课块行距全 App 唯一来源 | `ScheduleUI.rowGap` | 两处各算 2dp 曾导致每节多偏 1px 的真机 bug |
| 统一身份认证证书无效时取消连接，不提供绕过入口 | `WebViewLoginFragment.onReceivedSslError` | 遵循 WebView 官方要求，避免凭据经过不可信连接 |
| 勾选复选框/点"跳过"只做不涉及凭据的小事，登录永远由用户本人完成 | `suesHandleCasLoginPage` 等 | 产品隐私边界（README 已对外承诺） |
| 刷新小部件只能走 `AppWidgetManager.updateAppWidget`，不能自发 `APPWIDGET_UPDATE` 广播 | `AppWidgetUtils.refreshAllWidgets` 文档 | 受保护广播，自发自拒且静默失效（真机日志在注释里） |
| 日视图一条列表绑头部那一天，不做 ViewFlipper 动画 | `refreshTodayWidget` 文档 | 动画版曾出"日期与内容对不上"的两个真机 bug |
| 仅合并同刻触发，不以连堂为由抑制下一节提醒 | `alarmsForDay` | 下一节提前提醒不得被延后到上一节下课 |
| 小部件实例身份必须进 data URI，不能只放 extras | `AppWidgetUtils.dayUri` / `AppWidgetUtils.weekIntent` | `Intent.FilterComparison` 不比较 extras，否则不同尺寸实例会共用 `RemoteViewsService` 工厂。日视图为 `content:<widgetId>`，周视图为 `content:<tableId>#<widgetId>`；生产和测试均使用对应工厂方法，避免旧格式解析错误或实例身份丢失 |
| 背景图渐变底座只认自己记的叶子 Drawable（`bg_image_current`） | `BackgroundImageLoader.attach` | 直接取 `view.drawable` 会把 TransitionDrawable 套进 TransitionDrawable，历次 Bitmap 永久挂在引用链上（2026-09-18） |
| View 复用时必须失效背景图在途请求（无背景分支必须调 `cancel`） | `BackgroundImageLoader.cancel` | 不清占位 key 会把上一条数据的图贴到复用后的条目上（2026-09-18） |
| 背景图解码按 `CENTER_OUTSIDE` 采样 + 密度缩放，解码后密度归位 | `BackgroundImageLoader.applyScaling` / `decode` | 纯整数采样让极端长宽比的图整图进堆（实测 48 MB）；不归位密度会被 `BitmapDrawable` 二次缩放（2026-09-18） |

---

## 2. P0 —— 正确性缺陷（建议优先修）

### P0-1 导出文件/日历的输出流从不关闭（资源泄漏）

- **证据**：`schedule/ScheduleViewModel.kt:102`（`exportData`）与 `ScheduleViewModel.kt:144`
  （`exportICS`）拿到 `contentResolver.openOutputStream(uri)` 后只 `write`/`go`，没有
  `close`，也没有 `use {}`。biweekly 的 `go(OutputStream)` 不负责关流。
- **影响**：每次导出泄漏一个文件描述符；部分 SAF 提供方（云端文档）上表现为"导出成功
  但文件内容为空/不落盘"——写入结果是否真正刷出取决于提供方何时关闭。
- **建议**：两处都改为 `contentResolver.openOutputStream(uri)?.use { ... }`。原生机制，
  一行改动。
- **验证**：`./gradlew test` 回归 + 真机各导出一次 `.wakeup_schedule` 与 ICS。

### P0-2 `UpdateUtils.initDefaultData` 空 catch 吞掉建表失败

- **证据**：`utils/UpdateUtils.kt:66-71`，`try { insertTimeList; insertTable } catch (e: Exception) {}`
  ——空 catch 且随后无条件把 `KEY_HAS_ADJUST` 置 true。
- **影响**：首次建库一旦失败（磁盘满、DB 损坏），应用**永久**进入"默认时间表已初始化"
  的状态且不再重试：用户看到一张没有节次时间的空课表，且没有任何报错。这违反仓库自己
  的 AGENTS 规则（不吞错、不静默失败）。
- **建议**：失败时至少 `Log.w(TAG, ...)`；并且只在两次插入都成功后才置 `KEY_HAS_ADJUST`
  （把置位挪进 try 成功路径）。保持 `initDefaultData` 的幂等语义不变。
- **验证**：Robolectric 单测——注入异常场景断言 `has_adjust` 不被置位。

### P0-3 `exportICS` 循环里逐课 `catch (ignored: Exception)`（部分成功不可见）

- **证据**：`ScheduleViewModel.kt:132-140`。单门课生成 ICS 事件失败（如时刻格式坏）被静默
  跳过，导出的日历**少课**而用户不知道。
- **建议**：收集失败课程名，导出完成后以 Toast/对话框明确列出"这些课没有写进日历"。
  这正是 AGENTS §3.4"部分成功必须可见"的要求。
- **验证**：构造一条 `startTime=""` 的课，断言提示出现且其余课程仍导出。

### P0-4 `CourseUtils.calAfterTime` 是已知的坑，但仍在两处生产路径上

- **证据**：`CourseUtils.kt:220-241`。`CourseReminderScheduler.minutesOfDay` 的文档
  （`CourseReminderScheduler.kt:1040-1042`）已经指认它"跨天分支会把 23:50 提前 20 分钟
  这种输入静默改成 00:00"。此外它用 `substring(0,2)/(3,5)` 硬切，遇到库里/手输的
  `9:55`（未补零）会抛 `NumberFormatException` 直接崩溃。调用方：
  `settings/TimeSettingsViewModel.kt:95`、`settings/SelectTimeDetailFragment.kt:97`。
- **建议**：删除 `calAfterTime`，两处调用改用已有的 `CourseReminderScheduler.minutesOfDay`
  + `clockOf`（纯解析、零依赖、有测试）。跨天语义要么明确钳制并写注释，要么让"结束跨天"
  直接拒绝并提示。
- **验证**：单测覆盖 `"9:55"`、`"23:50"+30`、`"00:10"-10` 三个边界；手动走一遍
  "自定义时间表"页面把某节开始时间设为 23:50。

### P0-5 `AppWidgetUtils` 里 6 处裸 PendingIntent 没用已有的 `pendingIntentFlags()`

- **证据**：`utils/AppWidgetUtils.kt:224,229,234,282,299,304` 写的是
  `PendingIntent.getActivity(context, 0, intent, 0)` 或裸 `FLAG_UPDATE_CURRENT`，
  而同文件 `:46-51` 已经定义了带 `FLAG_IMMUTABLE` 的 `pendingIntentFlags()`（提醒模块在用）。
- **影响**：targetSdk 29 上只是 warning；**一旦 targetSdk 升到 31+，缺显式可变性声明会
  直接 `IllegalArgumentException`**——这正是 P2 里 targetSdk 路线的第一块绊脚石，现在
  统一是零成本。
- **建议**：6 处全部换成 `pendingIntentFlags()`（点击打开 App 的两个 `getActivity` 同样适用
  IMMUTABLE；它们不带 extras，不需要可变）。
- **验证**：`./gradlew lint` 无新告警 + 真机（Android 12+）小部件本体无点击入口、选择器
  详情页点预览不再打开应用（该做的归一化已完成，`pendingIntentFlags()` 仍由提醒模块使用）；
  当时"两处点击仍打开 App"的验收口径已作废，见 §1「小部件预览只声明 previewImage」。

### P0-6 release 包把 `Log.i/w` 全删了，与"日志自证"的设计意图矛盾

- **证据**：`android/app/proguard-rules.pro` 的 `-assumenosideeffects class android.util.Log`
  覆盖 v/i/w/d/e 全部级别。而 `CourseReminderScheduler`（SDK_INT 自证日志，
  `:337-340`）与 `BatteryOptimization`、`TimetableChangeWatcher` 的文档明确写着
  "日志里留一份自证，比事后猜可靠"——这些 `Log.i` 在发布包里**根本不存在**，
  真机排查时拿不到。
- **建议**：知情决策二选一：① `-assumenosideeffects` 只删 v/d（保留 i/w）——
  本应用日志量极小（每次重排一两条），保留 i 级没有性能/隐私顾虑；② 保留现配置，
  但在排障时用 debug 包。推荐 ①，并在 proguard 里注释原因。
- **验证**：assembleRelease 后反查 `dumpsys alarm` 与 logcat 能看到"重排完成"一行。

### P0-7 死代码与高危遗留（无调用方，直接删）

- **证据**：
  - `ViewUtils.saveImg`（`ViewUtils.kt:171-195`）：写 `Environment.getExternalStorageDirectory()`
    （API 29 起 scoped storage 下必失败）、PNG 压缩进 `mz.jpg` 名实不符、无任何调用方。
  - `CourseUtils.isQQClientAvailable`（`CourseUtils.kt:204-218`）：无调用方；且 API 30+
    包可见性机制下本就拿不到结果。
  - `ViewUtils.checkDeviceHasNavigationBar`（`ViewUtils.kt:97-118`）：反射隐藏 API，无调用方。
- **建议**：三处整体删除。`getVirtualBarHeight`（`ViewUtils.kt:125-139`，反射隐藏 API）
  **仍被使用**（`ScheduleActivityUI.kt:438`），列入 P2 随 targetSdk 路线换 `WindowInsets`。
- **验证**：`grep` 确认无引用后删除；`./gradlew assembleDebug` 通过。

### P0-8 课程详情点击的 catch 只弹"差点崩溃了"，吞掉原因（代码里自带 TODO）

- **证据**：`schedule/ScheduleFragment.kt:297-305`：`try { CourseDetailFragment.newInstance(c)…}
  catch (e: Exception) { //TODO: 提示是否要删除异常的数据  Toasty.error(…, "哎呀>_<差点崩溃了") }`
  ——异常原因被丢弃，用户拿到一句不可操作的文案，作者自己的 TODO 也悬着。
- **建议**：把 `e.message`（或 `e.javaClass.simpleName`）带进提示，并给出可操作下一步
  （"这条课程数据异常：……，可尝试删除该节课后重新编辑"）。
- **验证**：构造一条异常课程（如 `startNode > nodes`）点击详情，断言提示包含原因。

### P0-9 `ICalUtils` 两处脆弱实现缺测试钉住

- **证据**：
  - `ICalUtils.kt:105` `calendar.set(Calendar.YEAR, 2016)` 是死赋值（返回值随后在
    `getClassEvent` 里只被读 `HOUR_OF_DAY/MINUTE`），误导读者以为年份有语义。
  - `getClassEvent`（`ICalUtils.kt:57-67`）用 `set(Calendar.DAY_OF_WEEK, …)` 定位星期，
    依赖**默认 locale 的 firstDayOfWeek**；`sundayFirst` 的课表在某些 locale 下可能整周偏移。
- **建议**：删掉 2016 死赋值；为 ICS 导出补 Robolectric 单测：固定 Locale，
  分别以 `sundayFirst=false/true` 导出含单双周的课表，断言首个事件的 DTSTART 落在期望日期。
  先有测试再谈改写（把 `DAY_OF_WEEK` 换成显式日期加法是更优实现，但要靠测试护住）。
- **验证**：新增 `ICalExportTest`；现有 95+ 测试回归。

---

## 3. P1 —— 依赖与构建现代化

### P1-1 kapt → KSP（并顺手删掉 Glide 注解处理器）

- **证据**：`app/build.gradle:7`（`kotlin-kapt`）、`:221`（Room compiler）、`:231`
  （Glide compiler）。代码里只用 `Glide.with(...)`（`ScheduleActivity.kt:120,138`、
  `TableNameAdapter.kt:29`、`WidgetTableListAdapter.kt:28`、`TableListAdapter.kt:31`），
  **没有任何 `GlideApp` 引用**——Glide 注解处理器只为生成 GlideApp API，可直接删。
- **调研**：kapt 已进入维护模式，官方迁移指南
  [Migrate from kapt to KSP](https://developer.android.com/build/migrate-to-ksp)；
  Room 自 2.6 起完整支持 KSP；KSP 走 Kotlin 原生处理，构建提速约 2 倍（无 Java stub 阶段）。
  Kotlin 1.9.24 对应 KSP `1.9.24-1.0.20`。
- **建议**：`buildscript` 加 KSP 插件 classpath（或迁移 version catalog 后用 plugins DSL）；
  `apply plugin: 'com.google.devtools.ksp'`；`kapt "androidx.room:room-compiler"` →
  `ksp "androidx.room:room-compiler:2.6.1"`；删除 `kapt 'com.github.bumptech.glide:compiler'`
  与 Glide compiler 依赖；确认 `kapt.correctErrorTypes` 块可整体删除。
- **验证**：clean 后 `./gradlew test assembleDebug`；对比构建耗时。

### P1-2 依赖版本清单（当前 → 建议，及理由）

| 依赖 | 当前 | 建议 | 说明 |
| --- | --- | --- | --- |
| appcompat | 1.2.0-alpha02 | **1.7.1** | stable；仍支持 minSdk 21；夜间模式稳定性修复。1.8.0 起才要求 minSdk 23 |
| material | 1.2.0-alpha04 | **1.12.0** | 1.13/1.14 需 minSdk 23；且 MDC-Views 已进维护模式（1.14.0 为最终版），升到 1.12 即可封顶 |
| constraintlayout | 1.1.3 | **2.1.4 / 2.2.x** | API 向后兼容，修复大量测量 bug |
| lifecycle-extensions | 2.2.0 | **删除** | 官方已废弃；如需 ViewModelScope 用 `lifecycle-viewmodel-ktx` 2.8.x |
| gson | 2.8.6 | **2.13.x** | bug 修复；`.wakeup_schedule` 五行格式不变 |
| biweekly | 0.6.3 | **0.6.8** | 最新即 0.6.8（2022），Android 友好不变。不换 ical4j：需额外 R8 规则剔除 Groovy 类，收益不成比例 |
| BRVAH | 3.0.0-beta11 | **3.0.14/3.0.16 stable**（JitPack/MavenCentral `io.github.cymchad`） | 先出 beta；4.x 是 API 重写，单列决策 |
| Toasty | 1.4.2 | **1.5.2** | 同包名 `es.dmoral.toasty`，升级即换 |
| navigation | 2.7.7 | 保持 / 随 compileSdk 36 升 2.8+ | 2.8 要求 compileSdk 35 |
| junit | 4.13 | **4.13.2** | 补丁版 |
| androidx.test:runner | 1.2.0 | **1.6.2** | 配 Robolectric 4.16 |
| espresso | 3.2.0 | **3.6.1** | 同上 |
| robolectric | 4.16 | 保持 | 已是最新线 |

- **来源**：[appcompat releases](https://developer.android.com/jetpack/androidx/releases/appcompat)、
  [MDC-Android releases](https://github.com/material-components/material-components-android/releases)、
  [biweekly](https://github.com/mangstadt/biweekly)、[ical4j Android 指南](https://www.ical4j.org/android/)、
  [BRVAH](https://github.com/cymchad/baserecyclerviewadapterhelper)、[Toasty](https://github.com/GrenderG/Toasty)。
- **验证**：逐个升、逐个 `assembleDebug + test`，不一次性全升；每次升级跑一遍导入→提醒→小部件冒烟。

### P1-3 jcenter 退出：处理 NumberPickerView，然后删 jcenter + jetifier

- **证据**：根 `build.gradle` 两处 `jcenter()`（只读归档，随时可能整体关停）；
  `gradle.properties` 的 `android.enableJetifier=true`（注释已预告"删掉 NumberPickerView 后
  这一行也应一并删除"）。`cn.carbswang.android:NumberPickerView` 从未迁移到 Maven Central
  （[Carbs0126/NumberPickerView#74](https://github.com/Carbs0126/NumberPickerView/issues)）。
- **调研**：可选替代——[ShawnLin013/NumberPicker](https://github.com/shawnlin013/numberpicker)
  （Maven Central，活跃）；JitPack fork；或 vendor 源码。
- **建议**：**vendor 源码**（推荐）：该库核心只有 `NumberPickerView` 一个 View + attrs，
  本仓库已有 vendor splitties 的先例（`app/src/main/java/splitties/`），且使用点极少
  （自定义时间表编辑页）。vendor 后同批删除：`jcenter()` 两处、`enableJetifier` 一行、
  proguard 里 support 库的 keep 规则（`-keep class android.support.**` 等——jetifier 删除后
  这些规则也已无对象）。
- **验证**：临时清空 gradle 缓存（`--refresh-dependencies`）确认不再解析 jcenter；
  自定义时间表页手动回归。

### P1-4 `abiFilters` 三套 ABI 是无意义的包体放大

- **证据**：`app/build.gradle:137-140` 声明 `armeabi-v7a / arm64-v8a / x86`。全工程无原生
  代码，唯一可能的 `.so` 来源（RenderScript support）已被 `jniLibs.excludes` 剔除
  （`app/build.gradle:166-168`）。
- **影响**：多打包两套无用 ABI 目录；缺 `x86_64` 对 64 位模拟器不友好；2026 年主流设备
  全部 64 位，32 位过滤只剩负担。
- **建议**：删除整段 `abiFilters`，`assembleDebug` 前后对比 APK 大小与内容（`apkanalyzer`）。

### P1-5 构建脚本现代化（可分步走）

1. **compileSdk 34 → 36**（AGP 8.13+/9.x 支持；[Android 16 SDK 指南](https://developer.android.com/about/versions/16/setup-sdk)）。
   本应用侧载分发不受 Play target API 约束，compileSdk 只影响可用 API，无用户可见风险。
2. **version catalog**（`gradle/libs.versions.toml`）+ `plugins {}` DSL +
   `settings.gradle` 的 `dependencyResolutionManagement`：把根 `build.gradle` 的
   `buildscript/ext` 体系迁过去。属于一次性结构迁移，建议在 P1-1/P1-3 完成后做，
   避免冲突叠加。
3. **AGP/Kotlin 升级路线**：当前 AGP 8.7.3 / Kotlin 1.9.24 / Gradle 8.13 完全兼容，
   不是问题；升级到 AGP 9.x 时注意 kapt 已被标记 legacy（P1-1 完成后无此依赖）与
   [AGP9 迁移指南](https://blog.jetbrains.com/kotlin/2026/01/update-your-projects-for-agp9/)。
4. **签名口令交互**（`app/build.gradle:64-91` 的 Swing 弹框）：实现完备、注释清楚，
   保留；它已经支持从环境变量 `WAKEUP_KEY_PASSWORD` 取口令（口径写在
   `android/keystore.properties.example`），不必再引入 credentials 插件。

---

## 4. P2 —— 平台能力与 targetSdk 路线

### P2-1 targetSdk 29 的升级路线（不是"要不要升"，而是"升的话按这张表走"）

当前 `targetSdk 29` 是有真机权衡的刻意选择（manifest 注释：精确闹钟不需要权限、
避免 App Standby 桶被钉低；`CourseReminderScheduler` 类文档：不加 SCHEDULE_EXACT_ALARM）。
**保持 29 不是错误**；但若未来要升，逐级成本如下：

| 目标 | 必须做 | 涉及本项目 |
| --- | --- | --- |
| 31+ | ① 所有带 intent-filter 的组件显式 `android:exported`；② PendingIntent 显式可变性；③ 通知不得经 receiver/service 中转启动 Activity（trampoline 禁令） | ① `ScheduleActivity`（桌面入口，需 `exported="true"`）、`LoginWebActivity`、`WeekScheduleAppWidgetConfigActivity`；② P0-5 完成即满足；③ 现有通知 content intent 直指 Activity，已合规 |
| 33+ | ④ `POST_NOTIFICATIONS` 运行时权限——不申请则**提醒通知全部静默丢弃**；⑤ 侧载应用照常可用 `SCHEDULE_EXACT_ALARM`，但 Android 14 起新装默认拒绝（需 `canScheduleExactAlarms()` 检查 + 引导；或评估日历类应用可用的 `USE_EXACT_ALARM`——无 Play 政策约束，但"App Standby 桶"的旧权衡要在真机重测） | ④ 设置页加一次引导（可复用 `BatteryOptimization` 的模式：说明原因 → 跳系统框）；⑤ `CourseReminderScheduler.setExact` 加能力检查与降级路径 |
| 35+ | ⑥ edge-to-edge 强制，需重排自绘状态栏/导航栏逻辑 | ⑥ `ViewUtils.resizeStatusBar`、`ScheduleActivityUI` 的 `getVirtualBarHeight`、`getScreenInfo`（顺带把弃用的 `defaultDisplay` 换 `WindowMetrics`） |

- **来源**：[Schedule exact alarms are denied by default (Android 14)](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)、
  [USE_EXACT_ALARM 政策讨论](https://stackoverflow.com/questions/71031091/)。
- **验证**：每级在真机（HyperOS）上完整跑一遍：导入 → 重启 → 整夜待机 → 早课提醒到达。

### P2-2 网络安全配置全局放行明文，收紧为按需

- **证据**：`res/xml/network_security_config.xml`：`<base-config cleartextTrafficPermitted="true"/>`
  ——全应用、全域名允许 HTTP；`WebViewLoginFragment.kt:89`：
  `MIXED_CONTENT_ALWAYS_ALLOW`。
- **影响**：导入流程只访问学校 HTTPS 站点；全局放行意味着应用内任何 URL（包括未来误加的）
  都可明文。WebView 的 mixed-content 全放行同样超出需要（WebVPN 页面理论上全 HTTPS，
  但校内历史资源可能 http——需要实测）。
- **建议**：先抓包确认 WebVPN/统一身份认证全程 HTTPS 后，将 base-config 改为
  `cleartextTrafficPermitted="false"`（或按域 `domain-config` 放行学校域）；mixed-content
  改 `COMPATIBILITY` 逐步试。若实测发现必须明文的域，只放行那一个域。
- **验证**：完整走一遍导入流程（WebVPN 登录 → 课表读取），观察 `onReceivedError` 与加载失败。

### P2-3 Room schema 历史与 `allowMainThreadQueries` 退役计划

- **证据**：`AppDataBase.kt:12` `version = 9, exportSchema = false`；`:25`
  `allowMainThreadQueries()`（上游遗留全局开关）。
- **建议**：
  - `exportSchema = true` + ksp 参数 `room.schemaLocation`：版本已经到 9，导出 schema
    是一行配置，给未来"是否继续 destructive migration"留判断依据。本 fork 明确不做
    跨版本迁移（注释在 `AppDataBase.kt:26-28`），这个选择保留，但 schema 历史应留档。
  - `allowMainThreadQueries()`：先盘点全部 `...Sync()` 调用点
    （`CourseReminderScheduler` 在 `Dispatchers.IO`、`AppWidgetUtils.goAsync` 在
    `GlobalScope.launch`（Default）——都不在主线程），确认无主线程调用后删除该开关；
    若有遗漏点会立刻在开发期抛异常，比静默放行安全。
- **验证**：debug 包全功能回归（导入、编辑、小部件、提醒、导出）。

### P2-4 协程与广播接收的结构化

- **证据**：`AppWidgetUtils.kt:27-40` 的 `BroadcastReceiver.goAsync` 扩展默认
  `GlobalScope`（Delicate API，lint 提示）；`App.kt:135` 另起裸 `CoroutineScope(Dispatchers.IO)`。
- **建议**：建一个进程级 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（如 `App.scope`），
  两处共用。**不改行为**（goAsync 的 10 秒预算内 Room 操作绰绰有余），只是把
  fire-and-forget 收进有主的结构，崩溃时日志可归因。

### P2-5 文件导入的类型校验过严

- **证据**：`ImportViewModel.kt:84` `if (!uri.path!!.contains("wakeup_schedule"))` ——
  部分 SAF 提供方（如下载 ContentProvider 的 `content://.../document/123`）URI path 里
  **不含文件名**，合法的 `.wakeup_schedule` 会被误拒，报"请确保文件类型正确"。
- **建议**：改用 `contentResolver.query(uri, OpenableColumns.DISPLAY_NAME)` 读显示名判断；
  显示名拿不到时放宽（尝试解析，解析失败再报错——反正 `parse` 阶段有明确的错误信息）。
- **验证**：用系统"文件"应用从下载提供方打开一份 `.wakeup_schedule`。

---

## 5. P3 —— 一致性与可维护性

### P3-1 作息表数据双源，目前靠人工对账

- **证据**：同一套作息（COMMON 七格 + MORNING_A/B/C + 楼宇映射 + J30x 正则）在
  `coursetime/src/main/java/courseclock/coursetime/CourseTimeTable.java:105-127,180-187`
  与 `schedule_import/sues/SuesEamsImporter.kt:84-121,146-157` 各写一份。
  `settings.gradle` 只 `include ':app'`——**app 并不依赖 coursetime 模块**，两处物理隔离。
- **影响**：改一处忘另一处时，CLI/docs 输出与 App 实际作息不一致，且没有任何报错。
- **建议**（按成本递增）：
  1. **最低成本（推荐先做）**：加一个 Robolectric/纯 JUnit 测试，把两处数据表硬编码成
     期望值互相对账（或让 `SuesConflictTableTest` 的测试夹具反推三套时刻，逐值断言）。
  2. **结构方案**：`settings.gradle` 引入 `coursetime`（改为 `java-library`），app
     `implementation project(':coursetime')`，`SuesEamsImporter` 删掉私有副本改为引用
     ——单一事实来源。迁移成本低（纯 Java 无依赖），但要处理 `minSdk`/`desugaring` 无关性
     （纯 `java-library` 直接可用）。
- **验证**：方案 1 落地后 `./gradlew test` 与 `coursetime/verify.ps1` 双绿。

### P3-2 「HH:mm」解析收敛为一处

- **证据**：三份等价实现：`CourseReminderScheduler.minutesOfDay`（有校验有测试）、
  `CourseTimes.minutesOf`（private）、`ICalUtils.timeToCalendar` 内联解析。
- **建议**：统一引用 `CourseReminderScheduler.minutesOfDay`（或下沉到 `CourseUtils`）；
  `CourseTimes`/`ICalUtils` 改调用。避免三处校验规则将来不一致。

### P3-3 弃用 API 清单（随 targetSdk 路线走，不单独修）

`ViewUtils.getScreenInfo/getRealSize`（`defaultDisplay`，API 31 弃用）、
`getVirtualBarHeight/checkDeviceHasNavigationBar`（反射隐藏 API；前者仍在
`ScheduleActivityUI.kt:438` 使用）、`Activity.onActivityResult`（`LoginWebActivity`、
`ExportSettingsFragment` 等，可迁 Activity Result API）。集中到 P2-1 的 35+ 一并处理。

### P3-4 lint 与静态检查

- **证据**：`app/build.gradle:201-203` `lint { abortOnError false }`。
- **建议**：生成一次 `lintBaseline.xml` 后改 `abortOnError true`：存量问题记录在案、
  新增问题挡在提交前。与 AGENTS"不关闭校验"的原则对齐。

### P3-5 proguard 里的 R8 无效指令

- **证据**：`proguard-rules.pro` 中 `-optimizationpasses`、`-optimizations`、`-dontpreverify`
  是 ProGuard 专属指令，R8 静默忽略。留着误导维护者以为它们生效。
- **建议**：删除这三类行；`-keepattributes SourceFile,LineNumberTable` 保留（与 P0-6
  配合，release 日志可读）。

---

## 6. 建议实施顺序（小步、每步可独立验证）

| 批次 | 内容 | 风险 | 回归方式 |
| --- | --- | --- | --- |
| ① 零行为变更清理 | P0-1 流关闭、P0-2/3 吞错、P0-7 死代码、P1-4 abiFilters | 极低 | `test` + 冒烟 |
| ② 依赖小步升级 | gson/biweekly/Toasty/junit/BRVAH-stable、appcompat→1.7.1、material→1.12 | 低（UI 回归） | `test` + 手动走查主要页面 |
| ③ 构建现代化 | P1-1 kapt→KSP + 删 Glide compiler；P1-3 vendor NumberPickerView、删 jcenter/jetifier | 中（构建系统） | clean 构建 + 全量测试 + 导入冒烟 |
| ④ 修复行为缺陷 | P0-4 calAfterTime、P0-5 PendingIntent flags、P0-6 日志策略、P0-9 ICS 测试 | 中（有行为变化） | 针对性单测 + 真机 |
| ⑤ 平台路线 | P2-1 targetSdk 分级升级、P2-2 网络收紧、P2-3/4 | 高（需真机整夜 Doze 实测） | 完整提醒链路实测 |
| ⑥ 结构优化 | P3-1 双源对账、P3-2 解析收敛、P3-4 lint 基线 | 低 | 全量测试 |

---

## 时间测试版实现补充

独立 `simulation` 变体已提供日期定位、时间拖动、暂停及倍速控制，详见
[时间测试版手册](TIME-LAB.md)。正式版不包含测试控制页或测试计时服务；此前“无新增常驻服务”
的描述针对普通版课程通知。模拟器通过统一课程时间源驱动原业务，不更改系统时间或普通版数据。

## 7. 完成定义核对（对本手册原始审计）

- [x] 每条建议都有：证据（`文件:行` 或引用原文）、更优实现、原生机制、来源、验证方式
- [x] 联网调研：KSP/kapt、appcompat/material/biweekly/BRVAH/Toasty 版本现状、jcenter/NumberPickerView、
      exact-alarm 与 targetSdk 政策（来源见各节链接）
- [x] 明确列出"不得倒退"清单（§1），防止后续改进破坏真机实证的设计
- [x] 未修改任何现有文件；本手册是唯一新增文件
