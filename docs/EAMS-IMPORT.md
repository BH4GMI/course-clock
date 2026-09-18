# 教务数据与导入格式

课钟从上海工程技术大学教学服务中心（EAMS）直接读回课表。本文记录数据来源、
`.wakeup_schedule` 的格式，以及生成这个格式时必须遵守的编码约定 —— 改导入器、做外部工具或
排查导入结果时按这里的规则来。

## 数据来源

EAMS 学生端课表页不是服务端渲染的 —— 直接抓 HTML 拿不到课表，页面上那张
`table.courseTable` 是前端用接口数据画出来的。真正的数据在：

```
https://jxfw.sues.edu.cn/student/for-std/course-table/get-data
    ?semesterId=<学期>&dataId=<数据>&bizTypeId=2
```

返回 JSON，其中 `lessons[]` 每项一门课，`lessons[].course.nameZh` 是课名，
`lessons[].scheduleText.dateTimePlacePersonText.textZh` 是形如下面的排课文本：

```
1~3,5~8周 星期二 1~2节 松江校区 B210多 张三;
1~8周 星期四 6~7节 松江校区 B210多 张三
```

注意 `lessons[].nameZh` 是**行政班号**（`0616231;0616233`），不是课名。`semesterId` 与
`dataId` 每次登录可能变化，从 URL 里读即可。

导入器在 WebView 里读到这份 JSON 后自行解析，请求由 App 发起；导出的原始 JSON 含个人课表与
教师信息，**不要提交进版本库**（`.gitignore` 已按 `get-data*.json` 与 `*.wakeup_schedule`
排除）。

## 统一身份认证的滑块验证（自动代拖）

登录页的滑块人机验证由 `captcha.js`（`/cas/themes/sudy_shgcd/js/captcha.js`）渲染：两张图
`/cas/captcha/getCaptcha` 以 data-URL 写进 `.ap-slider-bg`（440×240 JPEG）与
`.ap-slider-img`（80×240 PNG）；拖 `.ap-bar-ctr`，松手时页面按
`parseInt(cursor/slidingScope*scope)` 编码提交到 `/cas/captcha/validate`，成功后页面自己
提交 `fm1` 表单。独立的「滑动登录」文档只推进验证码步骤，不含账号密码；账号密码在另一份
文档里由学校页面处理和加密，App 全程不经手。服务端容差 ±2px。

App 侧的实现契约（改 `WebViewLoginFragment` 或 `sues/SuesSliderSolver.kt` 前必读）：

- **求解器**：整幅滑块图作模板（裁剪会整体偏 +19px）、亮度掩码 `299R+587G+114B>12000`、
  掩码内三通道 ccorr、只在 1.0 档（不缩放）搜索。这是 80 例真实语料上「单次提交
  |x−真值|≤2」判据的最优配置（77/80）；若改「同 id 连试多候选」协议须换多档缩放并重测。
  算法与选型数据详见验证工作区（sues-webvpn-login 仓库）的技术报告。
- **解码**：`BitmapFactory` + `inPremultiplied=false`（等价 PIL 的 RGBA→RGB，保住滑块
  PNG 半透明边缘的 RGB）。全 80 例语料上 Skia 与 PIL 解码的候选表逐例一致（已对拍）。
- **换算**：`slidingScope` 与 `scope` 都是页面闭包变量，读不到，等价取法——zoom 取
  `.ap-bar-ctr` 行内宽 ÷60，`scope` 取两图自然宽之差（440−80=360，与全部语料一致）。
  不能用 `ctr.offsetWidth`（含 1px 边框，提交值会偏出容差）。cursor 反解枚举附近整数取
  提交值最贴近 x 的那个，量化误差 0~1px。
- **事件**：mousedown 派发到 `.ap-bar-ctr`，mousemove/mouseup 派发到 document，与页面
  监听位置一致；`MouseEvent.x` 即 `clientX`。起拖前勾一次 `#rememberMe`（未勾选页面会
  拒绝起拖）。
