package courseclock.timetable.utils

import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.CourseDetailBean
import courseclock.timetable.bean.TimeDetailBean
import kotlin.math.abs

/**
 * 按分组查作息时间。
 *
 * 学校可能让同一节次在不同场景下时间不同（本校是上午第 3~5 节按教学楼错峰），
 * 所以「节次 -> 时间」不是一对一，而是「(节次, 分组) -> 时间」。分组键的含义
 * 由导入器决定，这里只查表，不解释。
 *
 * 分组键恒为非空 String，空串即默认分组；null 只可能出现在 Gson 刚反序列化完、
 * 还没归一化的瞬间，那一步在 ImportViewModel 里做，不进到这里。
 *
 * 查不到时退回默认分组，再查不到返回空串——界面拿到空串只是不显示时间，
 * 不会崩。
 */
class CourseTimes(all: List<TimeDetailBean>, val defaultGroup: String = DEFAULT_GROUP) {

    /** 该时间表下的原始行，含所有分组；纵轴要根据默认分组推刻度，所以要能拿到全量。 */
    private val allRows: List<TimeDetailBean> = all

    private val byGroup: Map<String, List<TimeDetailBean>> =
            all.groupBy { it.timeGroup }.mapValues { entry -> entry.value.sortedBy { it.node } }

    fun rows(): List<TimeDetailBean> = allRows

    /**
     * 左侧时间栏、以及"没有分组的课程"认的那一套作息。
     *
     * 本校上午第 3~5 节按教学楼错峰成三套（A/B/C，见 `SuesEamsImporter.MORNING_BLOCKS`），
     * 而一个竖排时间轴只能表示一套，所以"用哪一套"是一个**用户可见的显示选择**：
     * 默认取 [defaultGroup]（导入时按本次占比写进默认分组，设置里也可以强制指定某一套），
     * 取不到就退回空串那套（手工建的时间表只有它），再取不到就是空 —— 界面只是不显示时间，不会崩。
     */
    val defaultList: List<TimeDetailBean> = byGroup[defaultGroup] ?: byGroup[DEFAULT_GROUP] ?: emptyList()

    /** 某个分组下的节次表；该分组不存在时退回默认分组。 */
    fun rowsFor(timeGroup: String): List<TimeDetailBean> = byGroup[timeGroup] ?: defaultList

    fun startOfNode(node: Int, timeGroup: String): String = at(node, timeGroup)?.startTime ?: ""

    fun endOfNode(node: Int, timeGroup: String): String = at(node, timeGroup)?.endTime ?: ""

    /** 默认分组里第 [node] 节的时间行；左侧时间列用它按节次匹配，避免行数与节次数不一致时越界或错位 */
    fun defaultTimeForNode(node: Int): TimeDetailBean? = defaultList.firstOrNull { it.node == node }

    /**
     * 第 [node] 节与第 [node] + 1 节之间要不要画一条网格横线。
     *
     * 学校公布的作息表里有些节次本来就是**一段连堂**：第 1、2 节（8:15~9:35）、
     * 第 6、7 节、第 8、9 节、第 10、11 节、第 12、13 节共用同一个起止时刻，
     * 是**一格**而不是两格；上午第 3~5 节按楼错峰，A 楼是「3」与「4-5」、
     * B/C 楼是「3-4」与「5」，形状还不一样。在连堂内部画线，用户看到的就是
     * 「1、2 节之间多出来一条虚线」——这两节本来就是连着上的。
     *
     * 判据取「相邻两节的起止时刻是否完全相同」，而不是写死 1-2、6-7 这些节次：
     * 起止时刻相同**就是**「同一格」的定义，既可读又对用户自定义的作息表成立，
     * 也不会和 [SuesEamsImporter] 那边按楼宇推导的连格形状分叉。
     *
     * 任一侧没有时间行时返回 true（照旧画线）：宁可多一条线，也不拿缺失的数据去猜。
     */
    fun hasGridLineAfter(node: Int): Boolean {
        val current = defaultTimeForNode(node) ?: return true
        val next = defaultTimeForNode(node + 1) ?: return true
        return current.startTime != next.startTime || current.endTime != next.endTime
    }

    /** 一次上课安排的上课时间：首节的开始时间。 */
    fun startOf(course: CourseBean): String = startOfNode(course.startNode, course.timeGroup)

    /** 一次上课安排的下课时间：末节的结束时间。 */
    fun endOf(course: CourseBean): String =
            endOfNode(course.startNode + course.step - 1, course.timeGroup)

