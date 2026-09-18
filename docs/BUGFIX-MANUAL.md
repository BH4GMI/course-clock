# Bug 排查与修改手册

## 2026-09-19：空状态（无课表）下主界面入口必崩

- 触发：全新安装（或把课表删光）后，首页右下角「+」一点即崩——真机（Android 15 / HyperOS）
  实测 `kotlin.UninitializedPropertyAccessException: lateinit property table has not been
  initialized`，崩溃点 `ScheduleActivity.initEvent` 的 addBtn 监听。这是发布前实机验证发现的
  缺陷，与 R8 无关（同一行为在未混淆构建上同样存在）。

### 根因

- `ScheduleViewModel.table` 是 `lateinit var`：库里有默认课表时由 `initView` 的协程赋值。
  全新安装**一张表都没有**，`getDefaultTable()` 返回 null、协程提前 return，`table` 永远不会
  初始化——而主界面一堆点击监听直接裸读它。同一类裸读散布在：加号加课、顶栏日期行
  （周数面板 `showWeekPicker`）、分享导出（面板内 `ExportSettingsFragment.tableName` 的
  lazy）、底部面板「修改当前周 / 上课时间 / 更换背景 / 已添课程」四个入口、抽屉「课程管理」、
  侧栏表列表的设置项与切表。此前只有抽屉「课程管理」一处有 `isTableReady()` 手工守卫。
- 契约层面的问题是：**「课表可能不存在」这件事只被三处调用点各自知道**，其余入口默认它
  永远就绪。修复必须让"读课表先过闸门"成为唯一路径，而不是再补几处 if。

### 修复（契约层：统一闸门，一处定义）

- `ScheduleViewModel` 新增 `tableOrNull(): TableBean?`——需要读 `table` 的 UI 一律从它拿，
  不再直接摸 lateinit；`isTableReady()` 保留（Fragment 侧三处仍在用）。
- `ScheduleActivity` 新增 `requireTable(action: (TableBean) -> Unit)` 闸门：就绪时把
  `table` 交给回调（调用方不许再回头读 `viewModel.table`）；未就绪时区分两种状态给提示——
  `initView` 已确认一张表都没有（新增 `tableMissing` 标记）时提示「还没有课表，先导入或新建
  一张吧~」，加载窗口内提示「课表还在加载，稍等一下再试~」。上述全部 8 类入口（含原先
  手写守卫的抽屉行，顺带去掉重复逻辑）统一改走闸门。
- `ExportSettingsFragment.tableName` 的 lazy 改走 `tableOrNull()` 容错（拿不到名字退回
  「我的课表」）：它的展示入口已有闸门把关，这里是同一契约的兜底，防止未来新增入口绕过。

### 验证

- 新增 `EmptyStateEntryGuardTest`（Robolectric，真 Activity + 空 Room 库）：
  - 空状态下逐个 `performClick` 上述全部入口——守卫失效时 `performClick` 会把
    `UninitializedPropertyAccessException` 直接抛出来；同时断言没有页面被拉起、
    「还没有课表」引导文字真实出现在窗口上。
  - 反向用例：播种一张课表后轮询点「+」，必须在限时内拉起 `AddCourseActivity`——
    钉住闸门不会把正常路径也拦住。
  - 注意（测试写法）：`AppDatabase` 是单例，**同一测试类的各方法共享同一个库文件**，
    用例必须先 `clearAllTables()` + `clearAllTimeTables()` 清场，且课表对时间表有外键，
    播种课表前要先播 `TimeTableBean(id = 1)`。
- 真机复测（Wi-Fi adb 连接的实机，空状态）：+、顶栏日期行、分享、底部面板四入口全部点击，
  `logcat -s AndroidRuntime:E` 无任何输出、进程 PID 全程不变；提示文案由单测钉住。
- 全量单测（`:app:testDebugUnitTest`）通过。

## 2026-09-18：背景图资源生命周期与日视图小部件多实例身份

- 触发：在不影响体验（日视图下课提醒保持每分钟更新、课程通知保持准时）的前提下优化健壮性、
  电耗与内存。本轮**未改动**任何提醒时刻、闹钟类型（`setExactAndAllowWhileIdle`）、倒计时
  刷新频率或通知精度。

### 背景图渐变历史嵌套（真实内存泄漏）

- 根因：`BackgroundImageLoader.attach()` 用 `TransitionDrawable(arrayOf(view.drawable, next))`
  取旧层，而渐变进行中的 `view.drawable` 就是 **TransitionDrawable 本身**。于是每换一次背景
  就在渐变里再套一层，历次换过的每一张 Bitmap 都被永久钉在这条引用链上，每次绘制还要多走一遍
  嵌套的 alpha 合成。`BackgroundResourcesTest.repeatedTransitionsDoNotKeepNestedHistory`
  连续换 10 次图即可复现（修复前该断言失败）。
- 修复：新增 `R.id.bg_image_current`，按 View 记录**上一次挂上去的叶子 Drawable**，渐变底座
  只从它取（没有记录时不做交叉淡入），从构造上就不可能嵌套。顺带删掉重复的私有 `apply()`：
  解码结果与缓存命中两条路现在共用 `attach()`。

### 「没有背景图」分支串图（真实可见缺陷）

- 根因：`TableNameAdapter` / `WidgetTableListAdapter` / `ScheduleActivity` 在无背景分支只设了
  纯色，没有清 `R.id.bg_image_load_key`。条目 A 的解码还在路上时 RecyclerView 把该 View 复用给
  一条没有背景的数据，A 的回调回来时 key 仍然匹配，于是把 A 的图贴到了 B 上。
- 修复：新增 `BackgroundImageLoader.cancel(view)` —— 取消在途请求、清掉两个 tag、取消未跑完的
  淡入并把 alpha 归位（首次淡入被打断时 view 会停在半透明上，复用出去就是一条发灰的纯色）。
  三个调用点的无背景分支改为先 `cancel` 再设纯色。

### 解码规模：移植 Glide 的 `CENTER_OUTSIDE`

- 根因：原采样只在"宽和高都还得比目标大"时才加倍率，且完全没有解码期缩放。一张 12000×1000
  的全景图放进 400×600 时两个条件都不成立，倍率退回 1 —— 3600 万像素、约 48 MB 的位图整个
  解进堆。这是 OOM 级隐患，不是"慢一点"。
- 修复：按 Glide 默认降采样策略 `DownsampleStrategy.CENTER_OUTSIDE` 与
  `Downsampler.calculateScaling` 的算法计算 `inSampleSize`（2 的幂、QUALITY 取整）以及
  `inTargetDensity`／`inDensity` 的零头缩放（采样先砍到 1/sample，密度再乘 `sample*exact`，
  净效果恰好是 `exact`），解码后把 `bitmap.density` 归位成显示密度 —— 不归位会让
  `BitmapDrawable` 拿它再缩一次，画面发虚。只缩不放。
- 来源（已联网核对，非凭记忆）：bumptech/glide `library/src/main/java/com/bumptech/glide/load/
  resource/bitmap/DownsampleStrategy.java` 与同目录 `Downsampler.java`。

### 在途请求与内存压力

- `loadInto()` 登记每个 View 的在途解码（主线程限定 + `WeakHashMap`，View 被 GC 时条目自消），
  View 被复用或取消时 `Future.cancel(false)` 掉还没开始的那次；正在跑的那次砍不掉，但其回调会
  因 key 不匹配被丢弃。列表快速滑动不再排队解码一批没人会看到的图。
- `App.onTrimMemory()` → `BackgroundImageLoader.clearMemory()`：**任何一档都交出缓存**。缓存是
  纯装饰的（上限 6 MB），正在显示的画面由各自 View 的 Drawable 持有，清缓存不影响画面。
  刻意不按档位区分：`TRIM_MEMORY_UI_HIDDEN`(20) 比 `TRIM_MEMORY_BACKGROUND`(40) 还小，档位
  常量不是单调的尺子，`level >= x` 或逐档列举都会把语义表达错 —— 逐档列举那个版本正是被 lint 的
  `SwitchIntDef` 抓出漏项的。

### 日视图小部件多实例身份

- 根因：`RemoteViewsService` 的工厂由系统按 `Intent.FilterComparison` 缓存复用，而
  FilterComparison 只比较 action / data / type / identifier / package / component / categories，
  **不比较 extras**。日视图把 `appWidgetId` 只放在 extras 里、data 只有 `content:0`/`content:1`，
  于是同一天的两个实例被合并成同一个服务请求：第二个实例拿到第一个实例的工厂，格子尺寸、
  `cellSizeCache`、算好的行槽位全是别人的。桌面上的表现就是"两个日视图一大一小，小的那个一直
  按大的尺寸画"。
- 修复：沿用周视图（`ScheduleAppWidgetService`）早已在用的约定，把实例 id 放进 **data**：
  `content:"<0|1>,<appWidgetId>"`；`TodayColorfulService.onGetViewFactory` 改按 `split(",")`
  解析。`size < 2` 那条分支接的是升级前已经推送到桌面的旧 URI（只有 `0`/`1`）：系统下次刷新之前
  可能还拿着它重连一次，此时实例未知、尺寸退回整屏宽，与旧版行为一致 —— 不画错也不崩；新版不再
  产生这种 URI，它自己会随一次刷新消失。

### 提醒取消路径：**刻意保持原状**

- 原计划要用 `FLAG_NO_CREATE` 的可空查询替换 `cancelAll` 里"先建再取消"的 500 次循环，省掉无效
  的 PendingIntent 创建。核对实现后决定**不做**：本项目「必须保留的设计（改进时不得倒退）」
  （`IMPLEMENTATION-MANUAL.md` §1）明确列着 **"PendingIntent 工厂『取或建』，永不返回 null"**，
  类文档也记录了 `FLAG_NO_CREATE` 版本曾导致全新安装每次重排都在 `getBroadcast` 上 NPE、提醒
  从未注册成功。收益只有每次重排省下若干次 binder 调用，代价是动到通知核心路径的类型契约 ——
  按"安全 > 正确性 > 速度"保持原状。

### 测试夹具：Windows 宿主无法表达 `file://` 路径

- 现象：`BackgroundResourcesTest` 的文件断言在 Windows 上以 `FileNotFoundException` 收场。
- 根因（**已实测**，非推测）：`android.net.Uri.fromFile(File)` 把 `getAbsolutePath()` 当作
  **已解码的路径段**塞进分层 URI。真机（POSIX）得到 `file:///data/user/0/...`，是对的；Windows
  上得到 `file://C%3A%5CUsers%5C...` —— 整条绝对路径落在 **authority** 里，`Uri.path` 返回
  **空串**。实测输出：`spec=file://C%3A%5CUsers%5C...` / `Uri.parse(spec).path=`（空）。
- 处置：这是**测试宿主的平台限制**（产品只跑 Android），因此不改产品代码迁就它；在夹具里用
  `hostFileUri()` 造一个两边都认的 URI（`file://` + 去掉盘符的 POSIX 风格路径），并用
  `canonicalFile` 校验一次 —— 换到别的盘跑会明确报错，而不是让断言悄悄退化成"什么都没测到"。
  **没有放宽任何断言。**

### 验证

- `./gradlew :app:testDebugUnitTest`：**55 suites / 282 tests / 0 failures / 0 errors / 0 skipped**
  （本轮之前：267 tests / 3 failures —— 失败的正是新增的背景资源测试）。
- 守门用例：`BackgroundResourcesTest` 4 → 12 项（渐变不嵌套、取消不串图、长图按覆盖缩放到
  2400×600、0.75 零头经密度得 1500×300、2 的幂倍率得 400×300、小图不放大的 100×100、
  centerCrop 出精确目标框、坏文件返回 null、非正尺寸拒绝、两次导入的独立文件身份、拒绝替换不
  破坏旧图且不留临时文件、相邻目录不删）；新增 `DayWidgetInstanceIdentityTest` 3 项（同实例的
  今天/明天不等、同天不同实例的 `filterEquals` 必须不等、两个实例各按自己的格子尺寸量）；
  新增 `ReminderTimelinessGuardTest` 4 项，把用户最在意的那条体验（**提醒准时 + 倒计时每分钟**）
  钉成会红的守卫：调度器只允许出现 `setExactAndAllowWhileIdle` 与 `setExact` 两种排法（精确
  集合比对）、每次排闹钟都显式写 `RTC_WAKEUP`、不得引入 `WorkManager`/`JobScheduler`/
  `setAlarmClock`、倒计时刷新链也必须仍是精确闹钟。该守卫先剥源码注释再比对 —— 因为
  `CourseReminderScheduler` 的注释里**故意**写着各种反面写法（`setInexactRepeating`、
  `setAlarmClock`、`AlarmManager.set`、`WorkManager`），不剥就会被自己的说明文字绊倒。
- `./gradlew :app:lintDebug`：BUILD SUCCESSFUL，59 条告警，**改动文件上无新增**
  （只剩 `BackgroundImageLoader.kt:15` 的 `ExifInterface`，属既有取舍：类注释里已论证
  `androidx.exifinterface` 会让 R8 多保留约 74 KB dex）。
- **未做**：真机安装与运行时内存/耗电实测。本轮结论是结构性的（无效工作被消除、引用链被切断、
  解码规模有了上界），**不给出任何百分比收益声明**。

## 2026-09-18：首次启动的弹窗顺序、侧栏页脚与关于页的书面化

- 触发：用户逐条反馈四件事 —— 关于页的排版与文案要更正式、侧栏删掉「添加课表」、「关于」要挪到
  侧栏最下方并且不再用现在的行样式（要简化）、以及「首次打开软件为什么先弹出没有课表的提示再弹出
  『关于本软件』」。

### 首次启动两个弹窗没有顺序（真实可见缺陷）

- 现象：全新安装冷启动，先弹「提示 / 还没有课表。去教学服务中心导入吧……」，约半秒后才弹
  「关于本软件」的许可与免费声明。
- 根因**不是**哪一条文案写错了，而是两者之间压根没有顺序：

  ```kotlin
  // onCreate：声明走定时器
  ui.content.postDelayed({ if (!getPrefer().getBoolean(Const.KEY_HAS_INTRO, false)) initIntro() }, 500)
  initView()   // initView 的协程：没有课表就立刻 showImportPrompt()
  ```

  全新安装同时满足"没有课表"（数据条件）与"没讲过声明"（偏好条件），而这两件事分别由一个
  写死的 500ms 延迟和一个协程触发。谁先到谁先弹 —— 延迟那一边必然输，于是顺序固定成了
  "先提示、后声明"。声明恰恰是"使用之前就该知道"的那一条（它 `setCancelable(false)`）。
- 修复层：**状态/生命周期层**，不是调参数。新增两个显式状态 —— `introPending`（声明还没讲完）
  与 `importPromptDeferred`（被声明挡下的导入引导）：

  ```kotlin
  introPending = !getPrefer().getBoolean(Const.KEY_HAS_INTRO, false)
  if (introPending) ui.content.post { initIntro() }   // 等首帧，不再写死 500ms

  // showImportPrompt()
  if (introPending) { importPromptDeferred = true; return }

  // finishIntro()
  introPending = false
  if (importPromptDeferred) { importPromptDeferred = false; showImportPrompt() }
  else showBottomSheetDialog()
  ```

  顺序从此由状态决定。顺带去掉那个估出来的常数：`postDelayed(500)` 换成 `post {}`。
- 收尾也一并改了：声明讲完之后给哪个"下一步"取决于**此刻有没有课表**。有课表才展开底部面板；
  没有课表就把被挡下的导入引导补上（那才是用户此刻唯一能做的事），不再"展开面板 + 再压一个对话框"。
- 防回归：`FirstRunDialogOrderTest`（三条：第一屏必须是声明、点掉声明才轮到导入引导、已讲过的老用户
  只看到导入引导且只弹一次）。**变异验证**：把 `if (introPending)` 改成 `if (false && introPending)`
  后 `首次启动的第一个弹窗是许可与免费声明` 立刻失败，说明这条测试真的钉住了顺序，而不是复述实现。
- 注意测试写法：导入引导出自 Room 的挂起查询（跑在 Room 自己的线程池上），
  `shadowOf(mainLooper).idle()` 只排空主线程队列、**不等**那个线程，所以用有上限的轮询等它出现；
  AlertDialog 的按钮回调还经 `AlertController.mButtonHandler` 转一手，`performClick()` 之后必须再
  `idle()` 一次。

### 侧栏抽屉：删掉「新建课表」，把「关于」降成页脚

- 「新建课表」与底部面板那个按钮是同一件事（共用 `createNewTable()`），两个入口只会让同一个动作
  有两种到达方式；连带撤掉那条只为分隔它而存在的细线。新建课表仍由底部面板提供。
- 「关于」挪到抽屉最下方，靠一个 weight = 1 的弹簧顶上去 —— 上面三行怎么增减都不会把它挤上来。
- 「关于」不再用 `navRow`：那是导航行的语言（32dp 圆底图标 + 15sp 文字 + 当前项强调色胶囊），
  回答的是"现在在哪、还能去哪"，而关于既不是目的地也不是状态。页脚只剩一枚 16dp 线性图标 +
  一行 13sp 次要文字色，居中、行高 48dp（导航行 56dp）。
- `ids.xml` 里 `nav_row_new_table` / `nav_row_divider` 保留不删：该文件按"条目顺序即 id"管理
  （见文件顶部），删条目会让后面的条目整体前移。已在文件里注明它们自本轮起无引用。
- 防回归：`ScheduleDrawerTest`（抽屉里没有 `nav_row_new_table`、`关于` 必须是最后一个子视图且
  其 bottom 等于抽屉底 − 12dp、页脚行高/字号/文字色/图标尺寸与导航行的差异逐条断言），并出图
  `_crop/drawer_light.png`。

### 关于页：排版与文案书面化

- 文案：「怎么用」→「使用说明」，「应用会用到」→「权限说明」，新增「开源许可」；三步改成完整
  陈述句（句末句号）；「开机自动恢复」改成系统里的真名「开机自启动」并补上"设备重启"这个前提；
  权限说明从"权限名"改成"为什么需要"。
- 排版：四张卡的圆角与内边距统一读 `about_card_radius` / `about_card_inset`（原先第一张 24dp/20dp、
  后两张 20dp/16dp，同页并排像没对齐）；「权限说明」三行之间用 1dp 分隔线分栏；开源声明从页面底部
  的居中灰字升成一张带节标题的卡（它是 Apache-2.0 第 4 条要求的署名，看起来像脚注等于没写）；
  补一行版权 `© 2026 The CourseClock Project`（署名照抄仓库根目录 NOTICE，与 NOTICE 不一致的署名
  等于没有署名）。
- 防回归：`AboutPageTest`（节标题存在且口语旧词不许回潮、四张卡圆角与内边距一致、版权行在最后一张
  卡里、版本号仍显示），并出图 `_crop/about_page_{light,dark}.png`。
- **未做**：真机目视。关于页与抽屉是人眼判断的活，出图只保证结构契约成立，观感仍以用户为准。

### 校名口径：适配，不是"本校的应用"（2026-09-18 续）

- 用户要求：「改成"适配上海工程技术大学"」「不要出现让人以为官方出品的误导」。
- 问题不在某一句话，而在**口径**：首次启动的声明写的是"本软件**面向**上海工程技术大学"，
  `NOTICE`（随包）写的是"CourseClock **serves** ... (SUES) **only**"。两句都像在描述"该校的
  应用"，而不是"第三方为该校做的适配"。
- 改法：把校名的用途统一收敛成**适配对象**，并且每一个出现校名的地方都必须同时给出"与校方无关"
  的陈述。三处一起改，不留一处口径不同：
  - `strings.first_run_body`：「适配上海工程技术大学的教学系统」+ 「本软件是个人开发的独立项目，
    与上海工程技术大学及上游原作者均无隶属、合作或背书关系；出现校名仅用于说明适配对象，不代表
    本软件由该校出品、维护或授权。」
  - `NOTICE`（仓库根目录与 `assets/NOTICE`，两份必须逐字一致）：Trademarks 一节改名
    Trademarks and university affiliation，写清 "independent, personally developed"、
    "neither produced, maintained, reviewed, authorised nor endorsed by that university"、
    "not an official application of it"；Modifications 一节的首句从 "serves ... only" 改成
    "is adapted for ... only"。
  - `README.md`：首句同样改成「适配……教学系统」，并单独一段声明本项目是个人独立开发的第三方
    应用、非该校官方应用。
- 防回归：`FirstRunDialogOrderTest.声明的校名口径是适配而不是本校的应用`（正面钉「适配上海工程技术
  大学」，反面钉「面向 / 只服务 / 本校」不许出现，并逐条要求无隶属、无合作、无背书、非校方出品
  四个分句）、`随包的NOTICE也要声明非校方出品`（直接读 APK 里那份 `assets/NOTICE`，钉住
  "serves ... only" 不许回潮）。
- **仍然存在、未改**：`SuesEamsImporter.DEFAULT_TABLE_NAME = "上海工程技术大学"` —— 导入后课表的
  默认名字就是校名，会出现在多课表列表与底部面板里。它是**数据**（用户可改名），不是应用的自称，
  所以本轮没动；但它确实是应用界面里最显眼的一处校名，要不要一起改成中性的表名需要用户确认。

### 关于页：栏目与分隔线减半（2026-09-18 再续）

- 用户原话：「关于中分的栏太多了」。
- 问题不是某一栏丑，而是**栏目的数量**：上一版为了"正式"，给每个栏目都配了一张卡、一个节标题，
  权限三行还各画了一条 1dp 细线。四张卡 + 四个标题 + 三条线，同页并排像一张被切碎的表格。
- 改法（减栏目，不是减信息）：
  - 「开源许可」不再单独成节。它只有一段署名，为它立标题 + 建卡片就是白占一栏 —— 署名、免费声明
    与版权一起收进**页脚**（不占卡片、不给标题），内容一个字没少。
  - 权限三行之间去掉两条细线，改用 14dp 留白分行。全页现在**一条分隔线都没有**。
  - 页脚从居中灰字改成左对齐（与三个节标题同为 24dp 缩进）：居中的灰字在整页左对齐的排版里
    像一个孤岛。
  - 应用信息卡补一行**项目地址**（`https://github.com/BH4GMI/course-clock`，autoLink 可点）。
    它单独占整行、铺满卡宽：挤在名字右边那一列（72dp 图标之后只剩约 208dp）会被折成两行，
    而且折的位置正好落在 URL 中间。
- 净结果：四张卡 + 四个标题 + 三条线 → **三张卡 + 两个标题 + 零条线**。
- 防回归：`AboutPageTest` 相应重写（卡片数 3；「开源许可」不许再出现；版权行不许出现在任何卡片里；
  项目地址必须在第一张卡里且带 ClickableSpan；新增 `全页没有分隔线只有留白` —— 判据是页面里
  有没有裸 `android.view.View`，因为本工程的细线一律写成 `<View android:layout_height="1dp"
  android:background="@color/list_divider"/>`）。
