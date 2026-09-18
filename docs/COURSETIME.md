# 作息换算

把「课程地点 + 节次」换算成上课时间。作息时间按教学楼分三套方案，同一节次在不同楼宇的
上下课时间不同。

本模块只做换算，不做数据清洗：地点与节次由调用方从课表数据中拆好后传入。它同时也是
一套可独立运行的 Java 程序，源码在 `coursetime/`。

## 输入契约

| 输入 | 形式 | 说明 |
| --- | --- | --- |
| 地点 | `A501（机房）`、`B210多`、`J301室`、`校外` | 单条课程的场地点；允许尾随教师姓名等噪声 |
| 节次 | `1-5节`、`第3节`、`1,3-4节`、`(1-5节 )` | 只含节次；全角字符自动归一 |

节次里出现「周」一律报错。周次属于课表数据的另一维，拆开后再传，避免把
`(18~21周) (1-5节 )` 这类字符串误解析成节次。

## 地点 → 作息方案

作息表分三列，程序按以下优先级匹配，首个命中即生效：

1. **专用教室** `J301` / `J302` / `J303` → 方案 A / B / C。
2. **「教学楼X」写法**：`教学楼A楼` → A，`教学楼D楼` → C。
3. **房号**：`A~F` 中的一个字母后面紧跟 2~4 位数字，字母前不能是数字或字母。A、F → 方案 A；
   B、C → 方案 B；D、E → 方案 C。
4. **兜底**：其余地点按方案 B（其他楼宇、教学场所）处理，`explicit` 置为 `false`，调用方
   据此决定是否提示用户。

第 3 条中「字母前不能是数字」是必要约束：`交8B323`、`现代交通工程中心8B317-319` 里的 `8B`
是楼宇编号而非教学楼代号，不能按 B 楼解读。二者最终仍落方案 B，但走的是兜底路径并如实标记。

## 节次 → 上课时间

三个方案共有的时段：

| 节次 | 时间 |
| --- | --- |
| 第 1、2 节 | 08:15 ~ 09:35 |
| 第 6、7 节 | 13:20 ~ 14:40 |
| 第 8、9 节 | 15:00 ~ 16:20 |
| 第 10、11 节 | 16:35 ~ 17:55 |
| 第 12、13 节 | 18:10 ~ 19:30 |
| 第 14 节 | 19:35 ~ 20:15 |
| 第 15 节 | 20:20 ~ 21:00 |

上午第 3~5 节按楼宇错峰：

| 节次 | 方案 A（A、F 楼，J301） | 方案 B（B、C 楼，J302，其他） | 方案 C（D、E 楼，J303） |
| --- | --- | --- | --- |
| 第 3 节 | 09:55 ~ 10:35 | — | — |
| 第 3、4 节 | — | 09:55 ~ 11:15 | 10:15 ~ 11:35 |
| 第 4、5 节 | 10:40 ~ 12:00 | — | — |
| 第 5 节 | — | 11:20 ~ 12:00 | 11:40 ~ 12:20 |

第 1~15 节均被且仅被一格覆盖。作息表只精确到格，不细分到单节：请求第 10 节时返回覆盖它的
整格 16:35 ~ 17:55，并由 `Segment.exact()` 返回 `false` 标出差异。请求跨格（如方案 A 的
`1-5节`）时拆成多段，段间课间不参与计算，`Result.overallStartMinute` 与
`Result.overallEndMinute` 给出含课间的整体区间。

## 输出

`CourseTimeTable.resolve(location, periodSpec)` 返回 `Result`：

- `segments`：按节次升序的时段列表，每段含输入要求的节次区间、覆盖它们的作息格、起止时间
  与 `exact()`；
- `overallStartMinute` / `overallEndMinute`：整体区间的起止；
- `match`：判定出的方案、判定依据，以及是否走了兜底。

时间一律是距 0:00 的分钟数，不依赖 `java.time`，因此不受 Android API level 限制。
`formatTime` 输出 `HH:mm`。

## 运行

编译并自测（Windows PowerShell）：

```powershell
powershell -ExecutionPolicy Bypass -File coursetime/verify.ps1
```

手动编译与调用（`javac -d` 不会自建输出目录，所以先建）：

```powershell
cd coursetime
New-Item -ItemType Directory -Force -Path build/classes | Out-Null
javac -encoding UTF-8 -d build/classes (Get-ChildItem src -Recurse -Filter *.java).FullName
java -cp build/classes courseclock.coursetime.CourseTimeCli "A501（机房）" "1-5节"
java -cp build/classes courseclock.coursetime.CourseTimeCli --table
java -cp build/classes courseclock.coursetime.CourseTimeTableTest
```

`--table` 打印程序内转录的完整作息表，便于与原表核对。`CourseTimeTableTest` 不依赖测试
框架，全部通过时退出码为 0（当前 52 项）。

标准输出固定按 UTF-8 编码。控制台代码页不是 65001 时，先执行 `chcp 65001`。

## 已知限制

- 作息表只到格，无法给出单节内部的更细时间。
- 地点识别只覆盖 A~F 教学楼的房号写法；其他命名方式落兜底方案 B，不会报错，但会标记为
  不确定。
- 不处理周次、星期、单双周与调课；调用方需自行决定这些维度如何影响闹钟。

## 与 Android 侧的关系

本模块是这套作息的独立实现，便于脱离 Android 环境核对与自测。App 侧另有一份按「分组」查时间
的实现（键是 `(节次, 分组)`），导入器按课程所在楼宇写入分组。两处的判定规则与时间值必须
一致，改动时请一并核对：

| 位置 | 用途 |
| --- | --- |
| `coursetime/src/main/java/courseclock/coursetime/CourseTimeTable.java` | 独立换算与自测（本模块） |
| `android/app/src/main/java/courseclock/timetable/utils/CourseTimes.kt` | App 按 `(节次, 分组)` 取值 |
| `android/app/src/main/java/courseclock/timetable/schedule_import/sues/SuesEamsImporter.kt` | 导入时按楼宇判定分组 |

`android/app/src/test/resources/sues_345_conflict_test.wakeup_schedule` 是一份同时盖住三套
作息的测试课表，`SuesConflictTableTest` 用它钉住上面第 2 条不许漂移。