- **两步门禁**：账号密码文档含 `#password`，必须等密码表单触发 `submit` 事件才接管；
  独立的「滑动登录」文档没有密码框，出现 `.ap-container` 即可接管，因为这一步不提交凭据。
  不再轮询 RSA 密文长度：加密和提交发生在同一次点击内，400ms 采样可能错过。
  监视器只在已接管且滑块就绪后上报图片，不会替用户提交尚未确认的凭据。
  验证码校验失败不提交表单，因此这些重试不会重复提交错误密码。
- **重试与上限**：验证失败页面 500ms 后换图重来；验证码身份按**完整 data-URL 字符串**
  比较（比长度会撞车）。每个登录文档最多自动拖 3 次（页面第 4 次失败会 `maxError` 藏起
  滑块），之后停掉页面侧监视器、撤遮罩、提示用户手动拖。
- **接管窗口**：账号密码页提交登录时立即上报接管，不等下一次轮询；此时只显示遮罩、等待
  跳转，不开始识别。滑动登录页先接管，再等「向右拖动滑块拼图」就绪并识别拖动。
  输入账号密码时不盖遮罩；被退回账号密码新文档时，首次 `armed=false` 上报撤下遮罩。
- **等待上限**：滑动登录页接管后 20 秒仍未上报就绪图片，就停表、撤罩并提示手动验证。
  账号密码提交后的跳转阶段不启动这个滑块等待计时器。用户可随时停止自动脚本。