- **未做**：真机目视。栏目多少、留白是否舒服，最终仍以用户为准。

## 2026-09-17：HyperOS / AOSP 后台权限分步设置与小部件入口

- 电池入口：检测 HyperOS 电池管理处理器后，首次点击“后台运行不受限制”明确打开 `com.miui.securitycenter` 的应用电池管理；返回后由用户确认已设“无限制”。厂商没有公开查询接口，确认记录仅表示用户完成该步骤，不代表 AOSP 授权。
- 第二步：厂商步骤完成后显示“加入 AOSP 白名单”，明确向 `com.android.settings` 发出 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，避免 HyperOS 的高优先级处理器截获；不可用时退回 Android 设置的电池优化列表，再失败则给出提示。
- 状态：AOSP 是否加入只查 `PowerManager.isIgnoringBatteryOptimizations`，不再使用历史 `battery_whitelist_confirmed` 或 Activity 结果码推断。返回前台重新查询，系统撤销授权后恢复申请入口。新偏好 `hyperos_battery_confirmed` 仅保存厂商步骤的人工确认，`hyperos_battery_pending` 保留待确认的返回流程。完成后可通过“重新设置”再次进入。
- 小部件：默认桌面为 `com.miui.home` 且注册了 `widget://picker` 时，按钮直接打开 `com.miui.personalassistant` 的小部件中心，提示搜索课钟或进入安卓小部件。其他桌面继续使用标准 pin 请求；没有支持、请求失败或确认窗口未出现时提供明确提示及返回桌面入口。请求返回 true 仅表示接收请求，只有实际回调才能表示添加完成。**（已废弃：这条入口在 2026-09-17 晚被整体移除，原因与删除清单见文末「26.」——厂商桌面要么不支持 pin，要么接受请求却既不出确认框也不落实例，剩下的路都是把用户丢进第三方列表。）**
- 设备核对仅使用 ADB 包管理查询：这台手机默认电池请求由 `com.miui.securitycenter` 优先处理，同时注册了 `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`；`widget://picker` 可解析到 HyperOS 的 `PickerHomeActivity`。没有截图或自行检查渲染。
- 回归：`SettingsSystemActionsTest` 覆盖设置行点击、厂商确认到 AOSP 的分步跳转、旧确认记录不误判、白名单撤销、HyperOS 小部件中心入口和其他桌面回退。无数据库或课表格式变更。

## 2026-09-17：当天课程小组件展示优先级

- 现象：13:25 时，全天共 3 门课的小组件只露出上午两门已结束课程，下午课程被挤出可视区。
- 根因：`TodayColorfulService` 保留已结束课程后仍沿用数据库顺序；固定高度布局只取能容纳的前几行。
- 修复：在行高测量及截取之前调用 `DayWidgetSchedule.displayOrder`，移除今天已结束的课程，再按正在上课、尚未开始排序；各组按实际开课时间升序，相同时间保持原顺序。上下课时间使用课程所属作息分组，不以节次代替。
- 边界：开课时刻包含在当前课程区间内，下课时刻立即从列表移除；缺少时间的课程不判定为正在上课，开课时间未知时在未开始组末尾展示。明天视图只按开课时间排序，不过滤。全部上完显示“今天的课都上完了”，没有排课则显示“今天没有课哦”。
- 展示：当前课程与紧接着的下一节均使用紧凑双行色卡，第一行显示状态、课名和当前课倒计时，第二行独立显示起止时间与教室，避免长课名或教师姓名挤掉地点。未设置教室时明确显示“教室未设置”。标题仍统计全天课程数，不等于剩余课程数或可见行数。每次数据刷新重新过滤和排序，复用现有刷新机制。
- 最后一课补充：仅当今天恰好剩一门且正在上课时，在当前色卡上方补充最近两门已结束课程的灰色小字摘要，按结束时间先后排列。摘要字号为课程基础字号减 2sp、最小 10sp，最多占列表高度的 40%；先保留当前课完整高度，再按剩余空间减少摘要数量。摘要和当前课合为一个列表项，避免摘要挤掉当前课。最后一课尚未开始、还有后续课程、明天视图以及全部结束时不显示摘要。复用现有 TextView 与测量机制，无新增依赖。
- 兼容性：无数据库、API、配置或课表文件格式变更。
- 回归：`DayWidgetPriorityTest` 覆盖当前/未来/已结束优先级、上下课边界、交叠分组、明天视图、全部结束、空列表及缺失时间；同时运行现有日小组件测试。
- 验证：调试包构建并通过 ADB 覆盖安装，13:32 真机截图显示“汽车电控技术 / 13:20-14:40 · B210多”已置顶。小组件相关测试采用独立测试源目录运行，因为现有 `CourseDetailRenderTest` 仍引用已删除的 `row_time`、`row_teacher`、`row_room`、`tv_reminder_value`，会阻塞默认测试编译；未改动该无关测试或正式测试配置。

## 2026-09-17：课表设置点击后开关不更新

- 根因：`ScheduleSettingsActivity.onSwitchItemCheckChange` 修改了课表和列表模型，但遗漏 `notifyItemChanged`，自绘开关仍显示旧状态。点击实际生效，但视觉上没有反馈。
- 影响及修复：显示周六、显示周日、周日为每周第一天、在格子内显示上课时间、显示非本周课程共 5 个开关统一按当前展示列表定位并刷新；搜索结果下仍刷新正确行，整行及开关区域点击都只切换一次。退出页面沿用原有保存逻辑。
- 同类排查：主设置页开关已有统一刷新；课表设置日期/当前周的联动另有依赖相邻下标或只更新搜索可见行的问题，改为按实际对象查找，隐藏行也同步模型，避免搜索后显示旧日期或通知越界。
- 回归：`ScheduleSettingsInteractionTest` 逐一点击 5 个开关的整行和开关区域，核对课表值、可见开关、无障碍状态，并验证搜索结果中的开关与日期按钮。

状态：**2026-09-16 已按本手册完成全部 P1/P2/P3 修复与死代码清理**（见第 8 节回填记录；
两处偏差与一项缓办也记录在内）。第 1~7 节保留审查时的原始分析，作为每处修改的依据。

- 审查日期：2026-09-15
- 审查范围：`android/app/src/main/java` 全部 132 个 Kotlin 文件中的业务代码；`AndroidManifest.xml`；`app/build.gradle`。vendor 进仓库的 `splitties/`（上游拷贝）、`widget/colorpicker`（第三方库代码）、`res/` 资源只做了调用面核查，未逐行审。
- 验证基线：
  - `coursetime/verify.ps1`：52 项全部通过；
  - `gradlew test`（testDebugUnitTest）：14 个测试类、95 个用例全部通过，0 失败
    （结果文件时间 2026-09-15 23:27，与当前工作区源码一致，工作树干净）；
  - 手册中每一条都标注了"验证方式"，修复后必须重跑上面两项并补对应单测。

---

## 0. 严重度与条目格式

- **P1**：崩溃、数据丢失、核心功能失效或产出损坏数据。
- **P2**：特定条件下行为错误、静默数据污染、资源泄漏、明显误导用户。
- **P3**：边界条件、一致性瑕疵、性能/健壮性改进。

每条格式：**位置 → 现象/触发 → 根因 → 修复方案 → 验收方法**。
"修复层"遵循仓库 AGENTS.md 的阶梯：优先修在数据入口/契约层，而不是每个使用点打补丁。

---

## 1. P1 问题

### P1-1 导出文件流未关闭：导出产物不保证落盘，且泄漏文件描述符

- **位置**：`schedule/ScheduleViewModel.kt:100-113`（`exportData`）、`ScheduleViewModel.kt:115-148`（`exportICS`）
- **现象/触发**：分享导出 `.wakeup_schedule` 或 ICS 后，在云盘类 DocumentsProvider（OneDrive/网盘同步目录）目标下可能得到空文件或半截文件；每次导出泄漏一个 fd，直到 GC 才回收。
- **根因**：`contentResolver.openOutputStream(uri)` 拿到的流从未 `close()`。`write()` 对本地文件"碰巧"生效（FileOutputStream 无用户态缓冲），但 Android 的契约是流必须关闭：本地路径靠运气、云同步提供端在 close 时才提交内容、fd 则纯粹泄漏。这是资源与契约层的问题，不是某一次写入的问题。
- **修复方案**（原生机制：Kotlin `use`，标准库一等机制）：
  ```kotlin
  // exportData
  withContext(Dispatchers.IO) {
      getApplication<App>().contentResolver.openOutputStream(uri)?.use { it.write(strBuilder.toString().toByteArray()) }
          ?: throw Exception("无法打开导出目标")
  }
  // exportICS
  withContext(Dispatchers.IO) {
      getApplication<App>().contentResolver.openOutputStream(uri)?.use { Biweekly.write(ical).go(it) }
          ?: throw Exception("无法打开导出目标")
  }
  ```
  注意两处现在对 `outputStream == null` 是静默跳过（写出"什么都没发生"的成功），一并改为报错。
- **验收**：导出到本机存储与至少一个云同步目录各一次，重开文件与内容比对；连续导出 20 次后 `dumpsys` 无 fd 增长；失败路径（选一个只读目标）有明确错误提示。

### P1-2 分享导入对 Gson 产物只归一了 timeGroup：room/teacher/color 为 null 时崩溃或显示 "null"

- **位置**：
  - 数据入口：`schedule_import/ImportViewModel.kt:100-107`（只归一了 `timeGroup`）
  - 崩溃点 A：`schedule/ScheduleFragment.kt:226`（`c.color.isEmpty()` —— color 为 null 时 NPE）
  - 崩溃点 B：`schedule_appwidget/ScheduleAppWidgetService.kt:273`、`today_appwidget/TodayColorfulService.kt:300-306`（`Color.parseColor(c.color)` / `c.color.substring(...)`，color 为 null 或畸形时抛异常，日视图小部件表现为"载入出现问题"且每次刷新复现）
  - 脏显示：`ScheduleFragment.kt:232`（room 为 null 时 `c.room != ""` 为 true → 显示 `@null`）、`TodayColorfulService.kt:370,393`（teacher 为 null → 显示 `null`）
- **现象/触发**：导入别人分享的 `.wakeup_schedule` 文件。该文件是外部输入，其他 fork/老版本导出的 JSON 缺字段时，Gson 绕过 Kotlin 构造器把 null 写进非空声明字段。`ImportViewModel` 已经意识到这一点并归一了 `timeGroup`，但 `room`/`teacher`/`color`/`courseName` 没有同等待遇——同一类问题只修了一半。
- **根因**：归一化做在了正确的层（导入入口），但字段清单不全。下游所有渲染点（`c.room != ""` 这类判断）都建立在"恒为非空"的假设上，这个假设只在导入处成立。
- **修复方案**：在 `ImportViewModel.importFromFile` 现有的归一化块里补齐同一批字段：
  ```kotlin
  courseBaseList.forEach { base ->
      val name: String? = base.courseName
      if (name == null) base.courseName = ""
      val color: String? = base.color
      if (color == null) base.color = ""
  }
  courseDetailList.forEach { detail ->
      val group: String? = detail.timeGroup
      if (group == null) detail.timeGroup = CourseTimes.DEFAULT_GROUP
      val room: String? = detail.room
      if (room == null) detail.room = ""
      val teacher: String? = detail.teacher
      if (teacher == null) detail.teacher = ""
  }
  ```
  同时按 P1-5 给渲染点的颜色解析加防御（入口归一化管"本应用导出的文件"，渲染防御管"任何已入库的脏数据"，两层都留）。
- **验收**：单测构造缺 `room`/`color` 字段的 5 行分享文件，导入后开课表、放两个小部件，均不崩溃、无 "null"/"@null" 字样；现有 `SuesConflictTableTest` 风格补一个归一化单测。

### P1-3 课表管理：长按删除课表无确认，且可删除默认课表

- **位置**：`schedule_manage/ScheduleManageFragment.kt:111-127`（长按直接 `deleteTable`）、`ScheduleManageViewModel.kt:32-34`
- **现象/触发**：误触长按即删整张课表。删除的是默认课表时，库内没有 `type = 1` 的行了：首页落入"还没有课表，去导入"引导（明明其他课表还在）、提醒停排、日视图小部件空——`CourseReminderScheduler`/`AppWidgetUtils` 都已把"无默认表"当一等状态处理，但用户视角这是"删一张表把 App 删瘫了"。删除经外键 CASCADE 连课程一起删光，不可恢复，而同页的单课删除（`CourseManageFragment`）与清空课表都有确认框，唯独删除整表没有。
- **根因**：确认缺失属于"不可逆操作未确认"（AGENTS.md 3.4 的硬要求）；默认表无保护属于状态机不变量（全库必须恰好一行 `type = 1`，见 `ImportViewModel.insertTableAsDefault` 的文档）没有被删除路径维护。
- **修复方案**：
  1. 长按删除前弹确认框（对齐 `CourseManageFragment` 清空课表的写法），文案写明"该课表的所有课程将一并删除"。
  2. 若目标 `id == tableDao.getDefaultTableId()`：还有其他课表时，删完后把默认标记转移给剩余第一张（`changeDefaultTable(oldId, 剩余id)`，在删除同事务里做）；只剩这一张时提示"这是最后一张课表"并拒绝删除或要求二次确认。
- **验收**：长按出确认框；删除默认表后首页直接显示剩余课表、提醒照常重排（看 logcat `CourseReminder` 行）；单测覆盖"删默认表 → 剩余表接管"。

### P1-4 时间表删除保护是死代码：删掉被引用的时间表后，课表作息被静默重置

- **位置**：`settings/TimeTableFragment.kt:70-91`（只挡 `id == selectedId`，catch 里的"仍被使用中"提示永远走不到）；`dao/TimeTableDao.kt:67-68`；外键定义 `bean/TableBean.kt:11-17`（`onDelete = SET_DEFAULT`，`timeTable` 默认值 1）
- **现象/触发**：A 课表正在用时间表 T；用户在"选择时间表"页长按删除 T（只要 T 不是本次会话里选中的那个就能删）。删除必然成功（FK 是 SET_DEFAULT 不是 RESTRICT），A 课表的 `timeTable` 被系统静默改回 1，上课时间全部变化，无任何提示。catch 里的"该时间表仍被使用中>_<"是永远不会触发的死分支。
- **根因**：保护写在了错误的层（UI 的 try/catch），而真正的约束（"被 TableBean 引用的时间表不可删"）既不在数据层声明（外键选择了 SET_DEFAULT），也不在删除入口检查。
- **修复后补注**：实施阶段的测试证明，在 SQLite 外键强制开启时这条删除路径其实抛
  `NOT NULL constraint failed`（原因见第 9 节的 schema 陷阱），所以旧版部分设备上表现为
  一句没头没尾的报错 toast，而不是静默重置——不管哪种形态，入口拦截 + 明确文案都是
  正确的修法，结论不变。
- **修复方案**：删除前查引用：`tableDao` 加一个 `select count(*) from tablebean where timeTable = :id`，>0 时拒绝删除并提示"该时间表正被 N 张课表使用，请先在课表设置里改用其他时间表"。原有"不能删除当前选中的"保留。
- **验收**：被默认课表引用的时间表删除被拒并有原因提示；无引用的删除成功；`TableBean.timeTable` 不出现静默变化（删前后 `getDefaultTable().timeTable` 相同）。

### P1-5 课程颜色的解析对脏数据零防御：一处崩溃即整块小部件报废

- **位置**：`today_appwidget/TodayColorfulService.kt:300-306`（`c.color.substring(3, 9)` 假定长度 9）、`schedule_appwidget/ScheduleAppWidgetService.kt:273`、`schedule/ScheduleFragment.kt:292`（`Color.parseColor`）
- **现象/触发**：颜色字符串不是 "#RRGGBB"(7) 或 "#AARRGGBB"(9) 时：长度 8、13 或任意畸形值 → `substring` 抛 `StringIndexOutOfBounds` 或 `parse` 抛 `IllegalArgumentException`。周视图工厂里抛出 = 整个周视图小部件"载入出现问题"；Fragment 里抛出 = 点击该课即崩。脏数据来源：P1-2 修复前的历史导入、其他 fork 的分享文件、以及 `AddCourseActivity.kt:281` 写入的 `"#" + Integer.toHexString(color)`（alpha 非全 ff 时不足 9 位——当前 alpha 滑块固定关闭所以是 7/9 位，但这是个隐式前提，无人守卫）。
- **根因**：颜色字符串在整条链路里没有单一出口。入库前无格式校验（数据层），渲染处各写各的假设（使用点）。
- **修复方案**（修在最小正确抽象层）：新增一个解析函数（放 `utils/ViewUtils` 或 `CourseUtils`）：
  ```kotlin
  /** 把课程颜色字符串安全解析成 ARGB；任何畸形输入退回 fallback，绝不抛异常。 */
  fun parseCourseColor(color: String?, fallback: Int): Int
  ```
  规则：null/空 → fallback；7 位按 #RRGGBB；9 位按 #AARRGGBB（日视图小部件的透明度拼接逻辑并入该函数）；其余 → fallback。三处渲染点全部改走它。顺手在 `AddCourseViewModel.preSaveData` / `onColorSelected` 写入处校验格式，防止新的脏数据。
- **验收**：单测覆盖 null/""/7 位/9 位/8 位/垃圾串；手工把库里一条课程颜色改成 "12345"，课表、两个小部件均正常显示兜底色，不崩溃。

---

## 2. P2 问题

### P2-1 `UpdateUtils.initDefaultData` 空 catch 吞掉种子数据失败，且失败后永不重试

- **位置**：`utils/UpdateUtils.kt:66-74`
- **现象/触发**：全新安装首次启动插入默认作息/默认课表失败（磁盘满、并发双进程等），异常被 `catch (e: Exception) {}` 吞掉，随后 `has_adjust` 照样置 true——条件 `!has_intro && !has_adjust` 永不再成立，用户落在"无默认课表"状态且没有任何报错，重启也无法自愈。
- **根因**：用空 catch 当"防重复播种"的机制，吞错 + 静默扩大成功范围，违反仓库禁令（AGENTS.md 1.3）。
- **修复方案**：播种前先查（`timeTableDao.getTimeTable(1) == null` 已有）、插入失败时不置 `has_adjust`、记日志并在下次启动重试；插入本身改成幂等（作息行用 `@Insert(REPLACE)` 或先查后插），让"重试"成为安全操作。失败时至少 logcat 一条 `Log.e`。
- **验收**：单测模拟插入抛错 → `has_adjust` 不被置位；二次调用能补齐数据；成功路径不受影响。

### P2-2 `CourseUtils.daysBetween` 未把开学日期归一到周首日：非周一开学时周次漂移 ±1

- **位置**：`utils/CourseUtils.kt:117-149`（`time1` 只是"开学日期当天零点"，没有像 `time2` 那样 `set(DAY_OF_WEEK, 周首日)`）；`countWeek`（160-164）；下游 `ICalUtils` 导出、提醒排程全部共用
- **现象/触发**：用户在设置里把开学日期选成周二~周日（DatePicker 不强制周一，设置页只弹 toast 建议）：设 09-16（周三）为开学日，09-14（周一，即真实第 1 周开头）会被 `countWeek` 算成 1（应为 0，"还没开学"）；周次边界整体错位一天。ICS 导出的日程同样偏移。
- **根因**：`time2` 归一到所在周的周一首日，`time1` 没有归一，两者差不再是 7 的倍数，`/7` 截断在负数方向靠 `betweenDays--` 这个特判兜——它只对整周差正确。
- **修复方案**（修在数据层）：解析出开学日期后同样 `cal.firstDayOfWeek = …; cal.set(DAY_OF_WEEK, 周首日)` 再取零点，删除 `betweenDays--` 特判；`ScheduleSettingsActivity` 的 DatePicker 保存时把日期自动吸附到所在周的周一（周日起始模式吸附到周日），toast 建议改成"已自动对齐到周一"。
- **验收**：单测（对齐 `CourseReminderSchedulerTest` 风格）：开学日为周一/周三/周日三种，断言开学前一日 countWeek=0、开学当周=1、下一周=2；本修复会改变非周一开学日期下的周次显示，属**用户可见行为修正**，CHANGELOG 需记录。

### P2-3 `CourseUtils.calAfterTime` 继续被调用：无前导零崩溃、跨天钳 00:00、占位行被污染

- **位置**：`utils/CourseUtils.kt:220-241`；调用点 `settings/TimeSettingsViewModel.kt:93-97`（`refreshEndTime`）、`settings/SelectTimeDetailFragment.kt:97`
- **现象/触发**：三个独立缺陷：
  1. `substring(0,2)/(3,5)` 假定 "HH:mm" 恒为 5 位：库里若混入 "8:00"（老分享文件/手改），拖一次"每节时长"滑块就 `NumberFormatException` 崩溃；
  2. 跨天钳到 `00:00`：23:30 + 50 分钟应该 00:20，被写成 00:00；
  3. `refreshEndTime` 对 `timeList` 全部 30 行生效：第 12~30 节那些 `00:00-00:00` 占位行的 endTime 被改写成 `00:45` 之类的脏数据并随"保存"入库。之后只要课表"一天课程节数"调过 11，这些行就是真实时间行，显示/提醒全错。
  `CourseReminderScheduler.minutesOfDay` 的文档（`CourseReminderScheduler.kt:1040-1042`）已经点名这个函数是坑，但两个调用点还在。
- **修复方案**：`refreshEndTime`/`SelectTimeDetailFragment` 改用分钟数算术（`minutesOfDay(clock)` + `clockOf(minutes)`，两个纯函数已存在且有单测）；跨天不再钳位，允许 end 早于 start 时保持现有显示即可；`refreshEndTime` 只作用于 `1..table.nodes` 范围内的节点，占位行不动。`calAfterTime` 改造后若无调用方则删除。
- **验收**：单测：23:30+50→00:20（或明确的不钳位行为）、"8:00" 不崩、占位行 00:00 保持；真机拖动时长滑块后保存，检查 DB 第 12+ 节行未被改写。

### P2-4 `ScheduleActivity.initView()` 每次调用都追加观察者与监听器：线性泄漏 + 重复刷新

