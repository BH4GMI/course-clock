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

### 日程三形态与空间分配（2026-09-18）

此节替代此前固定 4×2、单课居中色卡方案；下文其他审查建议保留原审查日期，不代表本批次已全部实施。

- **三个可放置入口**：4×2「当天课程」、2×2「当天课程（小）」、4×4「一周课程」。前两个共用同一份实现与渲染（`SmallTodayCourseAppWidget` 继承 `TodayCourseAppWidget`，画面同样由 `TodayColorfulService` 按实测宽高在 focus / timeline / singleLarge 之间切换），差别只有默认格数与预览。之所以必须是两个 provider：桌面按 **provider** 决定"添加小部件时摆多大"，一个 provider 只有一个默认尺寸，而同一个 `<receiver android:name>` 不能在清单里出现两次 —— 只靠"让用户把 4×2 拖小"的话，选择器里根本看不到"小正方形"存在。形态仍按实例的可用宽高切换：小正方形突出当前课程；横条把课程与状态并排；大正方形展示整周。
- 大卡单课时分为课程、开始/结束时间、地点三个区域。先测各区域文字自然高度，再均分剩余高度，不固定三等分挤压长课名。多课时间轴同样先测量再分配余量；可用空间不足时显示明确的余课数量。
- 已结束课程来自统一的 `DayWidgetSchedule.completedCourses`，课间与最后一课仍保留历史。显示顺序为已结束在上、当前居中、后续在下，历史容量不足时在上方显示数量；不再限定"最后一节正在上"才出现历史。**但"今天全部上完"是例外：那一档给空态**（见下一条），不把最后一节已结束的课留在正文里 —— 这是按设计稿改的口径，与"全部结束均保留历史"的旧说法相反。
- 空态一共四种，都由 `TodayColorfulService.empty()` 出一份**整体居中**的"图标 + 一句话"：没有课表 → `还没有课表`；开学日期无效 → `请检查开学日期`；今天没有课 → `今天没有课`；**今天全部上完 → `今天的课都上完了`**（判据是 `courses.isNotEmpty() && remaining.isEmpty()`，必须同时要求"今天本来有课"，否则那三种空情况下 `remaining` 也是空的）。居中的做法：竖向靠 root 的 `Gravity.CENTER_VERTICAL` 把整块居中，横向靠"图标视图铺满（`FIT_CENTER` 把图摆在正中）+ 文字自身 `gravity=CENTER`"——图标**不能**用 `wrap_content`，那样它会跟着文字左边缘走、整块偏左。`DayWidgetSparseLayoutTest` 在"全部上完"那一档同时量上留白与下留白（两者之差 ≤1px），只量一边会让"内容堆在顶上"这种错悄悄过去。大卡的月份日历只在"今天没有课"那一档出现；"全部上完"不画日历（今天已经没有内容可看）。
- 保留原来的下课前 20 分钟倒计时和每分钟刷新调度，不修改通知准时性、闹钟窗口或设备数据。
- 尺寸使用 `AppWidgetManager` 实例 options：竖屏取 minWidth / maxHeight，横屏取 maxWidth / minHeight；缺失 options 时使用 provider 默认尺寸。不再把宿主报告的 110dp 强制抬高到 176dp，也不再用手机屏幕宽度代替组件宽度。
- `onAppWidgetOptionsChanged` 触发当前实例重排。允许双向拖动，最小可调整尺寸为 110×110dp（= 2 格，按官方公式 70n−30）——这一档必须真的缩得到，"小正方形"才存在；原来写 170×150dp 把 2×2 挡在外面，形态再多用户也只看得见横条。布局不是三张固定比例位图：中间尺寸继续按真实文字测量。时间和地点保留，长文字使用可见省略号。小部件本体**不挂任何点击入口**（纯展示）：`RemoteViews` 无法区分"桌面上"和"选择器里"，挂上点击就会在选择器预览里被误触；用户要进应用点图标即可。详见 §1「小部件预览只声明 previewImage」。
- 周课表同样接 `onAppWidgetOptionsChanged`。它是一整张位图（`ScheduleAppWidgetService` 的 `getCount() == 1`，整周画进 `iv_schedule`），不重画就一直是拖动前那份格子尺寸。回调只重画被改的那一个实例（`AppWidgetUtils.refreshScheduleWidgetFor`），不顺手重排桌面上所有实例。今天那一列表头用 `colorPrimary`（强调色），不再用"全对比 vs 60% 透明"表达今天——后者在深色底上几乎看不出差别。
- 日视图表头是**一行小字**（设计稿 `.head`：左标题右计数，同为 12sp）—— 不是原先的"大号日号 + 第二行摘要"。标题随形态变，判据与正文**同一个** `TodayColorfulService.dayWidgetForm`：横条写「今日日程」（它正文左列本来就有大号日号 + 星期，表头再写日期是重复），小正方形写日期 `9月18日 · 周五`（它正文没有日期）。另一头是课程计数 `3节 · 已上1节`（设计稿写的是 `3节 · 已上1`；用户要求两个数字都带单位，"已上"后面直接跟数字会被读成时刻或序号），课程数必须与正文出自**同一份装载结果**（`TodayColorfulService.loadDay`）——表头与正文各查一次库会因为"读的时刻不同"而对不上，那正是用户一眼能看出来的错；表头的**时刻**也必须是同一个（`refreshTodayWidget` 的 `now` 参数），否则会出现「3节 · 已上3节」配「正在上课」。格宽不足 180dp（2 格 = 110dp）时不写计数，把一个用户永远看不全的截断串换成完整的标题。没有课表 / 开学日期无效 / 当天没有课时不显示计数，交给正文空态说明。
- 形态判据 `TodayColorfulService.dayWidgetForm` 只看宿主给这一格的**整卡**像素尺寸，表头与正文共用它；整卡尺寸也只有一个来源 `TodayColorfulService.cardBoxPx`。这两处原先各有各的一份实现，正是"表头说自己画横条、正文画的是小正方形"这类错的温床。大正方形（日视图被拖成大方块）这一档设计稿没有覆盖（它的大正方形是"整周课表"，属于另一个 provider），表头同样写「今日日程」—— 这是判断，不是照抄设计稿。
- 周视图里"此刻正在上"的那一格描边改用强调色（`TipTextView.accentStroke`，**默认关**，只由周视图服务打开）。主课表与周视图共用 `TipTextView`，主课表不开这个开关，所以主课表一行都不变。判据复用 `DayWidgetSchedule.isOngoing`，与日视图、下课提醒同一份。
- 日视图横条的正文结构（设计稿 `widget-morphology.html`）：左「日期块」大号日号 + 星期、中「课名 / 教室 / 时段」**三行**、右「状态列」；状态列在下课前 20 分钟窗口内是**大号数字 + 分钟**两行，其余是小字文字状态。小正方形则是强调色状态行 → 大号数字（倒计时优先，否则开始时间）→ 课名 → 教室 → 页脚「时段 · 另有N节」。时段用 **en dash**（`09:55–11:15`），与设计稿逐字一致；`DayWidgetSchedule.timeRange` 仍用半角连字符（主课表、提醒文案、导出都在用，只在这一处换显示字符）。下一节文案为 `接下来 HH:mm · 课名[ · 另有N节]`，余课数并进这一行、不另起一行；历史摘要为 `已结束 N 节 · 名称`。倒计时窗口复用 `DayWidgetSchedule.countdownMinutes`，不另写一份。
- **形态按宿主给的整卡尺寸判**（`hostBoxPx()`），不按正文区的宽高比：设计稿的三种形态是按格数定义的（2×2 / 4×2 / 4×4），而正文区已经扣掉表头与内边距、比例与卡片本身不同。原先用正文区比例判，4×2 会掉进窄形态（渲染探针把这件事打了出来）。
- **日视图正文的三级降级**（`TodayColorfulService.focus`）。设计稿只定义了 180×180 以上的形态，而这一格允许被拖到 110dp；那点高度放不下三段正文，所以按"先动间距、最后才动内容"的顺序降级，并且每一级都只在上一级仍然放不下时才做：①收起与时段开始时刻重复的大号时间（只在小正方形里有）；②收紧纵向间距并把课名收成一行 —— 收紧必须**递归到文字行**，因为横条里 `main` 的直接孩子是 `row` 这个容器，真正带间距的是容器内的三行文字，只清直接孩子的 padding 在横条里等于没做；③仍然放不下才让出两条副信息，顺序是"先下一节、再历史摘要"（当前课是这一格存在的理由，历史摘要最先让）。设计稿自己的降级同向：`.compact-text` 就是收紧 padding 与间距、把课名收成一行。
- `focus` 里两条副信息的 padding 必须在**量高度之前**设好：`availableMain` 的含义是"正文这一块真正能拿到多少像素"，等于 `高度 − 两条副信息的真实高度`。先量高度、后加 padding 会让这个值恒定高估两条 inner padding 之和，使"空间不足"那一级永远不触发，末行文字被父布局按 `AT_MOST(剩余)` 压扁 —— 表现为文字只剩半行高，而代码看上去什么都没做错。空余高度按各组自然高度均分（`slack`），不是全交给 `main` 的 weight（那会把余量堆在一头）。
- "当前显示哪一周"这个状态**已随箭头一起删除**：周视图现在只画本周，不再有 `schedule_widget_next_week_<id>` 登记。日视图同理，只画今天。
- **两个小组件都没有箭头**。日视图删掉了右上角的「切明天 / 切回今天」，周视图删掉了「下一周 / 回到本周」；随之删净的是整套"每个实例记住自己在看哪一天（哪一周）"的状态（`day_widget_tomorrow_<id>`、`schedule_widget_next_week_<id>`、`isNextDay`/`isNextWeek`、data URI 里的标志位、两个 provider 的 `ACTION_SHOW_*`/`ACTION_*_WEEK`）。理由是一样的：**箭头占掉的宽度正好是表头最需要的地方** —— 日视图 2×2 那一格里它把表头挤到只剩约 60dp，日期都会被截断。删掉后表头文字铺到最右边，日视图表头能放下完整的标题与 `3节 · 已上1节`，周视图能完整显示 `我的课表 | 第 N 周　周 X`。
- **小部件预览只声明 `previewImage`，不声明 `previewLayout`**（2026-09-18 真机定位）。桌面「添加小部件 → 点条目文字 → 详情页」会把 `previewLayout` 用 `AppWidgetHostView` 托管起来，**并给预览控件挂一个"打开本应用"的点击**：用户点一下预览图就被带回主界面，看起来像误触。真机证据：详情页控件树是 `item_details_preview → AppWidgetHostView → ImageView(click=true)`，那个 `ImageView` 的内容描述来自 `day_widget_preview.xml`（全工程只有这一处写「当天课程示例」），它的 bounds `[372,752][848,1209]` 与启动日志里的 `bnds=[372,752][848,1209]` 完全一致，而启动方是 `com.miui.home`（uid 10151）用 **LAUNCHER 意图**发起的 —— 与小部件自身的点击入口无关。只给 `previewImage` 时详情页退化成普通 `ImageView`、不挂点击，与哔哩哔哩等第三方小部件的行为一致。预览位图由 `WidgetPreviewImageTest` 从**真实布局**渲染，浅色（`drawable-nodpi`）与深色（`drawable-night-nodpi`）各一套，`-night` 限定符由 `previewImage` 的 `@drawable` 引用自动解析。
- 整个正文使用一幅 ARGB_8888 位图和一个列表项，避免多行独立测量、槽位重复拉伸与组件内部滚动。仅缓存当前批次尺寸，不增加图片历史缓存；刷新数据前清空状态，删除默认课表后不会残留旧课程。正文提供完整文本的无障碍描述，导航有可读名称。
- 4×6 改为 5×9 后可以按宿主给出的新宽度重排，但是否自动从四列扩大为五列由桌面控制，应用不能承诺全宽自动扩展。API 31+ 仍采用通用 options 回调路径，未新增 `RemoteViews(Map<SizeF, RemoteViews>)` 变体集合。