    /**
     * 这节课是不是已经下课了 —— 日视图小部件用它把「今天」里上完的课剔掉（用户要求：上完的课要消失）。
     *
     * 比的是**真实下课时间**（别的楼的课按它自己的分组算），而且按分钟数比而不是按字符串比：
     * 库里的时间可能是 `9:55` 这种没补零的写法，字符串比较会把 9:55 判成晚于 10:35。
     */
    fun isFinished(course: CourseBean, now: String): Boolean {
        val endMinutes = minutesOf(endOf(course)) ?: return false
        val nowMinutes = minutesOf(now) ?: return false
        return endMinutes <= nowMinutes
    }

    /** [courses] 里还没上完的那些，顺序不变。日视图小部件的"今天"列表用它。 */
    fun remaining(courses: List<CourseBean>, now: String): List<CourseBean> =
            courses.filterNot { isFinished(it, now) }

    fun startOf(course: CourseDetailBean): String = startOfNode(course.startNode, course.timeGroup)

    fun endOf(course: CourseDetailBean): String =
            endOfNode(course.startNode + course.step - 1, course.timeGroup)

    private fun at(node: Int, timeGroup: String): TimeDetailBean? {
        val rows = rowsFor(timeGroup)
        return rows.firstOrNull { it.node == node } ?: defaultList.firstOrNull { it.node == node }
    }

    /**
     * 课块在课表里的纵向位置。
     *
     * ## 为什么不能只按节次摆
     *
     * 左侧时间栏显示的是**默认分组**的作息。别的楼用另一套作息时（本校上午第 3~5 节按楼错峰），
     * 时间栏里那一行并不是这节课的真实时间：例如「D 楼第 3~4 节」是 10:15~11:35，而默认分组
     * （B 方案）的第 3~4 节是 09:55~11:15。按节次把块卡进格子，块上那两条横线（上课/下课）
     * 就是假的。
     *
     * ## 规则：在「自己所在那一行」的时间区间里按比例插值
     *
     * - 上边 = 这节课的**上课时间**在**首节所在行**区间里的位置比例；
     * - 下边 = 这节课的**下课时间**在**末节所在行**区间里的位置比例。
     *
     * 默认分组的课，首节时间正好是行区间的起点、末节时间正好是终点，比例是 0 与 1，
     * 于是 `top`/`height` 与旧的按节次公式**逐像素相同** —— 默认课表的观感一个像素都不动。
     *
     * 比例**刻意允许超出 0~1**：D 楼第 3~4 节的下课时间 11:35 在默认分组第 4 行（09:55~11:15）
     * 里已经是 1.25，块就该越出那一格往下压。这正是要求里的「无需严格在虚线框内」。
     *
     * 查不到时间行时退回按节次摆放 —— 没有作息数据不该让课块消失或错位。
     */
    fun blockBox(startNode: Int, step: Int, timeGroup: String, itemHeight: Int, gap: Int): BlockBox {
        val lastNode = startNode + step - 1
        val firstRow = defaultTimeForNode(startNode)
        val lastRow = defaultTimeForNode(lastNode)
        val start = at(startNode, timeGroup)
        val end = at(lastNode, timeGroup)
        if (firstRow == null || lastRow == null || start == null || end == null) {
            return naturalBox(startNode, step, itemHeight, gap)
        }
        val startFraction = fractionOf(start.startTime, firstRow) ?: return naturalBox(startNode, step, itemHeight, gap)
        val endFraction = fractionOf(end.endTime, lastRow) ?: return naturalBox(startNode, step, itemHeight, gap)

        // 放大的是**相对格子的偏离量**，不是格内比例本身：
        // 默认分组的课，上边比例是 0、下边比例是 1，放大后偏离仍是 0 与 (1−1)×K = 0，
        // 所以默认课表一个像素都不动 —— 与系数取多少无关。直接乘比例的话，默认分组的下边
        // 会被推到下一行去（1×2 = 2 行），那是错的。
        val pitch = itemHeight + gap
        val top = (startNode - 1) * pitch + gap +
                Math.round(startFraction * OFFSET_EXAGGERATION * itemHeight)
        val bottom = (lastNode - 1) * pitch + gap + itemHeight +
                Math.round((endFraction - 1f) * OFFSET_EXAGGERATION * itemHeight)

        // **块高永远不小于它占的节次高度**：一门「第 4~5 节」的课必须看起来是两行。
        //
        // 这条下限不是凑数，是几何上必须选的边。A 楼第 4~5 节真实只有 80 分钟（10:40~12:00），
        // 而默认分组第 4、5 行标的合起来是 125 分钟（09:55~11:15 与 11:20~12:00）——
        // 严格按时间画，块高只有 82px，比它占的两行（114px）还矮，看着就像被压扁了：
        // 再叠上放大系数，上边会被推到第 5 行开头，整块就读成"第 5 节"（真机上就是这么被报上来的）。
        // 所以时间差异体现在**整块下移**，而不是把块压扁。
        val naturalHeight = step * itemHeight + (step - 1) * gap
        return BlockBox(top, (bottom - top).coerceAtLeast(naturalHeight))
    }