- **位置**：`schedule/ScheduleActivity.kt:617-623`（7 个课程 LiveData 观察者，Activity 作用域，从不移除）、`:615`（每次调 `initEvent()`）、`:450`（`addOnButtonCheckedListener`）、`:475`（`addOnPageChangeListener`）
- **现象/触发**：每次从课表设置/管理页返回（`onActivityResult` → `initView()`）都追加一轮：第 N 次返回后有 N 组观察者。课程表任何一次变更触发 Room invalidation 时，旧观察者的查询也全部重跑，`allCourseList` 被反复置值、课表反复重绘；监听器堆叠后同一事件执行 N 次。功能幂等所以没人报 bug，但这是按返回次数线性放大的泄漏与浪费。
- **根因**：观察者生命周期挂在了"每次初始化"上而不是"Activity 生命周期"上；查询的 tableId 又依赖当次 `initView`，所以当时用了"每次新建 LiveData"的写法。
- **修复方案**：把 7 天课程观察改为"Activity 只注册一次，切表时换数据源"：保存 `List<LiveData<…>>` 与对应 `Observer`，`initView` 里先 `removeObserver` 旧的再注册新的；`initEvent()` 的监听器注册移到 `onCreate` 只做一次（按钮 Action 内部读 `viewModel.table`，本身与具体表无关；`onPageSelected` 同理）；`weekToggleGroup` 的按钮组每次 `removeAllViews` 后重新 `check`，监听器只加一次。
- **验收**：进设置返回 5 次后改一门课，Profiler/logcat 确认查询与重绘只发生一轮；翻页、周按钮行为不变。

### P2-5 ICS 导出文件名没有 .ics 扩展名

- **位置**：`schedule/ExportSettingsFragment.kt:48-57`（`EXTRA_TITLE = "日历-$tableName"`）
- **现象/触发**：`ACTION_CREATE_DOCUMENT` 不会补扩展名，导出文件是"日历-我的课表"无后缀；多数日历应用/系统导入器按扩展名识别，打不开。提示语"不要修改文件的扩展名哦"反而误导（它根本没有扩展名）。
- **修复方案**：标题改为 `"日历-$tableName.ics"`；提示语同步改。
- **验收**：导出文件带 `.ics`，系统日历/Google 日历可直接导入。

### P2-6 周视图小部件配置页：`getAppWidgetInfo` 可为 null 未防

- **位置**：`schedule_appwidget/WeekScheduleAppWidgetConfigActivity.kt:50`
- **现象/触发**：个别启动器/厂商时序下 `getAppWidgetInfo(mAppWidgetId)` 返回 null，`.provider` 直接 NPE，配置页白屏崩溃，小部件留在"载入出现问题"状态。
- **修复方案**：判空 → `finish()` 并 toast"小部件信息读取失败，请重新添加"。
- **验收**：单测/手动模拟 id 无效路径不崩溃。

### P2-7 `importFromFile` 的结构与路径判空缺失

- **位置**：`schedule_import/ImportViewModel.kt:82-94`（`uri.path!!`、`openInputStream(uri)!!`、五个 `fromJson` 结果均未判空）
- **现象/触发**：文件某一行是字面 `null` 或行数虽够但内容为空时，`fromJson` 返回 null，`timeDetails.forEach` NPE 崩溃，而不是走"文件格式不对"的友好提示；`uri.path` 为 null 时同样崩。
- **修复方案**：入口统一校验：path 判空、"wakeup_schedule" 包含判断已有；五个 `fromJson` 结果逐一判 null 并抛 `Exception("文件不完整或已损坏：…")`（消息面向用户）。
- **验收**：单测用残缺文件（改行、置 null）触发导入，得到可读错误而非崩溃。

---

## 3. P3 问题（边界 / 一致性 / 体验）

1. **设置页"当前周"保存按下标定位**：`schedule_settings/ScheduleSettingsActivity.kt:246` 用 `mAdapter.data[position - 1] as HorizontalItem` 取"学期开始日期"行。搜索过滤激活时 `position-1` 可能是 `CategoryItem("搜索结果")` → `ClassCastException`。改为按 `title == "学期开始日期"` 查找。
2. **主线程同步重排提醒**：`today_appwidget/TodayCourseAppWidget.kt:46,71` 与 `settings/SettingsActivity.kt:209,230,237,244,419,425` 在主线程直接调 `reschedule()`（内部是 Sync DAO + 最多几百枚 PendingIntent 循环，靠 `allowMainThreadQueries` 不崩但占主线程，广播有 10s 上限）。`App.renewReminderWindow` 已经示范了正确姿势（IO 协程）；`CourseReminderReceiver` 的系统事件分支同样在主线程。建议统一改为 `goAsync` + IO 协程（基建已有：`AppWidgetUtils.goAsync`）。
3. **加课页每次点开对话框都重新 observe**：`course_add/AddCourseActivity.kt:159-162,175-180`——同一 LiveData 上按 N 次挂 N 个观察者（幂等但泄漏）。移到 adapter 初始化或 `onViewCreated` 注册一次。
4. **周次选择器的触点命中用整除推算**：`widget/SelectedRecyclerView.kt:26-28` 假定"行高 == 列宽"且无 itemDecoration，6 列周次网格在带边距的布局下边缘一格命中错位。改用 `LayoutManager` 子视图命中或把 decoration 计入。
5. **"还没有开学"时周视图小部件头部日期显示下一周**：`utils/AppWidgetUtils.kt:176` 在 `countWeek == 0`（curWeek）与 `week = 1` 之间算出 `amount = 1`，头部日期是下周的。显示瑕疵；修法是 curWeek < 1 时按"本周"显示或显示空。
6. **`resolveStartDate` 用 `WEEK_OF_YEAR` 反推学期周一**：`schedule_import/sues/SuesEamsImporter.kt:258-266`——跨年时 `Calendar.add(WEEK_OF_YEAR)` 依赖 locale 周规则（minimalDaysInFirstWeek），极端情况下反推的第 1 周偏 3~6 天。改成 `cal.add(Calendar.DATE, -7 * (currentWeek - 1))` 更稳。
7. **`LoginWebActivity` 的 ACTION_VIEW 路径同时加载 FileImportFragment 又直接开始导入**：`schedule_import/LoginWebActivity.kt:50-61`——页面与导入并行，失败时 toast 会叠在无意义的文件选择页上。二选一：纯导入路径不加载 Fragment。
8. **`getVirtualBarHeight` 用反射读 `getRealMetrics`**：`utils/ViewUtils.kt:125-139`——`Display.getRealMetrics` 自 API 17 就是公开 API，反射无必要且在 API 30+ 有弃用告警；直接调用并加版本分支。
9. **AOSP 单 uid 闹钟上限的余量说明**：`CourseReminderScheduler.kt` 两类各 250 枚 + 跨天/刷新/倒计时 3 枚 = 503，理论上越过 AOSP 的 500 上限（注释里写"两段加起来正好也是 500"未计固定 3 枚）。实际课表 7 天窗口最多约 210 枚触不到；修复时顺手把 `MAX_REMINDERS_PER_KIND` 注释改准，或下调到 248。

---

## 4. 死代码与清理项（修复时顺手处理，需逐条确认无调用后删除）

| 位置 | 内容 | 备注 |
| --- | --- | --- |
| `dao/CourseDao.kt:37-42` | `coverImport` | 无任何调用方；且 `courseBaseList[0]` 对空列表会 IOOBE，留着就是隐患 |
| `utils/CourseUtils.kt:204-218` | `isQQClientAvailable` | 无调用方；API 30+ 包可见性下也不可靠 |
| `utils/ViewUtils.kt:171-195` | `saveImg` | 无调用方；直写外部存储 DCIM，targetSdk 29 上本就会失败 |
| `utils/ViewUtils.kt:97-118` | `checkDeviceHasNavigationBar` | 无调用方；反射读系统属性 |
| `utils/CourseUtils.kt:220-241` | `calAfterTime` | P2-3 修复后若再无调用方则删除 |
| `schedule/ScheduleActivity.kt:492-494` | `onPageSelected` 里的 `catch (e: ParseException)` | 该分支不可能抛 ParseException，死接 |

---

## 5. 已核查、确认无问题的部分（审查证据）

这些是本次重点验证过、**不需要动**的地方，写下来避免后续重复排查：

- **dayMap 列映射**（`ScheduleUI.init`）：mondayFirst/sundayFirst × showSat/showSun 四种组合逐一代入验证，列 id、表头 id 均在已创建范围内，周日隐藏+周日起始等组合不会越界。
- **ScheduleFragment 表头日期索引**（`onViewCreated` 67-73 行的三分支）：对每种 dayMap 组合核对了 `weekDate[...]` 下标，均取到正确日期。
- **提醒 requestCode 分段**：START/END 两段 `[0x5700,0x5800)/[0x5800,0x5900)` 与 `MAX_REMINDERS_PER_KIND=250` 的扫描上界自洽（见 P3-9 的小注），取消路径覆盖全部段 + legacy 0..63。
- **`remainingMinutes` 的 `floorDiv` 取整**、跨天/夏令时用 `Calendar.add` 而非固定毫秒、`nextTriggerMillisAt` 的 00:05 闭环：单测已钉住，逻辑核对无误。
- **`alarmsForDay` 的两遍扫描**（先排下课提醒再决定抑制谁）与三个开关的组合语义：注释所述两类丢提醒路径确实已被堵住。
- **数据库主键/外键**：`CourseDetailBean` PK(6 列)、`CourseBaseBean` PK(id,tableId)、`natural join` 连接键、CASCADE 链（删课表→删课程→删安排）核对一致；`@Delete` 按主键匹配成立（`deleteCourseBean` 可正确删行）。
- **`Common.weekIntList2WeekBeanList`**：单元素、连续、单双周、跳跃、乱序输入逐一推演，输出与 `SuesEamsImporter.weekSegments` 语义一致（孤立周 = type 0）。
- **`SuesEamsImporter`**：归一化逐字符保长（原文切片不错位）、节次/周次反向区间纠正、`J30x`/教学楼/房号三级判定与前后缀排除、三套作息覆盖完整性（coursetime 52 项自测佐证）。
- **`TimetableChangeWatcher`**：表名与 Room 默认表名一致（有单测对账 sqlite_master）、弱引用持有（App 强持有）、去抖 token 用法正确。
- **`BackgroundStore`**：私有目录复制、旧扩展名清理、只删自家文件的判定。
- **`WebViewLoginFragment`**：自动导航的停止位/去重账本（`SuesDocLedger` 有单测）、SSL 错误不静默放行、JS 接口仅暴露白名单方法、探测重试有上限。
- **Manifest**：提醒接收器的四条系统广播均在隐式广播豁免清单；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的用途与文档一致；targetSdk 29 下无需 `SCHEDULE_EXACT_ALARM` 的论证成立。
- **构建签名脚本**（`app/build.gradle`）：口令不落盘、只在产出安装包的任务族索要口令、headless 降级路径，核对无泄密与逻辑错误。

---

## 6. 未深查范围（诚实声明）

- `app/src/main/java/splitties/**`：上游 vendor 拷贝，仅确认无本项目改动痕迹，未逐行审。
- `widget/colorpicker/**`、`NumberPickerView`、`BaseRecyclerViewAdapterHelper`、`Toasty`、`biweekly`、`Gson`、`Glide`、`Room`：第三方库本体未审，只审了调用面。
- `res/` 布局与资源、`xml/` 小部件元数据：未逐项核对（渲染逻辑都在 Kotlin 侧）。
- 未做：真机运行验证、`assembleRelease`（R8/混淆规则未验证）、仪器测试。
- 深色模式、RTL、繁体/英文资源上的显示问题不在本次范围。

---

## 7. 建议修复顺序

1. **P1-2 + P1-5**（同根：外部数据不可信）→ 先加解析防御保底，再修导入归一化，各自带单测。
2. **P1-1**（导出 use{}，改动最小、收益直接）。
3. **P1-3 + P1-4**（删除保护，涉及一次用户可见行为变更，CHANGELOG 记录）。
4. **P2-2 + P2-3**（时间/周次纯函数，先补单测再改实现，防止回归）。
5. **P2-1、P2-4、P2-5、P2-6、P2-7**。
6. P3 与死代码清理（清理项删除前逐条再 grep 一次调用方）。

每批修复后必须：`powershell -ExecutionPolicy Bypass -File coursetime/verify.ps1`（52 项）、
`cd android; ./gradlew test`（95 项 + 新增单测）全绿，再提交。

---

## 8. 修复回填记录（2026-09-16）

全部条目已实施，与原方案的差异逐条说明如下：

| 编号 | 状态 | 实施说明 |
| --- | --- | --- |
| P1-1 | ✅ 已修 | 两个导出都改为 `openOutputStream(...)?.use { … }`，目标打不开时抛「无法打开导出目标」 |
| P1-2 | ✅ 已修 | `importFromFile` 归一化补齐 `room`/`teacher`/`courseName`/`color`（与 timeGroup 同一块） |
| P1-3 | ✅ 已修 | 长按删除先弹确认框；`TableDao.deleteTableAndFixDefault` 在事务里把默认标记转移给剩余第一张；删除后刷新两个小部件 |
| P1-4 | ✅ 已修 | `TableDao.countTablesUsingTimeTable` + `TimeSettingsViewModel.deleteTimeTable` 入口拦截，文案给出引用数；原死分支改为显示真实原因 |
| P1-5 | ✅ 已修 | 新增 `ViewUtils.parseCourseColor`（纯 JVM 可测，不依赖 Color.parseColor），三处渲染点全部收口；日视图小部件的透明度拼接改为 `ColorUtils.setAlphaComponent`，行为等价 |
| P2-1 | ✅ 已修 | `initDefaultData` 全部先查后插（幂等），默认课表插入失败时记日志、不置 `has_adjust`、下次启动重试 |
| P2-2 | ✅ 已修 | `daysBetween` 把开学日期也归一到周首日，删除 `betweenDays--` 特判。**偏差**：DatePicker 的"自动吸附周一"没有做——归一化修在算法层之后，任意开学日期的周次都已正确，吸附反而会擅改用户选的日期；设置页保留"建议选择周一"的提示 |
| P2-3 | ✅ 已修 | `calAfterTime` 原地重写为分钟数算术（复用 `minutesOfDay`/`clockOf`），跨天按 24h 回绕、无前导零可解析、解析失败原样返回；**偏差**：函数保留（两个调用点仍需要它），不是删除。`refreshEndTime` 跳过 `00:00-00:00` 占位行（`TimeSettingsViewModel` 不知道课表 nodes，用占位形状判定比传 nodes 更稳） |
| P2-4 | ✅ 已修 | `initView` 重挂七天课程观察者前先 `removeObserver`（登记在 `courseObservers`）；`initEvent()` 挪到 `onCreate` 只注册一次 |
| P2-5 | ✅ 已修 | ICS 导出标题带 `.ics` |
| P2-6 | ✅ 已修 | 配置页对 `getAppWidgetInfo` 判空，null 时提示并 finish |
| P2-7 | ✅ 已修 | `uri.path`、`openInputStream`、五个 `fromJson` 结果逐一判空，报「文件不完整或已损坏（××无法解析）」 |
| P3-1 | ✅ 已修 | 「当前周」保存改按标题定位「学期开始日期」行 |
| P3-2 | ✅ 已修 | 跨天闹钟、小部件 onUpdate、系统广播（BOOT/TIME/TIMEZONE/覆盖安装）、设置页 6 处重排全部改为 `goAsync`/`launch` 异步执行 |
| P3-3 | ✅ 已修 | `SelectTimeFragment`/`SelectWeekFragment` 增加 `onSaved` 回调，加课页两处 observe 改为回调 + `notifyItemChanged` |
| P3-4 | ⏸ 缓办 | `SelectedRecyclerView` 触点几何需要真机验证手感，盲改风险大于收益；维持现状，后续有真机再修 |
| P3-5 | ✅ 已修 | 主课表与周视图小部件的头部日期基准改为 `maxOf(countWeek, 1)` |
| P3-6 | ✅ 已修 | `resolveStartDate` 改为 `add(Calendar.DATE, -7*(cw-1))` |
| P3-7 | ✅ 已修 | ACTION_VIEW 路径不再加载 FileImportFragment |
| P3-8 | ✅ 已修 | `getVirtualBarHeight` 去反射直接调 `getRealMetrics`（minSdk 21 > 17） |
| P3-9 | ✅ 已修 | `MAX_REMINDERS_PER_KIND` 文档更正（两段 + 三枚固定闹钟 = 503） |
| 9-1 | 📌 已记录 | `TableBean.timeTable` 外键 SET_DEFAULT 与 NOT NULL 列矛盾——本轮以入口拦截绕开，schema 修正待确认后做（见第 9 节） |
| 清理 | ✅ 已删 | `coverImport`、`isQQClientAvailable`、`saveImg`、`checkDeviceHasNavigationBar`、`onPageSelected` 的死 catch |

**验证结果**：`coursetime/verify.ps1` 52 项全过；`gradlew test` **116 项全部通过、0 失败**
（基线 95 项 + 新增 21 项，来自 5 个新测试类：`CourseColorParseTest`、
`CourseUtilsTimeMathTest`、`TableDeletionGuardTest`、`UpdateUtilsSeedTest`、
`ICalExportTest`）；`gradlew assembleDebug` 出包成功。
修复导致的**用户可见行为变化**：删除课表需确认、
删默认表后自动切到剩余课表、被引用的时间表不可删（有原因提示）、ICS 导出带 .ics 后缀、
ICS 导出对写不进的课会列出清单、非周一开学日期下第二周起周次 +1（修正）、跨周边界日期
不再提前显示为"第 1 周"。

**遗留风险**：P3-4 缓办；真机回归（小部件两块、提醒链路、WebVPN 导入全流程）未做，
建议下一轮真机验证时按本手册各条"验收方法"过一遍。

---

## 9. 修复过程中新发现的陷阱（记录在案，本轮不改）

### 9-1 `TableBean.timeTable` 外键动作与列约束互相矛盾（schema 层，改前需确认）

`bean/TableBean.kt` 里 `timeTable` 的外键声明 `onDelete = SET_DEFAULT`，但该列本身
`NOT NULL`（Kotlin 非空 `Int = 1`）且 Room 没有为它生成 SQL 级 `DEFAULT`。于是
**只要有课表引用着某张时间表，删这张时间表必然抛 `NOT NULL constraint failed`**
（SET DEFAULT 在子行上写 NULL）。两点后果：

- P1-4 的真实故障模式比原分析的"静默改指默认作息"更早一步死在约束上——旧 UI 的
  catch 提示碰巧兜住了一部分设备；入口拦截修复后这条路径已经安全，行为正确。
- 但 schema 的矛盾还在：今后任何"直接删时间表"的新代码都会撞上它。正确修法是
  `onDelete = RESTRICT`（或给列加 `@ColumnInfo(defaultValue = "1")`），两者都需要
  版本迁移，按仓库规则属"需确认"变更，本轮不动。

单测 `TableDeletionGuardTest` / `UpdateUtilsSeedTest` 的 `@Before` 里因此不能调
`clearAllTables()`（它会先删 TimeTableBean 而触发同一约束），只能按外键依赖顺序手动
清行——测试文件里有完整注释说明这条陷阱，防止后人"顺手优化"回去。

---

## 10. 与 `IMPLEMENTATION-MANUAL.md` 的交叉核对

工作区里另有 2026-09-15 的《实现审查与改进手册》（用户另行产出），其 P0 正确性清单与
本手册的对账结果（本轮已全部闭环）：

| 对方编号 | 内容 | 状态 |
| --- | --- | --- |
| P0-1 导出流未关闭 | 同本手册 P1-1 | ✅ 已修 |
| P0-2 UpdateUtils 空 catch | 同本手册 P2-1 | ✅ 已修 |
| P0-3 exportICS 逐课吞错、部分成功不可见 | 本轮**补修**：`getClassEvents` 返回新增事件数，`exportICS` 返回漏课名单，主界面弹窗列出未写入的课程 | ✅ 已修 |
| P0-4 calAfterTime | 同本手册 P2-3（保留函数、原地重写为分钟数算术，理由见第 8 节偏差说明） | ✅ 已修 |
| P0-5 AppWidgetUtils 六处裸 PendingIntent | 本轮**补修**：全部改走 `pendingIntentFlags()`（targetSdk 31+ 的前置条件） | ✅ 已修 |
| P0-6 release 删除全部 Log 与"日志自证"设计矛盾 | 本轮**补修**：`proguard-rules.pro` 只删 v/d，保留 i/w/e 并注释原因 | ✅ 已修 |
| P0-7 死代码 | 同本手册第 4 节清理项 | ✅ 已删 |
| P0-8 详情点击吞掉异常原因 | 本轮**补修**：提示带上原因与「多课表管理」的出路，悬置多年的 TODO 落地 | ✅ 已修 |
| P0-9 ICalUtils 死赋值 + locale 依赖 + 缺测试 | 本轮**补修**：删 `YEAR=2016` 死赋值；周定位显式 `firstDayOfWeek = MONDAY`（美式 locale 下周日课程会整周偏移）；新增 `ICalExportTest` 钉住两个契约 | ✅ 已修 |

对方的 P1（依赖/kapt→KSP/jcenter/abiFilters）、P2（targetSdk 路线、网络安全配置收紧、
`allowMainThreadQueries` 退役、协程结构化、文件导入类型校验放宽）、P3（作息双源对账、
HH:mm 解析收敛、lint 基线、proguard 无效指令）属于**现代化与路线图**，多数涉及依赖变更
或构建配置，按仓库规则属"需确认"变更，本轮不实施；其中两条小而有价值的边界问题值得
单独留意：

- **文件导入的 `path.contains("wakeup_schedule")` 判定过严**（对方 P2-5）：部分 SAF
  提供方的 URI path 不含文件名，合法文件会被误拒。本轮 P2-7 已把该处改成可空安全，
  但"按显示名判断"的放宽未做——需要真机用下载提供方实测后决策。
- **作息数据双源**（对方 P3-1）：`coursetime` 模块与 `SuesEamsImporter` 各维护一份作息
  表，靠人工对账。结构方案（app 依赖 coursetime 模块）或对账测试可作后续任务。

---

## 11. 收尾批次（可闭环项全部实施，2026-09-16）

