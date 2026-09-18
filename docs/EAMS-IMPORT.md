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