事件语义核对（2026-09-19）：采用浏览器原生 `addEventListener('submit', ..., true)`，
参见 [MDN：submit 事件](https://developer.mozilla.org/en-US/docs/Web/API/HTMLFormElement/submit_event)
及其链接的 HTML 标准。用户提交或 `requestSubmit()` 会触发该事件，原生校验不通过时不会触发；
直接调用 `form.submit()` 则不触发。这里依赖已抓取的学校账号密码页提交方式，不泛化到任意
登录页；若学校改用直接 `form.submit()`，需要重新验证。未增加依赖，也未采用密码长度轮询。

测试：`SuesSliderSolverTest`（合成用例：定位精度 ±2、候选表结构与去重、边界拒绝）、
`SuesSliderSolverCorpusTest`（2 例真实语料回归，期望值来自验证工作区的 Python 参考候选表，
其中样本 011 锁住「1.0 档已知会错的灾难例」行为）、`SuesCaptchaGlueTest`（锁住三段页面侧
JS 的契约与踩过的坑：只由 x 参数化、行内宽而非 offsetWidth、接管先于识别、submit 事件门禁、
交回用户时撤罩、身份在上报前记账、停表停同一个计时器、事件序列与「先勾 rememberMe」）。
语料夹具为真实验证码图片，只用于测试。

### 端到端验证记录（浏览器验证台）

三段页面侧 JS 的正确性只能对着真 `captcha.js` 判，所以另外用一个**本地验证台**跑
（`_crop/cas-src/sim/prod.html`，`_crop/` 已 gitignore，不入库）：它加载抓包所得的
`captcha.js`，执行的却是**从 Kotlin 常量逐字节导出的生产字符串**（导出方式见
`_crop/DumpCaptchaJsTest.kt.txt`，只需把 `var X=272;` 换成目标 x），只把网络、求解结果和
Kotlin 状态机换成桩，图片用真语料（000 命中档、011 未命中档）。

2026-09-19 在当前环境重新运行 P0–P3、A、B、D–I，共 **66/66 项断言通过**，浏览器
控制台未捕获错误。`prod_strings.js` 的 watch、stop、dragTemplate 与现有 Kotlin 导出文件
逐字符串一致。同期运行 `:app:testDebugUnitTest :app:compileDebugKotlin` 成功，JUnit XML
统计为 **332 项、0 失败、0 错误、0 跳过**。浏览器验证使用 Kotlin 状态机的镜像，不能替代
WebView 生命周期与真实网络的设备测试。

| 场景 | 结论 |
|---|---|
| 坐标换算 | zoom=0.75（真机配置，滑轨 285）下 x=1/7/68/272/300/360 提交值**精确命中**，x=359 差 1px（量化，在 ±2 容差内）；zoom=1.0 下 7 例**全部精确命中** |
| 两步门禁（B） | 有密码框但未提交时，即使人工放入就绪滑块也不接管、不拖动；提交事件立即接管；无密码框的新文档先接管再识别拖动 |
| 失败重试 | 首次提交 272 被判错 → 500ms 换图 → 重新求解 68 → 通过 → 页面自提交 `fm1`；同一张图不会重复上报 |
| 次数上限 | 连续失败时恰好拖 3 次后放弃：停表、撤遮罩，且未触发页面 `maxError`（errorCount 停在 3，留给用户手动拖） |
| 放弃/停止（E/F/G） | 求解器给不出候选时撤下已显示的遮罩、不拖；用户「强制停止」与放弃后换图都不再上报；同一份文档内停表是粘性的（`__suesCaptchaWatch` 守卫挡住重复装表） |
| 返回登录页（H） | 提交后被退回账号密码新文档，`armed=false` 撤罩，用户可继续输入，期间没有拖动 |
| 等不到滑块（I） | 网络桩保持验证码请求待响应，页面持续加载；超时后提示、停表并撤罩，未调用求解器。镜像计时器缩短为 400ms，生产值为 20 秒 |

超时场景不能用坏图片替代待响应请求：当前抓取版 `captcha.js` 在请求成功时直接调用
`setReady(true)`，不等待图片解码，坏图片会进入识别失败分支。验证台仅在现有网络桩注入
待响应状态，不改写第三方脚本或生产字符串。

### 真机验证记录（2026-09-19）

**此前保存的日志**（复核 `_crop/device-logcat.txt`，未操作手机）：

- 04:12:49.702：用户提交登录，记录「登录已提交：接管屏幕，等跳转」。
- 04:12:50.438：首次滑块求解取 x=237，页面返回 `dragged:188`。
- 04:12:53.901：进入学生课表页；04:12:53.905：定位到 `course-table/get-data` 接口。

交接记录另记有成功提示和导入页退出。本轮核对代码可见，导入成功后调用
`setResult(RESULT_OK)` 和 `finish()`；此前测试直接启动 `LoginWebActivity`，退出后返回桌面
符合该启动方式。从 App 主界面进入时应返回原课表页面，这一路径未重新做真机验证。

**1.1 装机验证**（真机 `adb install -r` 覆盖安装）：

- 签名与已安装应用一致（Android Debug 证书），覆盖安装未清除应用数据：安装后课表、
  课程与作息原样保留。
- 安装后冷启动正常，无 `AndroidRuntime` 异常，主界面课表渲染完整。
- **设备为 Android 16 / API 36**（进程内 `Build.VERSION.SDK_INT=36`、`release=16`，见
  `CourseReminderScheduler` 的「重排完成」日志），应用 `targetSdk 29 / minSdk 21`。
  注意：该机在 **shell 里** `getprop ro.build.version.sdk` 报 21、`release` 报 6.0.1，
  系 LSPosed 类兼容模块改写的值；判断真实版本应以进程内 `Build.VERSION` 与
  `ro.system/ro.vendor.build.version.sdk`、`ro.build.fingerprint` 为准。
- 装机验证覆盖「可安装、可启动、数据保留」。**验证码求解链路仍未做真机端到端登录验证**：
  浏览器验证台与单元测试用的都是 Kotlin 状态机的镜像，不能替代 WebView 生命周期与真实
  网络下的设备测试。

## `.wakeup_schedule` 格式

每行一个 JSON 文档，与 App 的「文件导入」和 `ScheduleViewModel.exportData` 一致：

| 行 | 内容 |
| --- | --- |
| 0 | `TimeTableBean` |
| 1 | `List<TimeDetailBean>` |
| 2 | `TableBean` |
| 3 | `List<CourseBaseBean>` |
| 4 | `List<CourseDetailBean>` |

导入后 App 会重新分配 `id` 与 `tableId`，文件里的这两个字段只要内部自洽即可。

## 作息方案：一个必须知道的限制

学校的作息时间按教学楼分三套，上午第 3~5 节错峰：

| 节次 | 方案 A（A、F 楼，J301） | 方案 B（B、C 楼，J302，其他） | 方案 C（D、E 楼，J303） |
| --- | --- | --- | --- |
| 第 3 节 | 09:55 ~ 10:35 | — | — |
| 第 3、4 节 | — | 09:55 ~ 11:15 | 10:15 ~ 11:35 |
| 第 4、5 节 | 10:40 ~ 12:00 | — | — |
| 第 5 节 | — | 11:20 ~ 12:00 | 11:40 ~ 12:20 |

第 1、2 节与第 6 节及以后三套方案一致。节次与教室的完整对应见 [COURSETIME.md](COURSETIME.md)。

把第 3~5 节在三套方案下的真实区间摊开，可以看到它们**两两交叠、互不包含**：

| | 方案 A | 方案 B | 方案 C |
| --- | --- | --- | --- |
| 第 3 节 | 09:55 ~ **10:35** | 09:55 ~ **11:15** | 10:15 ~ **11:35** |
| 第 4 节 | **10:40** ~ 12:00 | 09:55 ~ 11:15 | 10:15 ~ 11:35 |
| 第 5 节 | 10:40 ~ 12:00 | **11:20** ~ 12:00 | **11:40** ~ 12:20 |

同一个「第 4 节」既是 09:55~11:15、又是 10:15~11:35、又是 10:40~12:00。它们不是包含关系
而是交叉关系，因此**不能用折中值或嵌套区间来代替**，只有按楼宇分别取值才是对的。

上游的 `TimeDetailBean` 是**一张扁平的时间表**（节次 → 起止），`TableBean.timeTable` 只
指向一张，也就是一张课表只能承载一套作息。本校的课程分散在三套作息里，那样表达不了。

本应用改成按**分组**查时间：时间表的键是 `(节次, 分组)`，`CourseDetailBean` 带一个
`timeGroup`，由导入器按课程所在楼宇写入。因此：

- 每条安排都按自己所在楼宇取时间，**不存在「选错方案」的偏差**；
- 导入时选定的方案只决定**默认分组**（空串），供左侧时间栏与没有分组概念的课程使用；
- 分组键对 App 是不透明的，App 不解释 `"A"` 是什么意思。

## 编码约定

- 跨节格（如第 1、2 节 08:15~09:35）内**每个节次都填整格起止**。App 取的是
  `timeList[startNode-1].startTime` 与 `timeList[startNode+step-2].endTime`，整格填写正好
  得到该格的起止。
- `TimeTableBean.sameLen` 必须为 `false`：为 `true` 时 App 会用 `courseLen` 覆盖每个节次的
  `endTime`。
- 时间格式为 `HH:mm`（`ICalUtils` 按 `:` 切分）。
- `CourseDetailBean` 的主键是 `(day, startNode, startWeek, type, tableId, id)`。同一时间
  地点只差教师的记录（例如同一门课由多位教师合上）必须合并，否则主键冲突；合并后教师名用
  `/` 连接。
- 非连续周次（`1~3,5~8周`）拆成多个 `CourseDetailBean`，因为一个实例只能表达一个
  `startWeek~endWeek` 区间。隔周（步长 2）映射为 `type` 1（单周）或 2（双周），与 App 的
  `Common.weekIntList2WeekBeanList` 一致。
- 课程配色取 App 的 `customizedColors`，按 `#ff` + RGB 小写生成，与 `Parser.kt` 的
  `Integer.toHexString` 对齐。

## 测试夹具

`android/app/src/test/resources/sues_345_conflict_test.wakeup_schedule` 是上述格式的一份
实例：13 门课、19 条安排、4 个分组，覆盖三套作息与下调课倒计时的时段。它是**随仓库提交的
测试资源**，不是生成物，全新 clone 上直接跑 `./gradlew test` 即可。

`SuesConflictTableTest`（11 项）与 `TimetableRenderProbeTest`（3 项）读它。前者钉住三件事：
每门课 ≥ 2 节且 ≥ 50 分钟、三套方案都有真实的课承载、同一天不重叠；后者按它出渲染图，
用于人工比对观感。

## 已知限制

- 只处理 `get-data` 返回的排课文本。教师或教室名内部若含空格会被误切给教师，本校数据未见
  此情况。
- 导入器使用学期起止日期定位第 1 周；换学期时以教务系统返回的日期为准。
- 不处理调课、停课、`notAttendLessonIds` 与重修补考标记。