对方手册 P1/P2/P3 里**能由构建与单测闭环验证**的项已全部落地；需要真机实测的项保留在
路线图上，理由逐条写明。

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 对方 P1-4 `abiFilters` 三套 ABI | ✅ 已删 | 无原生代码，纯包体放大；`assembleDebug` 验证出包 |
| 对方 P2-3 schema 留档 | ✅ 已做 | `exportSchema = true` + kapt `room.schemaLocation`，构建产出 `app/schemas/9.json`；`allowMainThreadQueries` 退役需全量审计 + 真机，保留开关 |
| 对方 P2-4 协程收口 | ✅ 已做 | `utils.appScope`（SupervisorJob + IO）替代 GlobalScope 与裸 `CoroutineScope`；`goAsync` 默认参数与 `App.renewReminderWindow` 共用 |
| 对方 P2-5 文件导入类型校验过严 | ✅ 已做 | `looksLikeWakeupSchedule`：path → DISPLAY_NAME 两级判断，都拿不到时放行到解析阶段报错，不再错杀合法文件 |
| 对方 P3-1 作息双源对账 | ✅ 方案1 | 新增 `SuesImporterTimeTableTest`：App 侧作息经 `parse()` 全链路逐格钉在学校公布值上（coursetime 侧由 verify.ps1 钉住）；结构合并（settings.gradle 引入 coursetime）留给后续决策 |
| 对方 P3-2 HH:mm 解析收敛 | ✅ 已做 | `CourseTimes.minutesOf`、`ICalUtils.timeToCalendar` 全部委托 `CourseReminderScheduler.minutesOfDay`，校验规则只剩一份 |
| 对方 P3-4 lint 基线 | ✅ 已做 | `lint-baseline.xml` 记录存量，`abortOnError true` 挡新增问题 |
| 对方 P3-5 proguard 无效指令 | ✅ 已删 | `-optimizationpasses` / `-optimizations` / `-dontpreverify`（R8 静默忽略） |
| 对方 P1-1 kapt→KSP、P1-2 依赖升级、P1-3 jcenter/jetifier、P1-5 compileSdk 36 | ⏸ 未做 | 每项都要求"升级后手动走查主要页面/整夜提醒实测"，无真机回归条件下不做 |
| 对方 P2-1 targetSdk 路线、P2-2 明文流量收紧 | ⏸ 未做 | 需要整夜 Doze 实测 / 抓包确认 WebVPN 全程 HTTPS，真机前提未满足 |

**收尾批次验证**：`coursetime/verify.ps1` 52 项、`gradlew test` **119 项全绿**
（新增 `SuesImporterTimeTableTest` 3 项；累计新增 6 个测试类 24 项）、
`gradlew assembleDebug` 出包成功、`gradlew lintDebug` 带 `lint-baseline.xml`
+ `abortOnError true` 通过。附带修复：移除无 androidTest 源码集的 instrumented
测试死依赖（其未缓存的传递构件曾导致 lint 在离线模式下无法解析类路径）。

---

## 12. 真实学期样本审计 + 连堂网格（2026-09-16）

### 12-1 样本来源与隐私边界

用教学服务中心课表接口 `for-std/course-table/get-data` 拉了**全部 7 个学期**
（2023-2024-1 起至 2026-2027-1，semesterId 521/541/542/562/582/602/622）的原始返回，
共 **222 条排课文本、80 门课程安排**。数据**只在内存里分析、没有落盘**：解析器逻辑
（`P_ENTRY`/`normalize`/分楼正则/周次切分）在浏览器里按 App 的实现逐条复刻后跑真实样本，
教师姓名在输出中打码；设备侧截图核对完即删。

### 12-2 R1 单双周标注导致**整份导入失败**（严重，已修）

| | 内容 |
| --- | --- |
| 症状 | 导入含单双周的学期时整份失败，一门课都进不来 |
| 复现 | 222 条真实样本里 **8 条**（4 个学期）长这样：`2~16(双)周 星期五 3~4节 …`、`1~3(单),4~16周 星期一 6~7节 …` |
| 根因 | `P_ENTRY` 的周次子表达式只认数字与区间，不认教务系统的「(单)」「(双)」后缀 → `parseEntry` 抛 `无法解析排课文本：…` → `parse()` 直接抛出，**整次导入回滚** |
| 修复层 | 解析器的周次展开（最早出错的那一层）：`WEEK_PART` 把标注纳入周次语法，`expandWeeks` 按标注过滤奇偶 |
| 关键点 | **不能把标注当噪声删掉**：`2~16(双)` 删掉标注会展开成第 2~16 周的每一周，整学期排错。`(双)`=偶数周、`(单)`=奇数周，展开后的列表与 App 的 `type`（1 单周/2 双周）语义一致，`weekSegments` 再由步长复核一遍 |
| 验证 | `SuesWeekParityImportTest` 5 项（双周/单周/同一串里混用/散列周次拆段/空集不炸）；修正后的正则在 7 个学期 222 条上重跑，**0 条无法解析**，8 条单双周展开结果逐条核对正确 |
| 为什么现在才发现 | 当前学期（2026-2027-1）恰好没有单双周课，`get-data` 的返回里一条都没有 |

### 12-3 R2 连堂之间画虚线（已修，用户报的"网格密度"）

学校公布的作息表里有些节次**共用一格**（第 1、2 节都是 8:15~9:35，第 6、7 节都是
13:20~14:40……），在它们之间画虚线会把一次连堂读成两节课。上午第 3~5 节按楼错峰，
A 楼是「3」与「4-5」、B/C 楼是「3-4」与「5」，连格形状还不一样。

- **判据不写死节次**：`CourseTimes.hasGridLineAfter(node)` 比较默认分组里相邻两节的
  起止时刻，时刻相同即同一格。数据驱动，用户自定义的作息表也成立，且不会与导入器
  按楼宇推导的连格形状分叉。
- **接线**：`ScheduleUI` 新增 `times: CourseTimes?` 参数 → 预计算 `gridLinesAfterRow`
  → 交给 `DashedGridDrawable`（背景层与前景层共用同一份）。没有作息数据时全部画线，
  观感与改动前一致。
- **调用点**：`ScheduleFragment`（`viewModel.courseTimes`，`initView` 里先赋值的，
  早于 ViewPager 建视图）与 `ScheduleAppWidgetService`（`times`，非 lateinit，空表等同旧观感）。
- **验证**：`SessionGridLineTest` 6 项（A/B/C 三套方案逐节钉住 + 用户报的 1-2 用例 +
  自定义作息不吞线 + 无数据不吞线）；真机（Redmi/1220×2656）实机截图核对：
  1-2、3-4、6-7、8-9、10-11、12-13 之间**没有**横线，只在 2\|3、4\|5、5\|6、7\|8、9\|10 有。

### 12-4 核查通过、**不需要动**的部分（真实数据佐证）

- **分楼逻辑**：222 条文本里出现的 61 种教室串逐一核对官方作息表的分楼口径
  （A/F/J301 → A 方案；B/C/J302 室及其他楼宇 → B 方案；D/E/J303 → C 方案），
  **222/222 全部正确**。包括容易被误判的两类：「交8B323」「现代交通工程中心8B317-319」
  因房号前有数字被负向后顾排除、落到"其他楼宇"的 B 方案（正是官方口径）；
  「训1119-1121」「航飞楼6201-6203」「校外」「平台由开课部门通知」同样落 B 方案。
- **开学日期反推**：`resolveStartDate` 用 `currentWeek` 反推第 1 周周一。7 个学期的
  `currentWeek`（1/28/54/83/107/134/158）是**全局连续周计数**，反推结果与接口里
  权威的 `lessons[].semester.startDate` **逐一完全相同**（2026-09-14 / 2026-03-09 /
  2025-09-08 / 2025-02-17 / 2024-09-02 / 2024-02-26 / 2023-09-11）。逻辑正确，
  本轮不改。**可选改进**（未做）：直接读 `semester.startDate` 可以去掉对"手机日期正确"
  这个前提的依赖——手机日期错时当前实现会把开学日期整体算错，所有提醒跟着错。

### 12-5 待决策（未改）

- **单条坏数据 = 整份导入失败**：R1 修完之后，"某个学期出现一种没见过的写法"仍然会让
  用户一门课都拿不到。可选的中间路线是"跳过无法识别的条目 + 明确告知跳了几条"，
  属产品行为变更，等确认再做。
- **节数上限固定 15**：真实 7 个学期用到的最大节次都是 13（`1-10节`、`10-13节`
  这类跨节实验课），`MIN_NODES = 15` 让课表底部常驻两行空格。学校作息表确实定义了
  第 14、15 节，故维持现状。

**本轮验证**：`gradlew test` **130 项全绿**（新增 `SuesWeekParityImportTest` 5 项 +
`SessionGridLineTest` 6 项）；`gradlew assembleDebug` 出包成功；真机 `install -r`
后实机截图核对连堂网格通过。

---

## 13. 时间轴修复 + 全量课程复查（2026-09-16 续）

### 13-1 R3 左侧时间栏"时间倒流"（用户截图报的，已修）

| | 内容 |
| --- | --- |
| 现象 | 时间栏竖着读是 `… 09:35 / 09:55 / 3 / 11:15 / 09:55 / 4 / 11:15 …`，第 3、4 节各自都把开始与结束标了一遍，看起来时间往回跳 |
| 根因（两层） | ① 时间栏按**节次**而不是按**格**标时间：连堂两行共用 09:55~11:15，两行都标就成了 `11:15 → 09:55`；② 更底层——构造时那句 `visibility = …` 写在 `FrameLayout.LayoutParams(...).apply {}` 里，而 `FrameLayout.LayoutParams` **没有 `visibility` 成员**，Kotlin 把它解析到外层**行 FrameLayout** 上：时间标签自己从来没被控制过，反而在 `showTimeDetail=false` 时把整行（连节次号）设成 GONE。`applyPreferences()` 走的是 `findViewById(...).visibility`，所以设置开关看起来是好的，把构造期这条死路掩盖了 |
| 修复层 | ① 时间标签的可见性：连堂**首行给开始、末行给结束**，中间行只留节次号（判据复用 `CourseTimes.hasGridLineAfter`，与网格线同一份，不会分叉）；② 可见性设在**标签自己**身上，布局参数里只放布局参数 |
| 用户可见影响 | 时间栏一格只标一次上/下课时间，竖读严格递增；顺带消除"关掉节次栏时间后整行消失"的隐患 |
| 验证 | `TimeColumnLabelTest` 5 项（竖读不倒流、连堂首末各标一次、单节两标都在、A 案形状、关开关全隐藏）；渲染探针 `_crop/render_light.png` 实图核对为 `08:15 / 1 / 2 / 09:35 / 09:55 / 3 / 4 / 11:15 / 11:20 …` |

### 13-2 全部课程能否成功导入（复查结论）

7 个学期、222 条排课文本、86 门不同课程：**222/222 解析成功，0 条失败**；
`lessons` 里有 **4 门课没有任何排课时间**，逐条查了接口原文 ——
`scheduleText` 的四个字段（`dateTimeText`/`dateTimePlaceText`/`dateTimePlacePersonText`/`roomSeatText`）
**全是 null**，即教务系统本身还没给它们排时间（军训、军事理论、大学物理实验A 上下），
不是解析漏了。导入后这些课不会出现在课表里，符合数据现状；
若要让用户明确知道"少了哪几门、为什么少"，需要在导入完成提示里加一句，属产品行为变更，待确认。

### 13-3 教学楼 ↔ 上课时间是否正确（复查结论）

- **三套作息逐节对账**：把 `COMMON_BLOCKS` + `MORNING_BLOCKS` 展开成 A/B/C 三套
  (节次 → 起止) 全表，与学校公布作息**逐节比对，0 处不一致**；默认分组确实就是 B 方案
  （第 3 节 09:55~11:15、第 4 节 09:55~11:15、第 5 节 11:20~12:00）。
- **分楼判定**：222 条真实文本、61 种教室串，**222/222 落到正确的方案**
  （A/F/J301→A，B/C/J302 室及其他楼宇→B，D/E/J303→C），含 `交8B323`、
  `现代交通工程中心8B317-319`、`训*`、`航飞楼*`、`校外` 等易误判项。
- 结论：**"教学楼对应的上课时间"这条链路上没有发现错误**，错的是 13-1 那个显示问题。

### 13-4 默认「第 3~5 节」用 B 方案（09:55~11:15 等）是否合理

用 7 个学期的真实安排（去重后 223 条，去重键 = 课程+星期+起始节+周区间+单双周+教室）
统计学生自己的课落在哪套作息上：

| 方案 | 适用楼宇 | 该同学安排数 | 占比 |
| --- | --- | --- | --- |
| B | 教学楼B、C楼，J302室，**其他楼宇、教学场所** | 105 | **47%** |
| C | 教学楼D、E楼，J303室 | 63 | 28% |
| A | 教学楼A、F楼，J301室 | 55 | 25% |

**合理的地方**：B 是学校口径里的"其他楼宇、教学场所"兜底方案，也是单一占比最大的一套；
固定不动能保证学期之间同一行的时间不变（当初就是为此把"用得最多的那套"改成固定 B 的）。
**不合理的地方**：它只对得上约一半的安排——A 楼的第 3 节 10:35 就下课（轴写 11:15）、
C 楼第 3 节 10:15 才开始（轴写 09:55），一个竖排轴物理上表示不了三套并行的作息。

可选方案（推荐第 3 条）：

1. **保持固定 B（现状）**：稳定、有学校公布口径背书；代价是另外两套楼宇的课与轴对不上。
2. **按本次导入里占比最大的那套定轴**（改动前的旧实现）：轴与多数课块一致，但换学期
   选课楼栋一变，上午 3~5 节的时间就跟着跳——当初正是这个原因被否掉的。
3. **轴不动，把真实时间显示在课块上（推荐）**：课块时间按它**自己所在楼宇**的作息算，
   学生看块就知道几点上下课，"轴对不对得上"就不再要紧。**这条现在就能用**——
   课表设置 →「在格子内显示上课时间」（`TableBean.showTime`，默认关）会把每块自己的
   开始时间写到块首；差的是它只写开始、不写结束，且默认关。要做的是"默认开 + 补上结束时间"。
4. **轴上并列三套**（如第 3 行标 `09:55/10:15`）：信息全，但 7 天布局下这行字号只有 9sp，
   会挤成一团，不建议。

**建议**：保持默认轴 = B 不动，改为推进第 3 条（课块上标自己的真实上下课时间）。
这条不需要新增概念，只动课块文案，也不会与网格/时间栏的判据耦合。

---

## 14. 未排课课程、时间栏方案可选、竖虚线缺失（2026-09-16 三续）

### 14-1 R4「无排课时间」的课程会**炸掉整份导入**（严重，已修；勘误 §13-2）

上一轮我说这 4 门课"只是数据没有时间、不是解析漏了"——**这个结论是错的**，当时的核对脚本
用 `v.textZh || ''` 读字段，把 JSON null 当成了空值；而 Kotlin 侧走的是 org.json 的
`optString`，它对**显式 null 返回字面量 `"null"`**，于是流程变成：
`optString → "null"`（非空）→ 当成一条排课文本 → `parseEntry("null")` 匹配不上 →
抛 `无法解析排课文本：null` → **整份导入回滚，一门课都进不来**。

真实数据里这四门课（军训、军事理论、大学物理实验A 上/下）的
`scheduleText.dateTimePlacePersonText.textZh` 就是**字段级 null**，落在 3 个学期上
（2023-2024-1、2023-2024-2、2024-2025-1）。当前学期恰好没有，所以一直没被踩到。

| | 内容 |
| --- | --- |
| 修复层 | 字段读取：新增 `JSONObject.jsonText(key)`，把 **JSON null 与字段缺失都当空串**（并顺带把字面量 `"null"` 也当没有）；`lessonName` 与 `lessonEntries` 都改走它 |
| 为什么修在这里 | 这是"外部数据 → 内部字符串"的唯一入口。修在解析器下面（容错跳过）会把真错误吞掉，修在上面（调用方判空）等于让每个调用方都记着 org.json 的这条脾气 |
| 验证 | 忠实复刻 `optString` 语义重跑 7 个学期：修复前 3 个学期必然抛异常，修复后 **222 条全部可解析**；单测 `courses with no schedule text at all are reported` 钉住 |

### 14-2 需求二：把"没有排课时间"的课程主动说清楚（已实现）

- `SuesEamsImporter` 新增 `SuesImportData.coursesWithoutSchedule`：**按最终有没有落到安排行**
  判断（"没有排课文本"与"文本展开后一周都不剩"两种情形都算），同名课程只要有一次安排就算排上了。
- `ImportViewModel.importFromSues` 改返回 `SuesImportResult(courseCount, coursesWithoutSchedule)`；
  `WebViewLoginFragment` 把它放进 `setResult` 的 extra（`Const.EXTRA_UNSCHEDULED_COURSES`）。
- `ScheduleActivity` 导入完成后的那个提示框里直接点名：

  > 以下 N 门课在教务系统里**还没有排上课时间**，不会出现在课表里：**军训、军事理论…**

  这样"少课"从"要用户自己对着教务系统数"变成"导入完就告诉他"。
- 验证：3 条单测（字段级 null、对象级 null/空串、周次展开为空集）。

### 14-3 需求四：三套作息都可用，默认按导入占比、可在设置里手动指定（已实现）

- **导入时写全 A/B/C 三套作息行**（外加默认分组），不再只写"这次用到的"：设置页允许把
  时间栏切到任意一套，而**切换不该要求重新导入**（重新导入会换一张课表、丢掉手工改动）。
- **默认分组（"自动"）取本次导入里安排条数最多的那套**：`axisSchemeOf(rows)` 直接数**去重后的
  安排行**（不是原始文本，那会把同一格重复列出的教师差异算成多节课）；并列优先 B
  （学校口径里的"其他楼宇、教学场所"），保证同一份数据每次导入得到同一个轴。
- **设置 → 常规 →「时间栏作息方案」**：自动（按导入占比）／A 方案（A、F 楼）／
  B 方案（B、C 楼及其他）／C 方案（D、E 楼）。存 `Const.KEY_TIME_AXIS_SCHEME`，
  回到课表即生效（`ScheduleActivity.onResume` 比对"已生效取值 vs 当前偏好"，真变了才重建，
  因为设置页还有一条不返回结果的入口）。
- **一处切换、全链路一致**：`CourseTimes.defaultGroup` 同时决定左侧时间栏、没有分组的课程的
  时间、以及课块纵向插值的基准；连堂判据（`hasGridLineAfter`）也读它，所以切到 A 方案时
  网格线会自动变成"3 单独一格、4-5 一格"。主课表、周视图小部件、日视图小部件、上课提醒
  四处都改用 `CourseTimes.ofPreferred(context, ...)`，不会各显示一套。
- **各学期"自动"会选哪套**（真实样本，去重后统计）：

  | 学期 | A | B | C | 自动选 |
  | --- | --- | --- | --- | --- |
  | 2023-2024-1 | 11 | 13 | 7 | B |
  | 2023-2024-2 | 11 | 16 | 2 | B |
  | 2024-2025-1 | 11 | 10 | 4 | **A** |
  | 2024-2025-2 | 8 | 15 | 11 | B |
  | 2025-2026-1 | 5 | 18 | 22 | **C** |
  | 2025-2026-2 | 2 | 13 | 9 | B |
  | 2026-2027-1（当前） | 7 | 20 | 8 | B |

  当前学期自动选 B，与之前的固定 B 一致 —— **这次改动不会改变现在看到的画面**；
  换成别的学期才会看到差别，而那时用户可以手动钉住某一套。这也正好回答了上一轮
  §13-4 的取舍：原来"固定 B"与"按占比"只能二选一，现在默认按占比、想稳就手动指定。

### 14-4 R5 周六与周日之间没有竖虚线（已修）

| | 内容 |
| --- | --- |
| 现象 | 7 天全显时，"周六｜周日"之间缺一条 Y 向虚线 |
| 根因 | `DashedGridDrawable.draw` 的竖线是"每列取左边界，最后一列改取右边界"，本意是"N 天恰好 N 条线"。但这样最后一列**自己的左边界被跳过**——7 天时画出来的是 时间列\|一、一\|二、…、五\|六、以及周日右侧那条外框，周六与周日之间那条正好是丢掉的 |
| 修复层 | 几何规则本身：**每列都收左边界 + 末尾再收最后一列的右边界**，N 天 = N+1 条；抽成纯函数 `verticalGridLineXs(columns)` 以便直接钉住 |
| 验证 | `SessionGridLineTest` 新增 2 项：7 天必须得到 `[0,100,…,700]` 共 8 条且含 x=600（周六\|周日）；空列表不画线 |

### 14-5 本轮验证

`gradlew test` **142 项全绿**（新增/改写：`SuesImporterTimeTableTest` 5 项、
`SessionGridLineTest` 2 项）；`gradlew assembleDebug` + `lintDebug` 通过；
新包已 `install -r` 到真机（**视觉核对待手机解锁**：真机锁屏，无法在不打扰用户的前提下截图）。

---

## 15. 「自动」的投票口径修正 + 改为读取时重算（2026-09-16 四续）

### 15-1 R6 投票口径错了：3~5 节之外的课不该有投票权（已修）

用户在真机上追问"为什么自适应没有生效"，读设备库（`databases/wakeup`，含 WAL）后定位到两点。
先看第一点 —— **投票口径**：

三套作息（A/B/C）**只在上午第 3~5 节不同**，第 1~2、6~15 节逐格相同。所以一门第 6~7 节的课，
无论它在 D 楼（C 方案）还是 B 楼（B 方案），时间都是 13:20~14:40，**对"时间栏该用哪套"没有任何
信息量**。旧实现却把**全部安排**拿去投票，等于让与上午那几节无关的课决定上午显示什么。

用户的原话：**「不在 345 节的课不作为依据！」**

拿设备上那张表的两个口径对比（同一份数据）：

| 口径 | A | B | C | 结论 |
| --- | --- | --- | --- | --- |
| 全部安排（旧） | 7 | **20** | 8 | 选 B |
| 只算跨到 3~5 节的（新） | **6** | 5 | 4 | **选 A** |

为什么差这么多：两门 A 楼长实验（新能源汽车技术实验、智能汽车控制实验，各在周一/周二/周四
上 1~10 节）贡献 6 张 A 票；而 B 的 20 条里有一大半（汽车电控技术 1~2 节、汽车检测诊断技术
6~7 节与 10~13 节、智能网联汽车仿真技术 6~7 节…）根本不经过 3~5 节。

- **规则收敛为一处**：`CourseTimes.votesOnAxis(startNode, step)`（是否跨到 3~5 节）+
  `CourseTimes.autoAxisGroup(votes)`（取最多的那套，并列优先 B、再按分组名排序保证确定性）。
  导入器与运行时都调它，不存在两份实现。
- 跨到 3~5 节的**长实验课有投票权**（1~10 节确实经过第 3、4、5 节），边界情形有单测钉住。

### 15-2 R7 「自动」只认导入时烘焙的值（已修）

第二点：`自动`原先读的是**导入那一刻**写进默认分组的那套作息，运行时不再计算。后果是
**已经导入过的课表切到"自动"永远显示导入当时的结论**，改了课也不会跟着变；这张表是旧版
（固定写 B）导入的，所以即使口径修对了，不重算也还是显示 B。