采用现有 Android `LinearLayout`、`TextView`、`View.MeasureSpec`、`RemoteViewsService` 与项目 `CourseTimes`，没有引入依赖或更换 UI 框架。没有采用 Compose/Glance，因为现有 provider 和集合点击契约可直接复用，引入新框架会扩大本次修改范围。

调研依据：

- 小米官方《Widget适配建议及示例》：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1585 ，本次在线访问成功（2026-09-18）；本地文档更新时间 2024-10-17，示例使用 options 选择布局。
- Android 官方布局指南：https://developer.android.com/develop/ui/views/appwidgets/layouts ，本次连接超时，**未完成在线验证**。回调、dp 单位和尺寸范围契约已核对本机 Android SDK 36.1 的 `AppWidgetManager.java`、`AppWidgetProvider.java`；产品仍为 minSdk 21 / compileSdk 34。

验证：`DayWidgetSparseLayoutTest` 使用 Room 夹具和 Robolectric NATIVE，覆盖 20 场景 × 7 尺寸 × 2 课程字号 × 2 主题，共 560 组，逐级检查文字及子视图边界；另测同实例尺寸变化、倒计时边界、实例隔离、查看日期保持和空库刷新。旧多行色卡截图断言已替换成整幅日程尺寸与内容断言，未降低倒计时与时间/地点检查。原生 PNG 输出在 `_crop/morphology-*.png`，属于桌面宿主验收前的渲染验证，不能称为真机截图。

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
| 通知剩余分钟在触发一刻现算（`targetAt` 进 Intent） | `ReminderPayload` / `notificationText` | 闹钟被系统推迟后回放设置值等于对用户说谎 |
| requestCode 分段（START/END 各 250）与 cancelAll 扫描上界是同一个数 | `MAX_REMINDERS_PER_KIND` | 两个用途必须同一常量，否则静默丢闹钟/取消不掉 |
| PendingIntent 工厂"取或建"，永不返回 null | `nextDayPendingIntent` 等 | `FLAG_NO_CREATE` 版本曾导致全新安装每次重排都 NPE（类文档） |
| 默认作息分组固定 B 方案，不随选课楼栋漂移 | `SuesEamsImporter.DEFAULT_SCHEME` | 左侧时间栏是学校公布的固定作息，不是统计结果 |
| 课块行距全 App 唯一来源 | `ScheduleUI.rowGap` | 两处各算 2dp 曾导致每节多偏 1px 的真机 bug |
| 统一身份认证证书告警必须用户决定，绝不静默放行 | `WebViewLoginFragment.onReceivedSslError` | 放行等于把密码暴露给中间人 |
| 勾选复选框/点"跳过"只做不涉及凭据的小事，登录永远由用户本人完成 | `suesHandleCasLoginPage` 等 | 产品隐私边界（README 已对外承诺） |
| 刷新小部件只能走 `AppWidgetManager.updateAppWidget`，不能自发 `APPWIDGET_UPDATE` 广播 | `AppWidgetUtils.refreshAllWidgets` 文档 | 受保护广播，自发自拒且静默失效（真机日志在注释里） |
| 日视图一条列表绑头部那一天，不做 ViewFlipper 动画 | `refreshTodayWidget` 文档 | 动画版曾出"日期与内容对不上"的两个真机 bug |
| 连堂判定单位是一天、抑制在两遍扫描里绑定 | `alarmsForDay` 文档 | 早期拆开版有两条真实丢提醒的路径 |
| 小部件实例身份必须进 data URI，不能只放 extras | `AppWidgetUtils.dayUri` / `ScheduleAppWidgetService` | `Intent.FilterComparison` 不比较 extras，两个实例会被合并成同一个 `RemoteViewsService` 工厂，尺寸与缓存全串（2026-09-18）。"这个实例在看哪一天/哪一周"的标志位已随箭头删除，现在 URI 里就是实例 id 本身（`content://<appWidgetId>`），解析只用 `schemeSpecificPart.toIntOrNull()`；测试也必须用 `AppWidgetUtils.dayUri` 构造，手写旧格式 `"0,<id>"` 会解析失败并悄悄回落到 provider 默认尺寸 |
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

## 7. 完成定义核对（对本手册自身）

- [x] 每条建议都有：证据（`文件:行` 或引用原文）、更优实现、原生机制、来源、验证方式
- [x] 联网调研：KSP/kapt、appcompat/material/biweekly/BRVAH/Toasty 版本现状、jcenter/NumberPickerView、
      exact-alarm 与 targetSdk 政策（来源见各节链接）
- [x] 明确列出"不得倒退"清单（§1），防止后续改进破坏真机实证的设计
- [x] 未修改任何现有文件；本手册是唯一新增文件