    /** 旧的按节次摆放。默认分组、以及任何查不到作息的情况都落在这里。 */
    private fun naturalBox(startNode: Int, step: Int, itemHeight: Int, gap: Int): BlockBox =
            BlockBox((startNode - 1) * (itemHeight + gap) + gap,
                    step * itemHeight + (step - 1) * gap)

    /** [time] 在 [row] 这一行的时间区间里的位置比例；时间读不出来时返回 null。 */
    private fun fractionOf(time: String, row: TimeDetailBean): Float? =
            fractionIn(time, row.startTime, row.endTime)

    /** 课块的纵向位置：相对课表顶部的偏移与块高，单位与 [blockBox] 的入参一致。 */
    data class BlockBox(val top: Int, val height: Int)

    companion object {
        /** 默认分组：手工设置的时间表、以及没有分组概念的课程都用它。 */
        const val DEFAULT_GROUP = ""

        /**
         * 课块偏移的放大系数。
         *
         * 严格按时间算，偏移量很小：D 楼第 3~4 节比默认分组晚 20 分钟，换算到行高 56 的格子里
         * 只有 14px（3 倍屏上约 4.7dp）。曾经为了"看得见"把它设成 2f，结果是**帮了倒忙**：
         * A 楼第 4~5 节的真实时长（80 分钟）比它占的那两行标着的时长（125 分钟）短，
         * 上边被放大到第 5 行开头，整块读成"第 5 节" —— 真机上就是这么被报上来的。
         *
         * 所以这里保持 1f（严格比例），差异靠"整块下移 + 块高不压扁"呈现。
         * 真要放大，得先解决上面那个几何问题：格内标的时长与课程真实时长不相等时，
         * 放大偏移就等于放大误差。
         */
        const val OFFSET_EXAGGERATION = 1f

        fun of(list: List<TimeDetailBean>, defaultGroup: String = DEFAULT_GROUP): CourseTimes =
                CourseTimes(list, defaultGroup)

        /**
         * 三套作息**只在上午第 3~5 节不同**（A/B/C 的第 1~2、6~15 节逐格相同）。
         *
         * 这个常量只用来解释"为什么会有分歧"，判定由 [autoAxisGroup] 直接算偏移量，不再靠节次范围。
         */
        val AXIS_NODES = 3..5

        /**
         * 并列（含一个候选都没有）时用哪一套：学校口径里的「其他楼宇、教学场所」，覆盖面最广。
         * 与"教室名认不出楼栋时按哪套算"（`SuesEamsImporter.FALLBACK_GROUP`）刻意分开写：
         * 那是两个独立的判断，取值相同只是巧合。
         */
        const val AUTO_AXIS_FALLBACK = "B"

        /**
         * 「自动」用哪一套作息：**让整张课表看起来最整齐的那一套** —— 累加所有课块在各候选作息下的
         * 偏移量，取最小的那个。
         *
         * ## 偏移量是什么
         *
         * 课块的上边 = 「该课**自己楼宇**的首节时刻」落在「轴的第 startNode 行」区间里的比例；
         * 下边同理（见 [blockBox]）。轴与这门课同属一套作息时，两个比例正好是 0 与 1，
         * 块严丝合缝地贴着自己那两格；轴是另一套时，比例偏离 0/1 多少，块就错位多少。
         * 于是「这套轴在这门课上有多别扭」= `|上边比例| + |下边比例 - 1|`，单位是行高。
         *
         * ## 为什么不再用"投票"
         *
         * 投票必须先回答"哪些课有资格投"——第 6~7 节的课？第 1~10 节的长实验课？只从 3~5 节
         * 起步或收尾的课（1~4、3~9）？每一条都是人为划的线，用户已经连着追问了两次。
         * 累加偏移量**不需要这条线**：
         *
         * - 对轴无所谓的课**自然贡献 0**：第 1~10 节长实验的上边锚在第 1 行、下边锚在第 10 行，
         *   这两行三套作息逐格相同，任何候选下比例都是 0 与 1；第 6~7 节的课同理。
         * - 有所谓的课**自然贡献它真实的错位量**：一门 1~4 节（E 楼）的课底边会被拉开约四分之一行，
         *   一门 3~4 节（D/E 楼）的课上下边各偏一点 —— 谁更该说话，由"错多少"自己决定，
         *   而不是由"算不算一票"决定。
         *
         * 真实样本里当前学期的结果与投票口径一致（都选 C），但理由干净得多。
         *
         * @param candidates 候选分组键（带分组的那几套作息）
         * @param rowOf 查「某分组某节次」的起止时刻；查不到返回 null（该课就没有偏好）
         * @param placements 这张课表的全部安排
         *
         * 并列时优先 [AUTO_AXIS_FALLBACK]，再按分组名排序 —— 同一份数据每次都要得到同一个结果，
         * 否则重开一次 App 时间栏就换个样子。分组键对 App 是不透明的，这里只做确定性排序。
         */
        fun autoAxisGroup(candidates: List<String>,
                          rowOf: (group: String, node: Int) -> Pair<String, String>?,
                          placements: List<CourseDetailBean>): String {
            val ordered = candidates.distinct()
                    .sortedWith(compareBy({ if (it == AUTO_AXIS_FALLBACK) 0 else 1 }, { it }))
            if (ordered.isEmpty()) return AUTO_AXIS_FALLBACK

            fun misalignment(scheme: String): Double {
                var sum = 0.0
                for (placement in placements) {
                    val lastNode = placement.startNode + placement.step - 1
                    // 这门课**自己楼宇**的上下课时刻；查不到（没有分组的手工课）就没有偏好
                    val ownStart = rowOf(placement.timeGroup, placement.startNode)?.first ?: continue
                    val ownEnd = rowOf(placement.timeGroup, lastNode)?.second ?: continue
                    val topRow = rowOf(scheme, placement.startNode) ?: continue
                    val bottomRow = rowOf(scheme, lastNode) ?: continue
                    val top = fractionIn(ownStart, topRow.first, topRow.second) ?: continue
                    val bottom = fractionIn(ownEnd, bottomRow.first, bottomRow.second) ?: continue
                    sum += abs(top) + abs(bottom - 1f)
                }
                return sum
            }

            return ordered.minByOrNull { misalignment(it) } ?: AUTO_AXIS_FALLBACK
        }

        /**
         * [time] 在 [from]~[to] 区间里的位置比例（0 = 区间起点、1 = 终点）；读不出时刻返回 null。
         *
         * 时刻解析委托给 [CourseReminderScheduler.minutesOfDay]：全工程的时刻解析收敛为一处，
         * 校验规则（范围、补零容忍）不会在多处各写一份后慢慢分叉。
         */
        private fun fractionIn(time: String, from: String, to: String): Float? {
            val moment = minutesOf(time) ?: return null
            val start = minutesOf(from) ?: return null
            val end = minutesOf(to) ?: return null
            val span = end - start
            if (span <= 0) return null
            return (moment - start).toFloat() / span
        }

        /** 「HH:mm」-> 当天第几分钟；格式不对返回 null（不猜）。 */
        private fun minutesOf(time: String): Int? = CourseReminderScheduler.minutesOfDay(time)

        /**
         * 按设置页「时间栏作息方案」构造。
         *
         * 偏好值空串 = **自动**：按**这张课表现在实际的安排**重算（[autoAxisGroup]），
         * 而不是读导入时烘焙进默认分组的那一套 —— 否则旧课表切到"自动"永远显示导入当时的
         * 结论，改了课也不会跟着变，与"自适应"名不副实。
         * 否则是 "A"/"B"/"C" 里被指定的那一套；四套作息行导入时都写进了库，
         * 所以切换不需要重新导入，改完回到课表就生效。
         */
        fun ofPreferred(context: android.content.Context, all: List<TimeDetailBean>,
                        details: List<CourseDetailBean>): CourseTimes {
            val selected = context.getPrefer()
                    .getString(Const.KEY_TIME_AXIS_SCHEME, "").orEmpty()
            val group = if (selected.isNotEmpty()) selected else {
                // 候选 = 这张时间表里带分组的那几套作息；行按 (分组, 节次) 索引，查一次建一次
                val rows = all.associateBy({ it.timeGroup to it.node }, { it.startTime to it.endTime })
                autoAxisGroup(
                        candidates = all.map { it.timeGroup },
                        rowOf = { g, node -> rows[g to node] },
                        placements = details)
            }
            return CourseTimes(all, group)
        }
    }
}