- 修为：`CourseTimes.ofPreferred(context, all, details)` —— 偏好为空（自动）时，
  **按这张课表现在实际的安排重算**；指定了 A/B/C 就用指定的那套（`defaultList` 取不到该分组
  时仍回退默认分组，不会因为缺行而空掉）。
- 四处调用点（主课表、周视图小部件、日视图小部件、上课提醒）都传入该表的安排：
  主课表走 `ScheduleViewModel.getDetailOfTable`（挂起查询），另外三处走新增的
  `CourseDao.getDetailOfTableSync`（本来就在后台线程上）。日视图**必须用整张表**而不是
  "今天这一天"的课去投票，否则时间栏会随星期几换。
- 代价：每次构建作息多一次很小的查询（一张表几十行）。

**用户可见影响（这张表的实际结果）**：装上新包后打开课表，时间栏会从 B 方案变成 **A 方案** ——
第 3 节 09:55~10:35、第 4~5 节 10:40~12:00；同时**网格的连堂形状跟着变**：A 方案下第 3 节
单独一格、第 4~5 节是一格，所以"3|4 之间有线、4|5 之间没有线"（B 方案下正好相反）。
两处都来自同一个 `defaultGroup`，不会各说各话。

### 15-3 本轮验证

`gradlew test` **149 项全绿**（新增 `AxisSchemeVoteTest` 6 项、`SuesImporterTimeTableTest` 2 项）；
`gradlew assembleDebug` + `lintDebug` 通过；新包已 `install -r`。
**真机视觉核对仍待解锁**：这次改的正是真机上要看的东西（时间栏 3~5 节的时间与连堂线）。

---

## 16. R8 长实验课要不要投票：从"经过 3~5 节"改成"画法会变才算"（2026-09-16 五续）

§15 的判据是"**跨到** 3~5 节就投票"，于是第 1~10 节的长实验课也投了票。用户追问
「重新规划长试验课程到底要不要作为依据」，回代码里把几何关系核一遍就清楚了：

**课块的纵向位置只由两行决定** —— `CourseTimes.blockBox` 取 `defaultTimeForNode(startNode)`
（首节所在行）与 `defaultTimeForNode(lastNode)`（末节所在行）做插值，块上的时间文字又取自
它**自己楼宇**的方案（`startOf`/`endOf`）。而三套作息只在第 3~5 节不同，于是：

| 安排 | 首/末节 | 画法是否随轴变化 | 结论 |
| --- | --- | --- | --- |
| 第 6~7 节 | 6、7 | 否（两行三套相同） | 不投票 |
| **第 1~10 节长实验** | 1、10 | **否**（上边锚第 1 行、下边锚第 10 行，三套逐像素相同） | **不投票** |
| 第 4~5 节 | 4、5 | 是 | 投票 |
| 第 3~9 节 | 3、9 | 是（上边按第 3 行算） | 投票 |
| 第 1~4 节 | 1、4 | 是（下边按第 4 行算） | 投票 |

也就是说：**只要"经过"就算票，等于让对轴没有偏好的课替上午那几节做主**。真实数据里这正是
当前学期被算歪的原因 —— 两门 A 楼 1~10 节长实验（各在周一/周二/周四）贡献 6 张 A 票，
把轴从 C 拉到了 A，而学生**每天**坐在 3~4 节里的是 E406、E210 那几门 C 楼课。

- 判据改为 `CourseTimes.axisAffectsPlacement(startNode, step)`：**首节或末节落在 3~5 节**
  才投票（名字也改了，原 `votesOnAxis` 表达的"跨到"正是被否掉的那个口径）。
- 三种口径在你当前这张表上的结果：

  | 口径 | A | B | C | 选谁 |
  | --- | --- | --- | --- | --- |
  | §15 之前：全部安排 | 7 | 20 | 8 | B |
  | §15：跨到 3~5 节 | 6 | 5 | 4 | A |
  | **§16（现）：首末落在 3~5 节** | 0 | 1 | **4** | **C** |

  有权投票的只有 5 条：`汽车服务企业管理` 周三/周六 3~4 节 @E406（C，2 票）、
  `汽车营销实务` 周四 3~4 节 @E210（C，1 票）、`工程项目管理与经济分析` 周三 3~4 节 @C310
  （C 楼按 B 方案，1 票）。剩下的 1~2、6~7、10~13 节与两门 1~10 节长实验全都不投票。
- 七个学期里的影响：只有 2024-2025-2（跨到→C 变为 首末→A）与当前学期（A→C）两处会变，
  其余五个学期两种口径结果相同。
- 验证：`AxisSchemeVoteTest` 拆成"首末落在 3~5 节才投票 / 两端都在外面不投票"两组，
  显式钉住 **1~10、1~9、6~11 节不投票**、**3~9、1~4、5~6、2~3 节投票**；
  `SuesImporterTimeTableTest` 增加"长实验课不投票"与"从 3~5 节起步/收尾要投票"两条。
  `gradlew test` **152 项全绿**，`assembleDebug` + `lintDebug` 通过，新包已 `install -r`。

**装上新包后的预期**：时间栏变为 **C 方案** —— 第 3~4 节 **10:15~11:35**、第 5 节 **11:40~12:20**；
连堂线随之为"3|4 之间无线、4|5 之间有线"（C 方案第 3~4 节同格）。这与每天上午 3~4 节
实际在 E406、E210 的课一致。

---

## 17. R9 放弃"投票"，改选**总偏移量最小**的作息（2026-09-16 六续）

用户接着问「3-9 1-4 有必要投吗」。这个问题本身说明**"投票"这个框架是错的**：它逼着人回答
"哪些课有资格投票"，而每一条线都是人为划的（6~7 节的课？1~10 节长实验？只从 3~5 节起步或
收尾的 1~4、3~9？），用户已经连着追问两次。

换成直接量化"这套轴在这张课表上有多别扭"：

```
偏移量(方案 S) = Σ_安排 [ |上边比例| + |下边比例 − 1| ]
上边比例 = 该课自己楼宇的首节时刻，落在「S 的第 startNode 行」区间里的位置
下边比例 = 同理，用末节时刻与 S 的第 lastNode 行
自动 = 偏移量最小的那套（并列优先 B，再按分组名排序）
```

轴与这门课同属一套作息时两个比例正好是 0 与 1，块严丝合缝；是另一套时，偏离多少就错位多少
（单位：行高）。于是：

- **对轴无所谓的课自然贡献 0** —— 第 1~10 节长实验的上边锚在第 1 行、下边锚在第 10 行，
  第 6~7 节、10~13 节的课同理，这两行三套作息逐格相同；它们连"要不要算一票"都不用回答。
- **有所谓的课自然贡献真实错位量** —— 1~4 节的课只错一条边（约 1/4 行），3~4 节的课上下各错
  一点，谁更该说话由"错多少"决定。

设备上这张表的实测（把三套作息与 35 条安排代进公式）：

| 候选 | 总偏移（行高） | 明细 |
| --- | --- | --- |
| A 方案 | 3.812 | 4 门 C 楼 3~4 节各偏 0.81 行 + C310(按 B) 0.56 行 |
| B 方案 | 2.000 | 4 门 C 楼 3~4 节各偏 0.50 行 |
| **C 方案** | **0.500** | 只有 C310(按 B) 偏 0.50 行 |

**自动 → C 方案**，与 §16 的结论一致，但理由不再依赖"哪些课算一票"：两门 1~10 节长实验与
全部 1~2、6~7、10~13 节的课在任何候选下偏移都是 0，明细里根本不出现。

- 规则仍只有一份实现：`CourseTimes.autoAxisGroup(candidates, rowOf, placements)`，
  导入器与运行时都调它（导入器传 `nodeTimes` 查表，运行时传库里的作息行）。
- 顺带把重复的"时刻 -> 区间比例"收敛为一处：`CourseTimes.fractionIn(time, from, to)`
  （原来实例侧另有一份 `fractionOf`）。
- 验证：`AutoAxisSchemeTest` 8 项（长实验不得把轴拉走、下午课不影响、1~4 与 3~9 有偏好、
  总偏移最小者胜、并列回退 B 且与顺序无关、手工课无偏好、无候选回退 B）；
  `SuesImporterTimeTableTest` 的四条轴用例在新规则下结论不变。
  `gradlew test` **154 项全绿**，`assembleDebug` + `lintDebug` 通过，新包已 `install -r`。

**用户可见结果与 §16 相同**：时间栏 C 方案（第 3~4 节 10:15~11:35、第 5 节 11:40~12:20），
连堂线为"3|4 之间无线、4|5 之间有线"。设置里的手动指定仍然照旧，不受自动结果影响。

---

## 18. lateinit table 在 onStart 恢复路径崩溃（2026-09-16）

- **位置**：`ScheduleViewModel.kt:37`（`lateinit var table`）← `ScheduleFragment.onCreateView:52`
- **现象**：真机（Android 16 / HyperOS）崩溃
  `UninitializedPropertyAccessException: lateinit property table has not been initialized`，
  栈在 `FragmentActivity.onStart → dispatchActivityCreated → ScheduleFragment.onCreateView`。
  设备 `/data/data/courseclock.timetable` 曾不存在（清数据/重装后恢复任务栈）。
- **根因**：`initView()` 在 `lifecycleScope.launch { whenStarted { … } }` 里异步读库后才
  `viewModel.table = table`；而 FragmentManager 在 `onStart` 就会恢复页并同步调
  `onCreateView`。`BaseActivity` 虽已 `remove("android:support:fragments")`，Android 16 /
  SavedStateRegistry 路径下仍可能带出 Fragment。
- **修复**：
  1. `ScheduleViewModel.isTableReady()` / `isTimeListReady()`（`::table.isInitialized`）；
  2. `ScheduleFragment` 未就绪时返回空 `FrameLayout`，`awaitingTable` 短路
     `onViewCreated`/`onResume`，等 `initViewPage` 重建；
  3. `BaseActivity` 额外剥掉
     `androidx.lifecycle.BundlableSavedStateRegistry.key`。
- **验证**：`gradlew test` 全绿；语义上任何"table 未赋值就进 onCreateView"的路径都不再读 lateinit。

---

## 19. 免听 / 重修适配（2026-09-16）

EAMS `get-data` 顶层还有两组此前未读的字段：

- `notAttendLessonIds`：免听 lesson id 列表（权威来源）；
- `lessonId2Retake`：id → 是否重修（只决定角标文案）。

当前学期样例：`[604344, 621522, 607510]`，三者 `lessonId2Retake` 均为 true，
且每门都与一门正常课重叠。

### 实现

| 层 | 改动 |
| --- | --- |
| 数据 | `CourseBaseBean`/`CourseBean` 增加 `notAttend`/`retake`；DB **v9→v10**（继续 destructive） |
| 导入 | `SuesEamsImporter` 读上述两字段；基表键变为 `(课名, notAttend)`，同名不同状态拆两条 |
| 提醒 | `CourseReminderScheduler` 在日循环与倒计时里过滤 `notAttend` |
| 渲染 | 免听课先画（底层）+ 整卡淡化 + `[重修]`/`[免听]` 前缀；正常课后画盖在上面；同槽位下层有免听时正常课右下角「免」角标（`TipTextView.TIP_NOT_ATTEND`） |
| 层级 | 同 startNode 叠块只在同类（正常×正常 / 免听×免听）之间互相隐藏；免听不得把正常课藏掉 |

### 验证

- 新增 `SuesNotAttendImportTest` 3 项（字段落地、同名拆基表、缺字段默认 false）。
- `gradlew test --rerun-tasks` **BUILD SUCCESSFUL**（含原有 154 项 + 新增 3 项）。

### 硬约束（用户裁定）

**正常课永远在上层；免听课绝不能盖过正常课。** 角标只在正常课右下角，用来说明底下还有免听块。

---

## 20. lint 存量 5 条：为什么不修（2026-09-17）

`lintDebug` 现在报 **5 条 error**（`build.gradle` 里 `abortOnError true`，所以 `./gradlew check`
会红）。这一节把"为什么留着不修"写下来 —— 不留记录的话，下一个人跑一遍 lint 会当成本轮引入的
问题重查一次。修法与代价都列在下面，谁想清掉都能照做。

### 20-1 本轮清掉的 4 条（每条都有 revert 检查）

| 位置 | 类型 | 处置 |
| --- | --- | --- |
| `settings/ChoicePopup.kt` | `NewApi`：`GradientDrawable.setPadding` 要 API 29 | 内边距改用 `InsetDrawable`（API 1）。此前 Android 5–9 上点「显示主题」/「作息时间表」弹列表即 `NoSuchMethodError` |
| `utils/BatteryOptimization.kt` | `NewApi`：`PowerManager.isIgnoringBatteryOptimizations` 要 API 23 | 加 `SDK_INT < 23 -> false` 守卫，并**删除** `lint-baseline.xml` 里那条。它此前一直把真崩溃瞒着：`SettingsList.batteryStateText()` 在每次构建设置页那一行时调它，Android 5.0/5.1 打开设置页就崩 |
| `AndroidManifest.xml` `SplashActivity` | `UnsafeImplicitIntentLaunch` ×2 | 补 `android:exported="true"`（顺带为 targetSdk 31+ 铺路：缺这行构建会硬失败），两条告警随之消失 |

两条 `NewApi` 的 API 等级证据来自 SDK 自带数据库 `platforms/android-33/data/api-versions.xml`
（`setPadding since=29`、`isIgnoringBatteryOptimizations since=23`），而 `minSdk 21`。

**版本判断必须直读 `Build.VERSION.SDK_INT`、且与那行调用同处一个方法 —— lint 的 `NewApi`
只认这种形态。** 实测把版本改成参数传进来（为单测开的口子），lint 立刻不再认这个守卫、
`NewApi` 重新报出来。所以 `BatteryOptimization` 里那行守卫既是运行时保护、也是 lint 的保护，
**不要为了测试或"看起来更函数式"改成别的写法**。

低于 API 23 的分支没有单测：本工程 Robolectric 4.16 只提供 API 23→36 的 android-all，
`@Config(sdk = [21])` 会直接报 `API level 21 is not available`（实测）。该底线由 lint 把关
（baseline 条目已删）；`BatteryOptimizationDozeGuardTest` 守的是另一半 —— 守卫不能把新机器
也一起挡掉（写宽成 `<=` 会被它抓到，实测）。

### 20-2 剩余 5 条：判定与理由

| 位置 | 类型 | 为什么不修 | 想清掉的代价 |
| --- | --- | --- | --- |
| `utils/CourseReminderNotifier.kt:108` | `MissingPermission`（`notify` 要 `POST_NOTIFICATIONS`） | 误报：紧邻上方就是 `areNotificationsEnabled()` 的早退 + 日志；本工程 `targetSdk 29`、清单里根本没声明这个 API 33 才引入的权限，权限被关时 `notify()` 的形态是静默丢弃（已被那段挡住）而不是抛异常。lint 看不到那个 guard | 单点 `@SuppressLint("MissingPermission")` + 注释，或改成 lint 能识别的 `ContextCompat.checkSelfPermission` 形态（是否被识别需实测） |
| `schedule_manage/ScheduleManageFragment.kt:230`、`:233` | `UseRequireInsteadOfGet`（`context!!`） | 存量且实际够不到：`BaseFragment.launch` = `lifecycleScope.launch { lifecycle.whenStarted(block) }`，Fragment 销毁时 scope 先取消，而 `context` 变 null 发生在 `onDetach`（`onDestroy` 之后），所以这里取不到 null。lint 建议的 `requireContext()` 只是把 NPE 换成 `IllegalStateException`，并不消除崩溃。它本来在 baseline 里，因行号漂移重新暴露 | 改成 `context?.let { … }`（真正的稳法）或 `requireContext()`（只为过 lint） |
| `res/layout/today_course_app_widget.xml:65`、`:76` | `UseAppTint`（`android:tint`） | **不能按 lint 改**：这份布局只有两条真实 inflate 路径，都不经 AppCompat —— `AppWidgetUtils` 走 `RemoteViews`（桌面进程用框架 `ImageView` 装），`TodayColorfulService` 走 `LayoutInflater.from(applicationContext)`。`app:tint` 在这两条路径上会被静默忽略，箭头会渲染成原色；`android:tint` 自 API 21 就是框架属性，这里才是正确写法 | 只能 `tools:ignore="UseAppTint"` + 注释写明 RemoteViews/非 AppCompat inflate 的原因 |

### 20-3 另有一条不稳定的 `[LintError]`（不是代码问题）

`splitties/resources/ColorResources.kt` 会被报一条 `[LintError]`：androidx.appcompat 的
`BaseMethodDeprecationDetector` 在模块分析期调 `context.getMainProject()`，这在 AGP 8.7.3 下
不被允许，于是 lint 把它自己报成 error（并声明 `UseCompatLoadingForColorStateLists` /
`UseCompatLoadingForDrawables` / `UseCompatTextViewDrawableApis` 三项可能误报或漏报）。

它**间歇性**出现：改动前见过一次，之后 3 次运行（2 次增量 + 1 次 `--rerun-tasks` 全量）都没复现，
取决于分析调度。没有代码层修法 —— 要么升级 appcompat/AGP，要么按 lint 的提示禁用上面那三项检查
（代价是这三项在本工程内永久失效）。**不要**因为它"自己会消失"就往 baseline 里塞。

### 20-4 顺带的结论：baseline 已经脱节，别一键抹平

当前报告里有 **132 条** baseline 条目不再匹配（`UnusedResources` 64、`HardcodedText` 35、
`UseRequireInsteadOfGet` 17 …）。baseline 生成于 `02468c0`，之后 `52b5763`（落地 12 屏设计）与
`6b64c5e` 两次大改造成行号漂移。含义是 **"lint 干净"这个判断目前不可信**。

但**不要**用 `./gradlew updateLintBaseline` 一键抹平：那会把上面这类真问题一起藏进基线 ——
`BatteryOptimization` 那条 API 23 崩溃就是这么被瞒了一整个版本的。

### 20-5 本轮验证

- 两条 `NewApi` 都用 revert 检查证明过：去掉守卫 → lint 报 `BatteryOptimization.kt:97 [NewApi]`
  （6 条）、监听 `context!!` 的用例失败；恢复守卫 → lint 回到 5 条、用例绿。
  `ChoicePopup` 的 API 28 用例在旧写法下报 `NoSuchMethodError`，恢复后绿。
- `gradlew testDebugUnitTest` 全量绿（含本轮新增用例）。
- `assembleDebug` + `install -r` 上机；「显示主题」/「作息时间表」两行在真机点开验证弹窗与内边距。

---

## 21. 健壮性巡检（2026-09-17）

四方向巡检：**输入脏 / 状态空 / 系统不配合 / 生命周期乱序**。手段是全仓静态扫（`!!` 179 处、
`lateinit` 45 处、catch、流与游标、线程与作用域、字符串解析、集合取值）→ 逐条判"能不能真的为
null/空、后果是崩溃还是静默失败" → 对最像真崩溃的写**临时探针实测**。巡检本身只读，没动生产代码；
21-1 是据此做的修复，21-2 是"查过确认没问题"的清单，21-3 是一条**被证伪**的发现（把过程留下，
免得以后有人凭同样的直觉再改一遍），21-4 是评估过但本轮未做的。

### 21-1 已修（4 条）

| # | 位置 | 问题 | 修在哪一层 | 证明方式 |
| --- | --- | --- | --- | --- |
| 1 | `WebViewLoginFragment` | 门户探测是**自续**定时回调（600ms × 最多 15 发 ≈ 9 秒窗口），而 `postDelayed` 投出的消息不会随视图销毁消失；离开导入页后那一发落在 `_binding!!` 上 → **NPE 崩溃** | 回调的生命周期归视图：句柄存字段 + `onDestroyView` 里 `removeCallbacks`；并在**每个**接触视图的异步入口（探测、JS 回调、`onProgressChanged`、`onReceivedError`、`onReceivedSslError`、降级/遮罩/提示）先确认 `_binding` | 探针实测两条前提：`视图销毁后回调仍然跑了 = true`、`此时调用 suesProbePage → NullPointerException`；回归用例 `ImportEntryRobustnessTest.视图销毁之后探测入口不再触碰视图` 在还原修复后 → **NPE 失败** |
| 2 | `LoginWebActivity` | 该 Activity 带 intent-filter（要接外部「打开 .wakeup_schedule」）**即导出**，extras 由调用方说了算；`getStringExtra("url")!!` → 缺 url 就崩 | 入口契约显式化：缺 url 提示一句并 `finish()`，不留空白页、不崩 | 回归用例 `缺url的导入intent不崩而是结束自己`（还原后 → **NPE 失败**）+ 对照用例（带 url 正常进页） |
| 4 | `ImportViewModel.importFromFile` | 文件类型只看文件名/显示名，**没有字节上限**：几百 MB 的同名文件会被整个读进内存，而"行数不足 5"是读完才判 | 按字节预算读，越过 `MAX_SCHEDULE_BYTES`（1 MB，相对真实导出夹具 9 KB 有百倍余量）就报"文件过大"；错误文案直接给用户看 | 回归用例 `超过上限的文件被拒且一个字节都不入库`（还原后 → 断言到的是"文件格式不对"而**失败**）+ 合法夹具照常导入 |
| 6 | `ScheduleAppWidgetService` / `ScheduleFragment` | `getViewById(...) as FrameLayout` 取**动态生成**的节点行；同文件另一处用的是 `?: continue`。两边 `nodes` 一旦不一致就是 TypeCastException | 统一成 `as? FrameLayout ?: continue`（当前不可达，属硬化） | 无新增用例（行为仅在异常路径变化）；现成小部件渲染用例继续覆盖成功路径 |

### 21-2 查过、确认当前没问题（别再重复怀疑）

- `SuesEamsImporter` 的 `match.groups[1..3]!!` 与 `value[0]`：`P_ENTRY` 用 `matchEntire`，组 1/2/3
  都是**必填且非空**的（`\d+`、字符类单字符、`WEEK_PART` 至少一个数字），组 4 才可选且用了 `?.`。
- `TimeSettingsViewModel` 的 `rows.minByOrNull { }!!`：值来自 `groupBy`，按定义非空。
- `TipTextView.mStaticLayout!!`：上面 `if (mStaticLayout == null)` 的两个分支都会赋值。
- `AppDataBase.INSTANCE!!`：`@Volatile` + 双检锁，`!!` 之前必然已赋值。
- 两个小部件服务的 `lateinit table`：都有 `this::table.isInitialized` 守卫（没课表时出空图）。
- `CourseReminderReceiver`：`when(action)` 对 null action 自然落空；提醒广播**再查一次总闸**处理
  "取消与派发之间"的竞态；重活走 `goAsync` + 协程；刷小部件包了 try/catch。
- WebView 的 SSL 错误：弹框让用户决定，没有静默 `proceed()`。
- `splitties` 里那些 `Fragment.context!!` 与 lint 报的 `context!!` 一族：都包在
  `lifecycleScope.launch { lifecycle.whenStarted { … } }` 里 —— 销毁时协程先取消，而 `context`
  变 null 发生在 `onDetach`（在 `onDestroy` 之后）→ 取不到 null（与 §20 同一结论）。
- 主线程 I/O：`allowMainThreadQueries` 确实开着，但所有 `…Sync()` 只出现在 RemoteViewsService、
  提醒链路、启动重排三处，各自有 `goAsync`/IO 上下文；适配器与点击路径没有同步查询。

### 21-3 被证伪的一条：导入读文件**并没有**泄漏 fd

巡检时判定 `openInputStream(uri)?.bufferedReader()?.readLines()`（全仓唯一没写 `use` 的流读取）
"每导入一次泄漏一个文件描述符"。写回归用例时用**还原检查**发现它一直是绿的：探针实测

```
PROBE bufferedReader().readLines()  closed=true lines=[a, b, c]
PROBE bufferedReader().use{...}     closed=true lines=[a, b, c]
```

**Kotlin 的 `Reader.readLines()` 自带关闭**（内部走 `forEachLine` → `useLines` → `use`），所以
原代码没有泄漏，这条发现作废。教训记在这里：**"看起来少了 `use`"不等于少了关闭**，标准库的
关闭语义要实测；而还原检查正是拦住这类误判的那道闸。

修复 #4 时那行仍然要写 `use` —— 因为改成按字节预算的手写循环之后，`readLines()` 不再参与，
关闭就得自己负责；`ImportFileRobustnessTest.合法文件照常导入且读完就关流` 钉的就是这个新契约
（它不能区分新旧实现，作用只是防止以后改回手写循环时忘了 `use`）。

### 21-4 未做：#5 导入原子性

`importFromFile` / `importFromSues` 末尾都是同样的 4 步写入（时间表 → 作息 → 建表并接管默认表 →
课程与安排），而 `@Transaction` 只加在单个 DAO 方法上，**这 4 步没有外层事务**：中途失败会留下
半截状态（最坏是"新表已设为默认、课程没写进去"，用户看到一张空课表）。

已评估的代价：代码很小（`room-ktx` **已在依赖里**，`withTransaction` 可直接用；两处序列完全重复，
顺手可去重），**贵的是验证** —— 现有导入测试是直接解析夹具、根本没碰 `ImportViewModel` 与数据库，
想证明"中途失败会回滚"得新搭数据库级测试并构造一次失败（`insertBaseList` 是
`OnConflictStrategy.REPLACE`，造重复主键不会抛；可行路线是构造 `startDate: null` 的文件，撞第 3 步的
`NOT NULL`）。本轮按用户决定跳过，留待单独一轮。

### 21-5 本轮验证

- 新增用例 5 条（`ImportEntryRobustnessTest` 3 + `ImportFileRobustnessTest` 2），全量
  `testDebugUnitTest` **43 类 / 228 项**全绿（含 22-3 补的那批）。
- 四条修复各自做了**还原检查**：还原后 3 条用例分别以 NPE、NPE、断言失败（错误文案不对）报红，
  恢复后全绿；并因此发现 21-3 那条假发现。
- 设备验收：`install -r` 后**功能验证**五个组件的导出——shell（uid 2000）用
  `am start -n …/LoginWebActivity --es import_type sues`（故意不给 url）能启动、随即自行
  `finish()`、无 FATAL；对照组 `SettingsActivity`（未导出）被系统拒绝。注意本机
  `dumpsys package` **不打印** `exported=`，所以判据是"外部 uid 能否唤起"，不是读 flag。
- 外部发**自有 action**（`WAKEUP_NEXT_DAY` / `WAKEUP_SHOW_TODAY`）实测
  `Broadcast completed: result=0` 且无崩溃；缺 extras 启动小部件配置页会自行结束。

---

## 22. 不看屏幕怎么验（非目视检查法）

一次"目视之外"的完整遍历，按性价比排序。每条都记了命令或做法，以及这次的实测结果。

### 22-1 设备自己的账本（最值钱，先看这个）

| 查什么 | 命令 | 这次的结果 |
| --- | --- | --- |
| 崩溃历史（前台/后台、时间、完整栈） | `adb shell dumpsys dropbox --print` | **2 条真崩溃**，都已定位到 21-1 的根因 |
| 当前崩溃缓冲 | `adb shell logcat -d -b crash` | 空 |
| ANR | `adb shell dumpsys activity lastanr` | `<no ANR has occurred since boot>` |
| 保活与待机自洽 | `dumpsys deviceidle` / `dumpsys alarm` 里的 `ssru` | 白名单在、`ssru` 为负，与 `mWakefulness=Dozing` 自洽 |

判"这条崩溃是否已经修过"的办法：拿 `dumpsys dropbox` 里的时间戳与 `git log -1 --format=%ci <提交>`
比对先后（21-1 那两条就是这么定性的：修复是 09-17 06:38，崩溃是 09-16 22:56）。

### 22-2 受保护广播的坑（这次踩过，记下来）

`am broadcast` 发受保护广播（`APPWIDGET_UPDATE`、`TIME_SET`、`BOOT_COMPLETED` 等）会被系统拒绝：

```
java.lang.SecurityException: Permission Denial: not allowed to send broadcast
    android.appwidget.action.APPWIDGET_UPDATE from unknown caller.
```

**它先打印 `Broadcasting: Intent {...}` 再抛异常** —— 只截前两行会误判成"发出去了但没生效"。
判据是 `Broadcast completed: result=`。要测这些 action 只有两条路：Robolectric 里
`context.sendBroadcast(...)`（没有权限模型），或者改成发**自己定义的** action（不受保护、
第三方也能发，恰好就是导出后的真实对外面）。

### 22-3 已经补上的自动化检查

| 测试类 | 钉住什么 | 牙齿（还原检查） |
| --- | --- | --- |
| `SettingsBindingStabilityTest` | 绑定阶段不增删子视图（直接监听层级变更事件，不用 `childCount`）；箭头实例不变 | 把 `convert` 还原成"换箭头就 `removeViewAt` + `addView`"→ 用例报 **`NullPointerException: Cannot invoke "android.view.View.unFocus(android.view.View)" because "view" is null`**，与真机那条一模一样 |
| `WidgetProviderHostileInputTest` | 假 `appWidgetId`、自有 action、空库刷新、绑定实例后的真实刷新、缺 extras 的配置页 | 后台协程的未捕获异常被单独接住判定（后台异常不会让用例失败，真机上却会杀进程） |
| `ImportMalformedInputTest` | 12 种畸形分享文件只允许"成功"或"抛带文案的异常"（不许 `Error`、不许空 `message`）；恰好 1 MB 不被误判超限 | 上限分支见 21-3 的还原检查 |
| `ImportEntryRobustnessTest` / `ImportFileRobustnessTest` | 见 21-5 | 见 21-5 |

### 22-4 还没做（评估过，随时可做）

- **release 包真机验收**：R8 的 keep 规则已在 dex 层面验过（JS 桥方法名与 Gson 字段名全部存活，
  见 22-5），但设备上装的始终是 debug 包（`dumpsys package` 带 `DEBUGGABLE`）。
- **时区/时间旅行**：收到 `TIMEZONE_CHANGED` / `TIME_SET` 后的重排只上机看过 `ssru`，
  没有把"改时区后闹钟仍对齐"写成用例。
- **覆盖率**（未接 jacoco）：哪些分支从没被走到目前只能人工记（例如 21-4 的回滚路径）。
- **CI**：`.github` 不存在，`./gradlew check` 当前是红的（5 条 lint 存量）→ 这道门事实上失效。

### 22-5 构建产物层（release 才暴露的问题）

`minifyEnabled true + shrinkResources true`，所以反射面与 JS 桥只能从 dex 里查：

```powershell
Expand-Archive app-release.apk -DestinationPath <tmp>       # 只取 classes*.dex
Select-String -Path <tmp>\*.dex -Pattern 'showSuesData' -List
```

这次结果：release dex 1 个 / 5.77 MB（debug 17 个 / ~13 MB，APK 7,999,903 → 3,794,322 B），
`showSuesData`、`suesFailed`、`suesDebug`、`local_obj`、`courseName`、`startNode`、`weekList`、
`notAttend`、`timeGroup` **全部存活** —— keep 规则有效。这类名字若被混淆，界面看起来只是
"点了没反应"，永远等不到一条崩溃日志。

---

## 23. 全量测试补齐（2026-09-17 晚）

### 23-1 release 包真机验收（此前从没跑过发布形态）

- 产物：`minifyEnabled + shrinkResources` → 单 dex 5.77 MB，APK 3,794,322 B（debug 7,999,903 B）；
  R8 报告里 **没有 `missing_rules.txt`**（没有会被裁掉的引用），`mapping.txt` 20 MB 说明确实混淆了。
- 装机后：`DEBUGGABLE` 标记消失；启动落在地课表页 `ScheduleActivity`（说明读库、建适配器在 R8 后正常）；
  本包排了 **27 枚闹钟**（含 `WAKEUP_REMIND_COURSE`，说明提醒链在 R8 后正常）；外部发自有 action
  得到 `Broadcast completed: result=0`；崩溃缓冲无 FATAL。
- **设备级反证**：设备上那只是 10:42 构建的 release 包，**早于本轮全部修复**；用它打
  「缺 url 的 `LoginWebActivity`」直接崩：

  ```
  java.lang.RuntimeException: Unable to start activity ComponentInfo{...LoginWebActivity}:
  Caused by: java.lang.NullPointerException
      at courseclock.timetable.schedule_import.LoginWebActivity.onCreate(SourceFile:97)
  ```

  同一个 probe 在修复后的 debug 包上不崩、自行 `finish()`。这条崩溃同时是 21-1 #2 修复必要性的硬证据。
- **未完成**：没能重验"修复后的 release 包"。`assembleRelease` 按设计需要发布密钥口令
  （口令不落盘、只从环境变量或输入框来），本机既无 `keystore.properties` 也无
  `WAKEUP_KEY_PASSWORD`，非交互环境下它按设计抛错退出。要补这一步，需要用户提供口令或自行构建。

### 23-2 数据库升级路径（新增 2 个测试类）

`version = 10` + `fallbackToDestructiveMigration()`，没有 `addMigrations`。用"造一份旧版本库文件
（`user_version` 设成旧值 + 里面有数据），再从**产品自己的入口**打开"的方式钉住行为：

- **升级（v9→v10）不崩，旧数据被清空**；
- **降级（v11→v10）同样被重建**。

两个方向各占一个测试类：`AppDatabase` 是单例（静态 `INSTANCE`），同类里第二次打开不会重新判定版本，
自己再 `new` 一个 Room 实例又是假绿（验的是测试里写的配置，不是产品里的）。
用户可见后果：版本一变课表就空，只能重新导入 `.wakeup_schedule`；这是既定选择（注释写明），
但值得在下次改 schema 时提醒一次。

### 23-3 时区与夏令时（新增 1 类 4 条）

`CourseReminderScheduler.nextTriggerMillisAt` 的注释声明"必须用 `Calendar.add(DAY_OF_YEAR, 1)`，
毫秒相加会在夏令时切换日偏一小时"。用例把那句声明变成证据（期望值由 `java.time` 独立算出）：

| 场景 | 断言 |
| --- | --- |
| 前跳日 2025-03-09（美东 02:00→03:00） | 目标仍是当地 00:05，间隔 **22h05m** |
| 后拨日 2025-11-02（美东 02:00→01:00） | 目标仍是当地 00:05，间隔 **23h35m** |
| 换时区（上海→纽约） | 各自落在当地 00:05，且两个绝对时刻不同 |
| 星期几/第几周 | 跟随设备时区：同一时刻在上海是周四、在纽约是周三 |

**还原检查**：把实现换成毫秒相加 → 两条 DST 用例以
`expected:<00:05> but was:<01:05>` / `but was:<23:05>` 报红，恢复后全绿。

### 23-4 设备状态变更（用户决定，含一次数据清空）

为验收 release，把 10:42 的发布包装到了设备上。该包签名是 `CN=BH4GMI`，debug 包是
`CN=Android Debug`，**两者不同而 `install -r` 仍然成功**（本 ROM 行为，未按常规拒绝）；
当时确认数据还在（课程提醒闹钟尚存）。用户选择"先卸载再装 debug"以回到干净状态：
`uninstall` 成功，随后 `adb install` 被 MIUI 策略挡下
（`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`），APK 已推到
`/sdcard/Download/Wakeup-debug.apk` 供手动安装。

**后果**：App 数据按用户选择被清空（课表、SUES 登录态、小部件绑定），且设备上目前**没有安装**该 App，
需要用户放开一次「USB 安装」或手动点装。教训：**换签名的验收不要在用户的日常设备上做** ——
要么用一台测试机，要么先确认能否原地升级。

### 23-5 覆盖率的决定

用户选择**不引入** jacoco 与 CI（本仓库 `.github` 不存在，`./gradlew check` 因 5 条 lint 存量是红的）。
因此"哪些分支从没被走到"仍只能人工记档。

### 23-6 仍未覆盖（诚实清单）

- **API 21/22 的运行时**：Robolectric 4.16 只支持 23+（`@Config(sdk = [21])` 直接报
  `API level 21 is not available`），而本工程 `minSdk 21`；那道 API 23 守卫目前只有 lint 的
  `NewApi` 在保。可选替代：真机旧机型，或自建 API 21 AVD 跑 instrumented 测试
  （当前仓库没有 `androidTest` 源集）。
- **其余测试类的还原检查**：本轮到目前完成了导入（21-5）、设置绑定（22-3）、时区（23-3）三组；
  还剩约 35 个类（课表几何、渲染、Sues 导入、更新/迁移、交互等）没有逐个做过 revert-check。
- **进程被杀 / 开机 / 低内存等真机状态**：只验过 `MY_PACKAGE_REPLACED` 与开机后的重排，
  `am kill`、`am send-trim-memory`、`onDeleted` 周期没有成用例。

---

## 24. 假绿清查：变异式还原检查（2026-09-17 夜）

方法：不逐个读 43 个测试类，而是**按被保护的生产文件成组把实现/设计 token 改坏** → 跑全量 →
按类归因失败 → 恢复 → 复跑。没变红的类，要么确实与这条改动无关（正常），要么就是**自指断言**
（测试与实现读同一个值，两边一起变，所以永远绿）。只有后者要记。

### 24-1 发现：设计 token 的**值**没有任何测试保护

一批改 5 个被渲染测试引用的 token：

| token | 原值 | 改成 |
| --- | --- | --- |
| `page_inset` | 16dp | 24dp |
| `setting_row_min_height` | 56dp | 40dp |
| `setting_row_two_line_height` | 64dp | 48dp |
| `widget_corner_radius` | 18dp | 2dp |
| `course_detail_inset` | 24dp | 8dp |

结果：**全量 234 项全绿**。不是资源没重新合并（同一批里后来改 `switch_thumb` 就红了），
而是那些断言读的是**同一个 token**：

```kotlin
val inset = px(R.dimen.page_inset)          // 测试读 token
assertEquals(..., inset, row.marginStart)   // 断言布局 == token
```

它证明"布局用了 token"（接线对），证明不了 token 的值对不对 —— 设计走样可以完全无声地发生。

**处置**：新增 `DesignTokenTest`（4 条）钉住设计稿数值与跨 token 的顺序不变量。
它自己的还原检查：把 `page_inset` 改成 24dp → 报
`卡片离屏幕左右的距离 expected:<16.0> but was:<24.0>`，恢复后绿。

### 24-2 批量变异里"有牙齿"的例子（说明这套方法不是只会报坏消息）

- `switch_thumb` 24→40（圆点比胶囊还高）→ `SettingsRenderTest` 报
  `圆点直径不是 24dp：量到 101px，token 是 120px`：**量到的几何 vs token** 这条断言有牙齿。
- 两行行高降到低于一行行高 → `SettingsRenderTest` 同样会红（顺序不变量有保护）。

### 24-3 发现：对比度仍然无测试保护

把正文色改成纯白（白底白字）、次要文字改成 `#FEFEFE`、强调色改成白 → 只有
`SettingsRenderTest` 的「开关一块里一个像素都没画出来」报红（它量的是"画没画出来"），
**没有任何对比度断言报红**。也就是说对比度目前只靠人工脚本（`_crop/contrast_audit.py`）看过，
线上改动没有任何自动保护。而这轮之前已经量到两处不足（小部件蓝卡白字 3.63:1、
品牌色当正文 3.63:1，见 §22 之前的口头结论）。

**待办（需要一次设计决定）**：要么先把这两处配色修到达标、再加"≥4.5:1（小字）/≥3:1（大字）"
的断言；要么先只给已达标的那几对加断言、把不足的两对写进待办。直接写一条现在就会红的断言
只会让套件长期变红，不可取。

### 24-4 进度

本晚已完成三批（设计 token、开关几何、颜色）。**剩余**：课表几何（`ScheduleUI`）、
时间与周次（`CourseTimes`/`CourseUtils`）、Sues 导入规则、小部件规则（`DayWidgetSchedule`/
`TodayColorfulService`）、更新与迁移（`UpdateUtils`）、iCal 导出、周选择器、列表/对话框基类、
设置交互 —— 继续按同一方法推进。

### 24-5 第二批：课表几何常量（`ScheduleUI`）——绝对尺寸基本无保护

一次改 5 个常量：`ALPHA_UNDER_COURSE_LIGHT` 0.12→0.5、`WEEK_AXIS_LABEL_SP` 11→9、
`WEEK_AXIS_GAP_DP` 4→8、`AXIS_LABEL_ALPHA` 0.32→0.9、`TODAY_SLIDER_WIDTH_DP` 24→10
→ **239 项里只有 1 条红**：`ScheduleWeekAxisTest > 今天那一列的滑块落在该列上`
（`滑块宽度应该是 24dp expected:<72> but was:<30>`，是硬编码 24dp 的绝对期望 ✓）。

结论：课表几何那一批（`BlockRowAlignmentTest` / `SessionGridLineTest` / `TimeColumnLabelTest` /
`TimetableRenderProbeTest` / `ScheduleUIMeasureTest` / `ScheduleTopBarTest`）保护的是**相对不变量**
（课块与格子对齐、连堂内部不画线、刻度累加、标签不倒退），绝对尺寸几乎只有"今天滑块宽度"这一条。
这是取舍而不是缺陷：相对不变量才是那些历史缺陷的形态；但"字号/间距/透明度被改错"目前无声。

### 24-6 第三批：时间语义与小部件规则——牙齿很好

- `CourseTimes.hasGridLineAfter` 恒返回 true（连堂内部也画线）+ `defaultTimeForNode` 下标 +1
  → **4 类 12 条红**：`BlockRowAlignmentTest`、`SessionGridLineTest`（含用户报过的
  "第 1、2 节之间不该有线"）、`SuesConflictTableTest`、`TimeColumnLabelTest`。
- `DayWidgetSchedule.displayOrder` 把"已结束"从 2 改成 1 + `remainingToday` 去掉过滤
  → `DayWidgetPriorityTest` 3/3、`DayWidgetRenderProbeTest` 3/3、`DayWidgetCountdownTest` 1/1 全红。
- `UpdateUtils` 把 `has_adjust` 标记改成 false → `seeding marks the adjust flag` 红。
  但把「先查后插」的 `if (tableDao.getTableById(1) == null)` 改成 `if (true)` **没有红**：
  主键冲突被 catch 兜住，"结果不重复"依然成立 —— 该用例保护的是**结果**而不是**机制**。
  不是假绿（结果确实成立），但设计意图"先查后插、不靠捕获主键冲突蒙混"没有测试钉住。

### 24-7 发现：Sues「星期X」的字→数字映射无人钉住（真漏洞，已补）

把 `WEEKDAY_TEXT` 里的 `'三' to 3` 改成 `'三' to 5`（**周三的课整批排到周五**）→
**239 项全绿**。夹具里明明写着「星期三」（6 处），但断言的是作息表与教室分组的推导，
没有一条断言"星期三的课落在第 3 天"。

**处置**：在 `SuesImporterTimeTableTest` 加 `every weekday word maps to its own day number`
（八个字 一~六/日/天 各一条课，断言 day 排序后的多重集是 1..7,7）。
它的还原检查：把 `三` 改回 5 → 报
`expected:<[1, 2, 3, 4, 5, 6, 7, 7]> but was:<[1, 2, 4, 5, 5, 6, 7, 7]>` ✓

### 24-8 至此的账

| 批次 | 覆盖的生产单位 | 结果 |
| --- | --- | --- |
| 24-1/24-2 | `dimens.xml` / `colors.xml` | 发现"token 值无保护"→ 已补 `DesignTokenTest`；对比度仍无保护（待设计决定） |
| 24-5 | `ScheduleUI` 常量 | 绝对尺寸基本无保护（仅滑块宽度） |
| 24-6 | `CourseTimes` / `DayWidgetSchedule` / `UpdateUtils` | 牙齿良好（4 类 + 3 类 + 1 类红） |
| 24-7 | `SuesEamsImporter.WEEKDAY_TEXT` | **发现漏洞**→ 已补用例 |

剩余：`SettingsList`/`SettingsActivity` 交互、`WeekPickerFragment`、`BaseListActivity`/
`BaseDialogFragment`、`ICalUtils`、`ViewUtils.parseCourseColor`、`ViewUtils.scheduleTextColor`、
`CourseUtils`（`calAfterTime`/`countWeek`）、`CourseDetailText`/`CourseDetailFragment`、
`AutoAxisScheme`，以及四个"无生产 import"的类（`DayWidgetHeaderPreviewTest`、
`WidgetAdapterViewClickTest`、`SuesDocLedgerTest`、`ExampleUnitTest`）—— 后者只能靠改布局/资源。

### 24-9 第四批：纯函数/工具层，外加又一个自指假绿

| 位置 | 怎么改坏的 | 报红 |
| --- | --- | --- |
| `ViewUtils.parseCourseColor` | 6 位色不再补不透明 alpha | `CourseColorParseTest` 1/4 |
| `CourseUtils.countWeek` | `during / 7 + 1` → `during / 7` | `CourseUtilsTimeMathTest` 6/9、`CourseReminderSchedulerTest` 7/55、`TimeZoneAndDstTest` 1/4 |
| `CourseUtils.calAfterTime` | 分钟数 +1 | 同上若干条 |
| `ViewUtils.scheduleTextColor` | 自定义背景分支改成硬编码黑 | **0 条红 ← 假绿** |

**发现：`ScheduleTextColorTest` 的浅色分支是自指断言**

```kotlin
assertEquals(0xff000000.toInt(), scheduleTextColor(context, tableWithBackground()))
```

`0xff000000` 正好是 `TableBean.textColor` 的**默认值**，于是"有背景图就永远返回黑"
（也就是这条规则本来要防的那个 bug 的变体）也能让它保持绿色：它分不清"读了用户的字段"
与"没读"。

**处置**：给 `tableWithBackground` 加 `textColor` 参数（默认值不变，既有断言照旧），
新增 `浅色模式下用的是用户配的那个文字色而不是默认黑`（用 `0xFF3366FF`）。
它的还原检查：`expected:<-13408513> but was:<-16777216>`（即 `0xFF3366FF` vs 黑）✓

至此：47 类 / 240 项。

### 24-10 第五批：小部件安全区、iCal 星期、开关状态、自动作息方案

| 位置 | 怎么改坏的 | 报红 |
| --- | --- | --- |
| `widget_edge_padding` 16→4dp | 小部件内容安全区 | `DayWidgetHeaderPreviewTest` 1/2、`DayWidgetRenderProbeTest` 2/3、`DesignTokenTest` 1/4 |
| `ICalUtils.weekDayConvert` `3 -> WEDNESDAY` → `FRIDAY` | 周三的课导出到周五 | **0 条红 ← 漏洞** |
| `SwitchItemProvider` `isChecked = item.checked` → `!item.checked` | 开关显示状态反过来 | `ScheduleSettingsInteractionTest` 1/1、`SettingsRenderTest` 1/11 |
| `CourseTimes.autoAxisGroup` `minByOrNull` → `maxByOrNull` | 自动作息改选"偏移**最大**"的那套 | `AutoAxisSchemeTest` 7/9、`SuesImporterTimeTableTest` 5/12 |

后面两组说明这两块**牙齿很好**（尤其 `ScheduleSettingsInteractionTest` 正是"开关不更新"那个
已修 bug 的守门用例）；`DesignTokenTest` 也顺带抓到了小部件安全区被改，算交叉验证。

**发现第二个"映射只测了一头"的漏洞**：`ICalExportTest` 原来只覆盖 `day = 7`（周日），
另一条 `day = 1` 又只验"没有作息数据不导出"，于是 `weekDayConvert` 中间五天无人钉。
**处置**：新增 `every weekday lands on its own date` —— 七天各排一节、逐天断言导出日期。
带变异跑当场报 `第 3 天必须落在 2026-09-1[6] but was:<...1[8]>`（周三被写成周五）。

至此：47 类 / 241 项。

### 24-11 第六批：免听 / 单双周 / 去重台账 / 源码扫描类

| 位置 | 怎么改坏的 | 报红 |
| --- | --- | --- |
| `parseNotAttendIds` 丢掉所有 id | 免听标记全部丢失 | `SuesNotAttendImportTest` 2/3 |
| `P_WEEK_MARK` 的 `[单双]` → `[单]` | 「(双)」标记不再被识别 | `SuesWeekParityImportTest` 5/5、`SuesImporterTimeTableTest` 1/12 |
| `SuesDocLedger.claim()` 恒返回 true | 同一份文档的重复回调都当新文档处理 | `SuesDocLedgerTest` 3/3 |
| 删掉 `setPendingIntentTemplate(R.id.lv_course, …)` | 小部件列表项点击失效 | `WidgetAdapterViewClickTest` 1/2 |

**方法教训（写下来，避免自欺）**：

1. **源码扫描类用例的变异必须真的删掉那段文本**。我第一次只是把那行**注释掉** → 字符串仍在文件里
   → 用例照样绿，差点记成"假绿"。
2. **变异必须先确认编译通过，再解读结果**。第二次把 `R.id.lv_course` 改成不存在的 id →
   `Unresolved reference`，而 Gradle 仍报了 `tests=2 failures=0`（跑的是上一轮的旧字节码）——
   如果不看 `^e:` 行，就会得出"没红"的错误结论。
3. 因此每条"0 类红"的结论都要先排除"变异无效"与"没编译"这两种可能，只有排除了才算发现。

至此：47 类 / 241 项（本轮无新增用例——这批全部是有牙齿的，没发现假绿）。

### 24-12 第七批：详情文案 / 弹窗窗口 / 删默认表的默认标记转移

| 位置 | 怎么改坏的 | 报红 |
| --- | --- | --- |
| `CourseDetailText.nodeText` | 末节 +1（「第 1-2 节」→「第 1-3 节」） | **0 条红 ← 自指断言** |
| `BaseDialogFragment` 的 `setGravity` | `BOTTOM` → `CENTER` | `BaseDialogFragmentWindowTest` 1/1 |
| `TableDao.deleteTableAndFixDefault` | 删掉默认表后不再转移默认标记 | `TableDeletionGuardTest` 1/3（"默认标记应转移到剩余课表"） |

**发现第三个自指断言**：`CourseDetailRenderTest` 的摘要断言是
`assertEquals(CourseDetailText.whenText(course()), whenText)` —— 期望侧与渲染侧读**同一个函数**，
所以改格式两边一起变，永远绿。
**处置**：新增 `摘要文案的格式必须钉在字面量上`（`周一 第 1-2 节`、`第 1-16 周`、单周/双周缀词）。
带变异跑当场报 `expected:<周一 第 1-[2] 节> but was:<周一 第 1-[3] 节>` ✓

至此：47 类 / 242 项。

### 24-13 第八批（收尾）：顶栏圆钮、周选择器标题、列表接线、空卡片

| 位置 | 怎么改坏的 | 报红 |
| --- | --- | --- |
| `ScheduleActivityUI` 顶栏圆钮 `dip(36)` → `dip(30)` | 圆钮变小 | `ScheduleTopBarTest` 1/7 |
| `SettingsList.batteryStateText()` 的「未设置」文案 | 后台运行那一行的状态文本 | `SettingsSystemActionsTest` 2/4 |
| `week_picker_title` 字符串「周数」→「周次」 | 周选择器标题 | `WeekPickerRenderTest` 1/7 |
| `BaseListActivity` 的 `topToBottom = R.id.anko_layout` → `topToTop = PARENT_ID` | 列表被标题栏压住（**这就是曾经的真机 bug**） | `BaseListActivityLayoutTest` 1/1 |
| `emptyCardSizePx(w,h)` 改成返回写死的 `100 to 88dp` | 空卡片不再等于可视区 | `DayWidgetEmptyCardTest` 1/1 |

**两次"改错地方"也值得记**：
- 我第一次把 `ScheduleActivityUI` 里另一个底部面板的「周数」当成周选择器的标题 —— 它属于别的视图，
  `WeekPickerRenderTest` 当然不红。**判据要看测试实际渲染的是哪个 Fragment/布局**，不能只看字符串。
- 我第一次改的是**无参** `emptyCardSizePx()`（走的是"启动器没给 options"的兜底分支），
  而 `DayWidgetEmptyCardTest` 调的是**两参数**重载（纯计算版）。改前必须先确认测试走哪条路。
- `DayWidgetEmptyCardTest` 是这批里设计得最好的一个：它**独立 inflate 同一份布局再量**，
  所以能抓到"函数被写死"，抓不到"布局被改"（两边同变）—— 这个边界是合理的，不是假绿。
- `DayWidgetRemainingCoursesTest` 的 `t.remaining(...)` 是 `CourseTimes` 的方法，
  不是 `DayWidgetSchedule.remainingToday` —— 所以 24-6 那批改 `remainingToday` 时它没红是**正确的**。

### 24-14 假绿清查总账（② 收尾）

方法：**按被保护的生产单位成组做变异** → 跑全量 → 按类归因 → 恢复 → 复跑。
共 13 批、约 45 处变异，覆盖了全部 47 个测试类（`ExampleUnitTest` 是 `2+2` 占位，无生产代码可验）。

**挖出并已修的 5 个真问题**（每一个都补了测试，且都做了变异验证）：

| # | 问题 | 补的测试 | 变异验证时的报错 |
| --- | --- | --- | --- |
| 1 | 设计 token 的**值**无任何保护（5 个 token 全改错，234 项全绿） | `DesignTokenTest` | `expected:<16.0> but was:<24.0>` |
| 2 | Sues 「星期X」→数字 映射无保护（`三`→5 全绿） | `SuesImporterTimeTableTest.every weekday word maps to its own day number` | `expected:<[1,2,3,4,5,6,7,7]> but was:<[1,2,4,5,5,6,7,7]>` |
| 3 | iCal 星期映射只测了周日（`3`→周五 全绿） | `ICalExportTest.every weekday lands on its own date` | `第 3 天必须落在 2026-09-1[6] but was:<...1[8]>` |
| 4 | `scheduleTextColor` 自指断言（期望值＝默认黑，改成"永远黑"仍绿） | `ScheduleTextColorTest.浅色模式下用的是用户配的那个文字色而不是默认黑` | `expected:<-13408513> but was:<-16777216>` |
| 5 | `CourseDetailText` 自指断言（期望侧读同一个函数，格式改坏仍绿） | `CourseDetailRenderTest.摘要文案的格式必须钉在字面量上` | `expected:<周一 第 1-[2] 节> but was:<周一 第 1-[3] 节>` |

**确认牙齿良好的**：`CourseTimes`（4 类红）、`DayWidgetSchedule`/`TodayColorfulService`（3 类 + 空卡片）、
`UpdateUtils`、`SuesEamsImporter`（免听/单双周/作息方案）、`AutoAxisScheme`、`SwitchItemProvider`
（"开关不更新"那个已修 bug）、`SuesDocLedger`、`TableDao` 的默认标记转移、`BaseDialogFragment`、
`BaseListActivity`、`WeekPicker`、`ScheduleTopBar`、`WidgetAdapterViewClickTest`（源码扫描）、
`ImportViewModel`、`WebViewLoginFragment`、`CourseUtils`、`ICalUtils`、`ViewUtils`。

**仍无自动保护、且需要一次设计决定**：颜色对比度（正文/次要文字/小部件文字对各底色）。
把正文改成纯白之后 242 项全绿；要补断言得先决定是"先修那两处不达标的配色"还是"只钉已达标的那几对"。

**必须记住的三条方法教训**（否则这套清查会自欺）：
1. 源码扫描类的用例，变异必须**真的删掉那段文本**（注释掉不算）。
2. 变异后**先确认编译通过**再看结果：编译失败时 Gradle 仍会跑上一次的旧字节码并报"0 失败"。
3. 改之前先确认**测试实际走的是哪条路径**（哪个 Fragment、哪个重载、哪个分支），
   否则"没红"可能只是没碰到。

## 25. release 包真机验收收尾：用**含修复**的代码重跑 R8 + 装机（2026-09-17 夜）

§23 那次验收用的是 10:42 那个**早于本次修复**的发布包（也正是它复现了 #2 崩溃）。
R8 会改名、会删类、会内联分支，"源码里修好了"并不等于"发布包里修好了"，修完必须用同一条路径再验一次。

### 25-1 拿不到发布口令时，怎么出一个可装机的 release 包

`android/keystore.properties` 指向真实发布密钥库（`<发布密钥库路径，只存在于本机 android/keystore.properties>`），
口令只在环境变量里。**没有口令就不该出发布包，更不该去要口令**。而验收要的是"R8 处理过的包"，
不是"发布者那张证书"，所以走的是调试库：

- 把 `keystore.properties` **临时**改指 `~/.android/debug.keystore`（别名 `androiddebugkey`、口令 `android`），
  构建结束**立刻按原字节还原**（前后 SHA-256 都是 `5F3FFCD6…`，已核对）。
- 调试证书与设备上已装的 debug 包**同一张证书**，所以 `adb install -r` 是**原地升级**：
  `firstInstallTime` 保持 19:52:30 不变，`WAKEUP` 库与已注册闹钟都还在（顺带拿到一份"升级不丢数据"的真机证据）。

试过但走不通的两条路（记下来免得再试）：
- init script 在 `projectsEvaluated` 里改 `storeFile` → `It is too late to set storeFilePath`；
- 挂在 `signingConfigs.whenObjectAdded` 上改 → 会被紧随其后的 `storeFile file(...)` 覆盖回去。

⚠️ **`packageRelease` 失败时会先删掉旧的 `app-release.apk`，再报错退出。** 这次就踩到了：
用户 10:42 那个真实签名的包被删掉，靠 `_crop/app-release.signed-10h42.apk.bak`（SHA-256 `1AB56C31…`）
找回，验收结束后已还原回 `outputs`。**以后动发布构建前先备份产物。**

### 25-2 验收结果（R8 包，`CN=Android Debug` 签名，3,770,408 B）

| 项 | 结果 |
| --- | --- |
| `apksigner verify --print-certs` | `Verifies`；v1+v2 通过；签名者 = 调试证书（与已装 debug 包同证书） |
| `adb install -r` | `Success`（原地升级，数据未清） |
| `dumpsys package` | `DEBUGGABLE` 消失 → 确实跑的是 release 变体 |
| 冷启动 | `SplashActivity` → `ScheduleActivity` resumed；crash buffer 空 |
| dex / 资源 | 单个 `classes.dex` 6,058,396 B；877 个条目；**无 `missing_rules.txt`** |
| 反射敏感名 | `showSuesData`/`suesFailed`/`suesDebug`/`local_obj`/`courseName`/`startNode`/`weekList`/`notAttend`/`timeGroup` 全部保留 |
| 清单组件 | `ScheduleAppWidget`、`TodayCourseAppWidget` 两个 provider 在设备端已绑定；`CourseReminderReceiver`、`WidgetPinReceiver`、`LoginWebActivity`、`ScheduleAppWidgetService`、`TodayColorfulService` 类名均保留 |

### 25-3 导出组件的真机回归（uid 2000 从 shell 直接拉起，走的正是外部调用方那条路）

| 用例 | 期望 | 实测 |
| --- | --- | --- |
| `import_type=SUES` 但**不带 url**（#2 的原始崩溃） | 提示 + 结束自己 | `am start` 成功；crash buffer 空。§23 用同样的调用在修复前的包上是 `NPE at LoginWebActivity.onCreate` |
| 完全不带 extras | — | 不崩，但**停在空白页**（见 25-4） |
| `ACTION_VIEW` 打开非 `.wakeup_schedule` 文件 | 提示 + 回首页 | 正确回到 `ScheduleActivity`，无崩 |
| `ACTION_VIEW` 打开**损坏的** `.wakeup_schedule` | 提示 | 提示后**停在空白页**（见 25-4） |

### 25-4 本轮新发现（**未修**，等决定）：导入失败后停在一个空白页

`LoginWebActivity` 的 `ACTION_VIEW` 分支刻意不加 `FileImportFragment`（原注释理由：
"失败时 toast 叠在一个无意义的页面只会让人困惑"），但 `catch` 里**只有 toast、没有 `finish()`**，于是：

- 打开一个损坏的 `.wakeup_schedule`：报错 toast 消失之后，用户面对的是一个**空白页**；
  证据：`dumpsys activity activities` 里 `topResumedActivity` 仍是 `LoginWebActivity`；
- 同时 `logcat` 里有 `E/MediaProvider: SecurityException: courseclock.timetable has no access to
  content://media/external_primary/file/…`（targetSdk 29 的包在 Android 16 上按 `file://` 读取被拒）——
  异常被 `catch` 收成一句 toast，页面留了下来；
- 完全不带 extras 时（`else -> null`）同样停在空白页。

这与同一文件里"不留空白页"的既定契约（即 §24 总账表 #2 那一行的修复理由）矛盾，属同族遗漏。
**最小修法（待确认，因为这是用户可见的流程变化）**：`catch` 里补 `finish()`；
另加一条 `fragment == null && action != ACTION_VIEW` 时提示并 `finish()`。
⚠️ 不能简单在 `onCreate` 末尾无条件 `finish()`：`ACTION_VIEW` 那条路是 `launch{}` **异步**导入的。

### 25-5 本轮收尾门禁

- `:app:testDebugUnitTest`：**47 类 / 242 项 / 0 失败 / 0 错误**（结果 XML 汇总）。
- `:app:lintDebug`：报 **5 个 Error，与修复前逐条一致**（`CourseReminderNotifier.kt:108` 的
  `MissingPermission`、`ScheduleManageFragment.kt:230/233` 的 `UseRequireInsteadOfGet`、
  `today_course_app_widget.xml:65/76` 的 `UseAppTint`），**没有新增**；因 `abortOnError` 而失败属既有状态。
- 全部验收动作结束后设备已还原成 debug 安装（`DEBUGGABLE` 回来了、`run-as` 可用、数据仍在），
  `outputs` 里的 `app-release.apk` 也还原成用户原件；临时文件（init script、keystore 备份、解包 dex）已全部删除。

## 26. 交互问题一轮（2026-09-17 晚，用户逐条反馈）

用户一次性给了六条体验问题。逐条记根因、修法与验证；**其中"应用内添加小部件"最终按用户
决定整体移除**（不是修好）。

### 26-1 上下箭头太扁（第二次反馈）

- 现象：设置页里「就地选择」那种行的右侧「⌃⌄」仍然是扁的。
- 根因：`ArrowView` 的 SELECT 几何是 `half = 0.19×边长`、`height = 0.14×边长`（真机 WIP 值），
  斜率只有 **36°**；两个尖虽然分开了，但折线本身是扁的 —— 上一轮只解决了"糊在一起"，
  没解决"扁"。
- 修法：按 **45°** 重画（`height == half`，顶角 90°），并把 `gap` 一起放大到 `0.30×边长`
  以保住中间那道缝；三个比例抽成 `ArrowView.SELECT_HALF/SELECT_HEIGHT/SELECT_GAP` 常量，
  线宽也抽成 `STROKE_WIDTH_DP`，因为"缝"的换算要减掉它。
- 验证：新增 `ArrowViewGeometryTest`（斜率 = 45°、可见缝 ≥ 2.5dp、墨迹不超出 20dp 的位）。
  变异检查：把 `SELECT_HEIGHT` 改回 `0.12f` → `select chevrons are drawn at 45 degrees, not flat` **报红**。

### 26-2 触感太少（线性马达）

- 现象：只有设置页的行点击有一下震动，其余主要交互全都没有。
- 修法：新增 `utils/Haptics.kt` 作为**全应用唯一入口**，分三档语义（`tap` = CONTEXT_CLICK、
  `tick` = CLOCK_TICK、`longPress` = LONG_PRESS），一律走 `performHapticFeedback` 的**平台常量**
  而不是自建 `Vibrator`：这样用户关掉系统「触感」时不会震，也不会绕过强弱档位。
- 铺点（**刻意不全铺**）：课表页圆钮（加课/更多/侧栏/分享/导入）、课程格子、
  面板开合、周数按钮、周选择面板选周、课程详情的关闭/上下一门/编辑/删除、导入选择、导出面板。
- **同一次操作只发一次**：点周数按钮会同时触发"按钮选中"和"ViewPager 翻页"，
  所以翻页那一路改用**手势判定**（`onPageScrollStateChanged` 里只在发生过 `DRAGGING` 后才发），
  并且初始化定位 / 展开面板时对齐当前周的**程序化 `check()`** 用 `suppressWeekTick` 挡掉，
  否则冷启动 1 秒后会凭空震一下。
- 验证：`HapticsTest`（三档各自对应一个平台常量、三档互不相同）。

### 26-3 分享/导出面板退不出去

- 根因：`ExportSettingsFragment` 在 `onViewCreated` 里写死 `isCancelable = false`，
  返回手势与点遮罩全部无效，只剩最底下那个「取消导出」。导出后的「是否分享」对话框同样锁着。
- 修法：面板改回可取消（返回键/手势、点遮罩、点取消三条路都通）；`showShareDialog` 去掉
  `setCancelable(false)`（文件**已经导出成功**了，这个框只是在问"还要不要顺手分享"）。
  导入前的文件选择器提示框同理。
- 验证：新增 `ExportSettingsFragmentExitTest`（面板真的在屏幕上且可取消）。

### 26-4 导入后停在空白页

- 根因：`LoginWebActivity` 的外部「打开文件」分支刻意不加任何 Fragment，而 `catch` 里
  只有 toast、没有 `finish()`；导入失败后 toast 一消失就是一个空白页。
- 修法：失败分支补 `finish()`；另外补上"既没有可识别 `import_type`、也不是 `ACTION_VIEW`"
  的分支（同样提示并结束）。**不能**在 `onCreate` 末尾无条件 `finish()` —— `ACTION_VIEW`
  那条路是 `launch{}` 异步导入的。

### 26-5 首次进入应该是「无课表」而不是「空课表」

- 根因：`UpdateUtils.initDefaultData` 会插一行 `tableName = ""` 的默认课表，
  于是"刚装好的 App"和"用户把课表删光了"完全无法区分（管理课表里挂着一张没名字的表）。
- 修法：全新安装**只种默认作息、不种课表**；"已经种过"的凭据从"库里有没有课表"改成
  **"默认作息还在不在"**（课表本来就可能一张都没有）。首页早就支持 `getDefaultTable() == null`
  的引导分支，所以这一步之后"无课表"才真正可达。
- 连带修一处真问题：`addBlankTable` 以前插的是 `type = 0`（非默认），没有预置课表时
  **用户建完表首页仍停在"去导入"引导上**。现在把这条不变量放进 DAO
  （`TableDao.insertTableAsDefaultIfNone`，`@Transaction`）：库里一张表都没有时，新插入的
  那张接管默认标记；首页新建表后立刻 `initView()` 重载。
- 验证：`UpdateUtilsSeedTest` 增两条（全新安装一行表都没有；第一张表接管默认、第二张不抢），
  原「seeding twice」用例改为断言"默认作息在、课表不在"。

### 26-6 应用内「添加桌面小部件」：**整体移除**（用户决定）

- 过程：先按"官方 API 优先"重写了一遍（原实现一上来就跳 `widget://picker`，把
  `requestPinAppWidget` 短路掉了）。真机实测（`uiautomator` 读文本 + `input tap` 驱动）：
  - 新版第一步确实弹出了「添加桌面小部件 / 当天课程·4×2 / 一周课程·4×4 / 取消」，
    **没有**再跳厂商列表 —— 说明 `isRequestPinAppWidgetSupported` 在这台机器上是 `true`；
  - 点「当天课程」后 `requestPinAppWidget` **返回 true**，但桌面既不弹确认框、
    也不真的多出实例（`dumpsys appwidget` 里实例数为 0，应用自己的"已请求桌面添加"
    对话框是诚实的）。
- 结论：各厂商桌面在这件事上没有可用的通用做法；剩下的路都是"把用户丢进第三方列表里自己找"。
  用户拍板**放弃该功能并删干净**。
- 删除清单（已全部执行，`grep WidgetPin|ADD_WIDGET|widget://picker|requestPinAppWidget`
  在 `android/app/src` 下只剩一条说明性注释）：
  - `utils/WidgetPinReceiver.kt`（整个文件）
  - `AndroidManifest.xml` 里的 `<receiver android:name=".utils.WidgetPinReceiver" />`
  - `SettingRowId.ADD_WIDGET`
  - `SettingsList` 里那一行「添加桌面小部件」
  - `SettingsActivity` 的 `showWidgetPicker` / `requestWidgetPin` / `showAddWidgetGuide` 与分发分支
  - 测试 `widgetRowDoesNotHijackIntoVendorPicker` / `vendorPickerIsReachableAsAFallback` /
    `addWidgetRowUsesNavigateArrow`；新增一条反向用例
    `settingsHasNoInAppAddWidgetEntry`（设置页不该再出现任何"小部件"行）
- 保留：选择器预览只由 `android:previewImage` 提供 —— 三个**预览布局**
  （`day_widget_preview.xml` / `small_day_widget_preview.xml` / `week_widget_preview.xml`）
  与对应的 `android:previewLayout` 声明已在"详情页点预览会打开应用"那一轮删除，原因见
  IMPLEMENTATION-MANUAL §1「小部件预览只声明 previewImage」。预览位图由
  `WidgetPreviewImageTest` 从真实布局渲染生成，浅色与深色各一套。

### 26-7 顺带修掉的测试隔离缺陷

整包跑 `:app:testDebugUnitTest` 时，`DatabaseUpgradeFromOldVersionTest` /
`DatabaseDowngradeFromNewerVersionTest` 会以"旧数据还在（expected 0 but was 1）"假红，
单独跑却是绿的。根因是 `AppDatabase` 是**静态单例**：同一个 Robolectric 沙箱里前面的测试类
已经把它建了出来，那时它握着的是这个路径上**旧文件**的连接，于是用例里"删文件 + 写一份
旧版本库"这一步根本没被走到。修法：`LegacyDb.seed` 里先 `AppDatabase.getDatabase(context).close()`。

> 这条不是本轮功能改动的副作用，是**本来就存在的顺序依赖**，被新增用例扰动的顺序暴露了出来。

### 26-8 本轮门禁与真机核对

- `:app:testDebugUnitTest`：**50 类 / 252 项 / 0 失败 / 0 错误**（结果 XML 汇总）。
  新增 3 个测试类（`ArrowViewGeometryTest`、`HapticsTest`、`ExportSettingsFragmentExitTest`）
  与 `UpdateUtilsSeedTest` 的 2 条、`SettingsSystemActionsTest` 的 1 条。
- `:app:assembleDebug` 通过；装上真机后冷启动正常，`logcat -b crash` 为空。
- 设置页真机核对（`uiautomator dump` 读文本，非目视）：外观分组现在是
  「外观 → 显示主题 → 主题颜色 → 课表全屏显示 → 日视图用课程颜色」，**没有任何"小部件"字样**。

### 26-9 侧栏（抽屉）的两件事：行点击没有触感、跳转是两次动画串行

**触感**：`initNavView` 里五行的 `setOnClickListener` 都没给回执。现在每行开头都是
`Haptics.tap(...)`，与本页其它入口同一档（`navRowSchedule/Course/Setting/About/NewTable`）。

**动画**：根因是这一句

```kotlin
ui.drawerLayout.closeDrawer(GravityCompat.START)
ui.drawerLayout.postDelayed({ startActivity(...) }, 360)   // ← 写死等抽屉动画跑完
```

——它把"抽屉收起"和"Activity 转场"**故意排成串行**，用户先看一遍抽屉回弹、再看一遍转场，
像卡了一拍；而且 360 是估出来的常数，抽屉动画时长一变就不准。另一条路（两个动画同时跑）
会让新页面在半路被还没收完的抽屉推一下。现在走第三条：

```kotlin
ui.drawerLayout.closeDrawer(GravityCompat.START, false)   // 抽屉不动画
block()                                                    // 目标页面立刻启动
```

屏幕上只剩 Activity 这一次转场：没有先后等待，也没有互相打架，还去掉了写死的时长。

**实测 A/B**（同一台机、同一路径：展开抽屉 → 点「设置」→ 轮询 `dumpsys activity activities`
直到 `SettingsActivity` 成为 `topResumedActivity`；每次 adb 往返约 90–120ms，两边都含这笔开销）：

| 版本 | 4 次读数 | 结论 |
| --- | --- | --- |
| 旧（`closeDrawer` + `postDelayed(360)`） | 472ms | 含 360ms 等待 |
| 新（不收起动画 + 立即启动） | 175 / 169 / 186 / 174 ms | 扣掉 adb 往返后约 80ms，基本就是启动本身 |

**量测方法的一条教训**（差点让我写出错的结论）：A/B 中途出现过"旧实现反而更快"的自相矛盾读数，
原因是**安装没真正生效**（那一轮的 `adb install` 用了相对路径，设备上跑的还是上一版）。
判定办法是**核对哈希**：`pm path` 拿到设备上的 `base.apk`，pull 出来与本地产物比 SHA-256；
不相等就说明量的不是你以为的那一版。后来还在临时版本里加了一行 `Log.i("DrawerAB", …)`，
用 logcat 确认代码路径真的被走到（日志出现 → 472ms；去掉 → 175ms）。

**顺带修掉的竞态**：去掉 360ms 延迟之后，`navRowCourse` 里的 `viewModel.table` 变成**立刻**读取，
而它是 `lateinit`、由 `initView` 的协程异步填充（抽屉随时可展开）。原来那个延迟只是把窗口掩盖小了。
现在显式挡一道：`if (!viewModel.isTableReady()) { 收抽屉 + longSnack("课表还在加载，稍等一下再试~"); return }`。

## 27. 「关于页返回闪退」静态检查（2026-09-17 夜，用户转述 + 复现失败）

### 27-1 复现记录（先说没复现出来，免得后来人重走）

用户报告：进入关于 → 左上角返回 → 闪退 + 自动重启，并弹出 Toast
「导入参数不完整，请回到 App 里重新开始导入>_<」。之后用户自己也复现不到了，于是转静态检查。

试过且**都无崩溃**（`logcat -b crash` 为空、dropbox 无新增）：
抽屉→关于→左上角返回（正常速度 / 冷启动后 700ms 内抢着点，各 3 次）、系统返回键、左侧边缘手势、
打开「不保留活动」（`settings put global always_finish_activities 1`，事后已还原为 0）。
器械记录侧：dropbox 里本 App 最近一次崩溃是 **19:42 的 `LoginWebActivity` NPE**（即 24 号修复前的那个），
再往前是 **09-16 15:13** 的 `ScheduleFragment.onCreateView` 读 `lateinit table`（已由 `isTableReady()` 守卫）。

### 27-2 静态结论（附真机可证的证据）

**① 清单里的导出过滤器比预期宽得多。** `LoginWebActivity` 的 intent-filter 写了
`<data android:mimeType="application/octet-stream" android:pathPattern=".*\.wakeup_schedule" />`，
**但没有 `android:scheme`**。实测 `cmd package query-activities`：

| 探测的 intent | 课钟是否在候选里 |
| --- | --- |
| `ACTION_VIEW` + `file:///sdcard/Download/foo.txt` + `application/octet-stream` | **在** |
| `ACTION_VIEW` + `file:///sdcard/Download/ok.wakeup_schedule` + 同类型 | 在 |
| `ACTION_VIEW` + **完全不带 data** + 同类型 | **在** |

即：`pathPattern` 根本没参与匹配（没有 scheme 语境），过滤器实际只按 MIME 生效。
而 `application/octet-stream` 是"未知二进制文件"的通用兜底类型 —— **任何应用 VIEW 一个未知类型文件
都可能把课钟拉起来，甚至不带 data**。

**② 「闪退 + 自动重启」的来源是 `backToSplash()`。** `ACTION_VIEW` 分支里"不是课表备份"时走的是

```kotlin
Toasty.error(this, "只能打开 .wakeup_schedule 文件哦>_<", ...).show()
backToSplash()   // FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK
```

`CLEAR_TASK|NEW_TASK` 会把整个任务清掉再重启到首页 —— 用户看到的就是"界面一闪 + App 自己重启"。
**这条分支没有任何新数据要展示，清任务重启毫无必要。**

**③ 入口的文件判定与导入器分叉。** `LoginWebActivity` 自己抄了一份"只看 `uri.path`"的判定，
而真实的「分享 / 从文件管理器打开」给的是 SAF 的 `content://…/document/1234`：**文件名只在
DISPLAY_NAME 里**。导入器（`ImportViewModel.looksLikeWakeupSchedule`）早就为此加了显示名判断，
入口却没跟上 —— 同一个文件在入口被拒、在导入器里合法。

**④ 「导入参数不完整」只可能来自外部裸拉起。** 它出现在
`fragment == null && intent.action != ACTION_VIEW` 分支；App 内三个入口
（`ImportChooseFragment` 两条、`ScheduleActivity.startSuesImport`）**都带 `import_type` 与 `url`**，
真机验证「从学校教务导入」走的是 WebView 路径、不弹这句。所以它只能由外部（或 adb）裸拉起触发。

### 27-3 修法

| 位置 | 修法 |
| --- | --- |
| `BaseTitleActivity` / `BaseBlurTitleActivity` / `BaseListActivity` | 左上角返回箭头补 `Haptics.tap(this)` —— 用户反馈"关于中返回没有震动，还有设置等"；三个基类此前**都没有**接 `Haptics`，所有继承它们的页面都受影响 |
| `ImportViewModel.looksLikeWakeupSchedule` | 由 `private` 改为 `internal`，**入口与导入器共用同一个函数**，不再各写一份 |
| 同上，`file://` 分支 | `file://` 的 path 就是真实文件名：不含 `wakeup_schedule` 时**直接判否**，不再"拿不到显示名就放行" |
| `LoginWebActivity`「不是课表备份」分支 | `backToSplash()` → **`finish()`**（不再清任务重启） |
| `LoginWebActivity`「无 action 无 extras」分支 | 去掉那句 Toast，**静默结束 + 一条 `Log.i`**：用户什么都没发起，弹"导入参数不完整"只会让人以为是自己弄坏了导入 |
| `ImportViewModel.importFromFile` | `openInputStream` 对"文件不存在/读不到"是**抛**异常而不是返回 null，所以原来那句"文件打不开或已被移动"几乎走不到，用户看到的是 `open failed: ENOENT` 系统原文 —— 两种失败收敛成同一句人话 |
| `LoginWebActivity` 两处 catch | 只报 ViewModel 给出的**人话**，`e.message ?: "导入失败>_<文件可能已损坏或读不到"`，不再原样拼接系统异常 |
| `AndroidManifest.xml` | 删掉**无效且误导**的 `pathPattern`，并写明"过滤只能粗到 MIME、真正的判定在 Activity 里" |

### 27-4 验证

- 新增 4 条测试（`ImportFileRobustnessTest`）：显示名才是文件名时入口放行 / 显示名不是课表备份时拦住 /
  `file://` 路径不含文件名时直接判否 / 打不开的文件给人话且不含 `ENOENT`。
  显示名两条用 `Robolectric.buildContentProvider(...).create(authority)` 挂一个只回答
  `OpenableColumns.DISPLAY_NAME` 的提供方来模拟 SAF 下载提供方（`ShadowContentResolver` 的
  `setCursor` 要 `BaseCursor`、`registerProviderInternal` 在该版本不存在，都不行）。
- 真机复验（用 `am start` 从外部拉起，正是用户那条路）：

  | 场景 | 修复前 | 修复后（实测） |
  | --- | --- | --- |
  | `file:///sdcard/Download/foo.txt` + octet-stream | 走到打开文件 → `发生异常>_< … open failed:ENOENT`（用户反馈的原文）；不存在的文件 | 入口判否 → 提示"只能打开 .wakeup_schedule" → 结束，**回到桌面，无重启**；日志里只有别的系统进程的 ENOENT，课钟没有再尝试打开 |
  | 裸拉起（无 action、无 extras） | 弹"导入参数不完整" | `I/LoginWebActivity: 既无 import_type 也非 ACTION_VIEW 的外部启动，直接结束`，**无 toast**，回到桌面 |
  | 崩溃缓冲 | — | 空 |

- 门禁：`:app:testDebugUnitTest` **50 类 / 256 项 / 0 失败**；`assembleDebug` 通过。








## 28. Toast 观感：不透明、占满横轴、一条无用提示（2026-09-17 夜，用户反馈）

### 28-1 现象拆解与根因

用户三条观察：① toast 完全不透明；② 占满 x 轴；③「返回主页才生效」那条是无用内容，不该弹。
拆开是两件事：

**全宽的那条是"后台 toast"。** 它来自 `App.onActivityStopped`（`activityCount == 0` 时才
`show()`），即用户按下返回/HOME、App 已离开前台的那一刻才弹出。Android 11 起后台 toast
一律改由系统渲染成纯文字样式——自定义视图被无视、横贯屏幕；而本 App targetSdk 29 < 30，
前台 toast 仍走 Toasty 的自定义视图（其 `toast_layout` 根布局本来就是 `wrap_content`）。
所以"占满 x 轴"只发生在这一条上：删掉它，「全宽」与「无用内容」一起消失。随之
`activityCount`、整个 `registerActivityLifecycleCallbacks` 块与四个 import 变成死代码，
一并移除；grep 过全部 `Toasty.` 调用点，剩余的都在前台 Activity/Fragment 里，后台渲染路径
不复存在。

**不透明是 Toasty 1.4.2 把颜色烤死在库里。** 反编译 AAR（`javap es.dmoral.toasty.Toasty$Config`）
确认 `Config` 只有 `setToastTypeface / setTextSize / tintIcon / allowQueue`，**没有任何颜色入口**；
五档背景色（normal `#353A3E`、error `#D50000`、success `#388E3C`、info `#3F51B5`、
warning `#FFA900`）都是不透明值，便捷方法直接 `ContextCompat.getColor(R.color.errorColor)`
读资源表，再 `setColorFilter(color, SRC_IN)` 刷到背景 9-patch 上。

### 28-2 修法（资源层，原生合并规则）

SRC_IN 的合成公式是"结果 alpha = 颜色 alpha × 9-patch alpha"，即**带 alpha 的颜色会原样
透传成半透明背景**；而颜色值取自合并资源表，"App 资源覆盖同名库资源"是资源合并的一等规则
（本项目 `android.nonTransitiveRClass=false`，传统合并，App 恒赢）。因此最小正确层是资源层：

- 新增 `res/values/toasty_colors.xml`：按**原名**覆盖五色为 85% alpha（`#D9…`），RGB 一律
  不动；`defaultTextColor`（白）**故意不覆盖**——文字保持全不透明，半透明底上白字才不糊。
- `App.kt`：删后台 toast、`activityCount`、生命周期回调块及孤立 import。

宽度无需改动：自定义视图路径本来就是 `wrap_content`（"尽可能覆盖文字、不额外占空间"），
唯一会全宽的渲染路径（系统后台文字 toast）已随无用提示一起删除。

### 28-3 验证

- 新增 `ToastyBackgroundOverrideTest`（2 项）：特意用**库自己的 R** 去读五个颜色（走库代码
  同一条解析路径），断言值恰为 `0xD9 + 原 RGB`、alpha 恒等于 0xD9——合并规则、库版本、颜色名
  任何一环让覆盖失效都会红；文字色保持 `#FFFFFFFF`。
- 门禁：`:app:testDebugUnitTest` **51 类 / 258 项 / 0 失败**；`assembleDebug` 通过；
  `lintDebug` 仍为既有 5 错（`CourseReminderNotifier` ×1、`ScheduleManageFragment` ×2、
  `today_course_app_widget.xml` ×2），无新增。

## 29. 深色 / 浅色模式适配审计（2026-09-17 夜，用户要求全面检查）

### 29-1 结论先行

**没有发现真正的深色适配 bug。** 适配骨架是对的：`Theme.MaterialComponents.DayNight` +
`values-night/colors.xml`（页面/卡片/文字/分隔线/小部件六层都有深色值），课表屏文字由
`ViewUtils.scheduleTextColor()` 按深浅模式切换，基类与小部件全走主题属性或色 token。

### 29-2 逐面检查记录（都过）

| 检查面 | 取色方式 | 结论 |
| --- | --- | --- |
| 设置/关于/课表管理等页面 | `page_background`/`card_background`/`text_primary`/`text_secondary` token | 两套各有一份，过 |
| 课表屏（头部三行 + 图标 + 课程格） | `scheduleTextColor()`：深色→主题色；自定义背景图→用户选的 `TableBean.textColor` | 过（`initTheme()` 在 onCreate 首帧前执行，`ScheduleActivityUI` 里四处 `Color.BLACK` 只是初值，随后被整体覆盖） |
| 侧栏抽屉 | 卡片底/文字/图标全 token，矢量图标 `imageTintList` 染色；`nav_header.xml` 注释里明确记录了"早先写 `main_background` 导致深色白底白字"的教训 | 过 |
| 周/日小部件 | 底板 `@color/widget_panel_background`（深 `#1A1A1B`）、文字 `widget_panel_text`（深 90% 白）、分隔线深色置透明 | 过 |
| WebView 导入页 | 深色遮罩 + 白字（盖在任意网页上，与模式无关）；错误文字继承主题色 | 过；浅蓝错误插图在深色下可见性成立（浅色图形落在深底上） |
| 周选择/加课页白字 | 白字全部压在彩色内容色（主题蓝、课次色）上 | 过 |
| 布局/样式硬编码十六进制 | 全量扫描（大小写不敏感） | 只剩 `tools:` 预览属性与 WebView 遮罩两处，均为合理存在 |
| Toast（上轮改的 85% 透明） | 白字在两种底色下的对比度（WCAG 公式实算） | 见 29-3 |

### 29-3 Toast 半透明后的对比度实算（白字，85% alpha）

| 档位 | 浅色页面上 | 深色页面上 | 不透明原值·浅色 |
| --- | --- | --- | --- |
| normal 深灰 | 7.26:1 | 13.06:1 | 11.50:1 |
| error 红 | 4.86:1 | 7.07:1 | 5.48:1 |
| info 蓝 | 4.87:1 | 8.50:1 | 6.87:1 |
| success 绿 | 3.24:1 | 5.39:1 | 4.12:1 |
| warning 琥珀 | 1.76:1 | 2.65:1 | 1.92:1 |

normal/error/info 两模式都过 WCAG AA（≥4.5:1，浅色侧 error/info 刚过线）。**success/warning
在 stock Toasty 里本来就低于 AA**（白字配琥珀底 1.92:1 是库的原生配色问题），半透明只让
success 从 4.12 降到 3.24（仍过 AA Large 3:1）。要动它们就是改 Toasty 的配色设计，超出
"改透明度"的授权范围，**未动**；若要修，方案是把这两档 RGB 压深一档再叠加 85% alpha，
一处文件（toasty_colors.xml）可改。

### 29-4 顺带清出的死资源（深色审计的副产品）

全量扫描十六进制色时发现六个**全项目零引用**的文件（layout 与 Kotlin 都不引用，manifest
与小部件元数据也不引用，lint baseline 里本来就躺着 "appears to be unused"）：

- `layout/today_course_app_widget_1.xml`
- `drawable/muticards_bg.xml`（只被上面那个布局引用）
- `drawable/widget_panel_shape.xml`、`widget_frame_shape.xml`、`week_widget_cell_bg.xml`
  （写死 `#ffffffff` 的旧小部件底板，若还活着就是深色白底 bug——好在全是死资源）
- `drawable/course_item_bg_today.xml`

已删除。未跟踪的 WIP 预览文件（day/week_widget_preview.xml）确认没有引用它们。

### 29-5 门禁

`:app:testDebugUnitTest` 51 类 / 258 项 / 0 失败；`assembleDebug` 通过；`lintDebug` 仍为
既有 5 错（同 27 章清单），警告 57→56（死资源告警消失），无新增。

## 30. 「回到课表就生效」一族弹窗清零（2026-09-17 夜，用户第二轮反馈）

### 30-1 为什么用户说"仍然没有变化"

上一轮改的是 **Toasty 的 toast**；用户指着的是另一类东西——`splitties.snackbar.snack` 弹出的
**Material Snackbar**（贴底、全宽、不透明）。两者长得像，用户统称"toast"，但组件不同、
取色也不同，改前者动不了后者。这是"仍然没有变化"的根因：上轮根本没碰这一族。

「为什么回到课表就生效」：设置页改的是偏好（SharedPreferences），课表屏在**变得可见时**
（onResume 比对重建、翻页构建）才读偏好重画——后台的页面无法重渲染自己，所以这些设置
天然是"回到课表才看得见"。弹窗本是预防"是不是没保存上"的安抚，用户判定为噪声。

### 30-2 处置（弹窗清零，时机进副标题）

原则：**改动本身可见的，一律不弹**；只在"回来看不到效果、容易以为没保存上"的行上，
把时机写进**常驻的行副标题**（小字、一直在、不占弹窗），弹窗一条不留。

| 行 | 原弹窗 | 处置 |
| --- | --- | --- |
| 虚线网格 / 显示上课时间 / 课表底部留白 | 「回到课表就生效哦」 | 删。回到课表立刻可见，无需说 |
| 作息时间表（就地选择） | 「回到课表就生效哦」 | 删。同上（onResume 比对重建接手） |
| 空课表插图 | 「切换页面后生效哦」 | 删；副标题补「切换页面后生效」 |
| 页面预加载 | 「重启App后生效哦」 | 删；副标题补「重启 App 后生效」 |
| 课表全屏显示 | 「重启App后生效哦~」 | 删；副标题补「重启 App 后生效」 |
| 主题颜色 | 「重启App后生效哦~」 | 删。该行副标题本来就写着"重启后生效"，弹窗纯属重复 |
| 日视图用课程颜色 | 「请点击小部件右上角的…」 | 删；副标题补「小部件右上角可切换」 |
| 提醒通知不划走 | 「对下一次提醒通知生效哦」 | 删；副标题补「对下一次提醒生效」 |
| 时间表管理「删除成功~」 | longSnack | 删。行消失本身就是回执；守卫（不能删选中项）与失败提示保留 |

设置页里 Snackbar 只剩两条**错误/守卫**（还没课表就来设置、电池白名单引导类仍走对话框）。

### 30-3 验证

- 新增 `SettingsSwitchQuietApplyTest`：在真的 SettingsActivity 上连拨五个开关，断言偏好
  全部落盘（静默 ≠ 没保存）且全程无任何 toast；谁往回加弹窗它就红。
- 门禁：52 类 / 259 项 / 0 失败；`assembleDebug` 通过；`lintDebug` 仍为既有 5 错无新增。
