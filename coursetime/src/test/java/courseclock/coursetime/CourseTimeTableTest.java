package courseclock.coursetime;

import java.io.PrintStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 自测入口，不依赖任何测试框架。
 *
 * <pre>java -cp &lt;classes&gt; courseclock.coursetime.CourseTimeTableTest</pre>
 *
 * <p>全部通过时退出码为 0，有失败时退出码为 1。</p>
 */
public final class CourseTimeTableTest {

    private static int passed;
    private static int failed;
    private static PrintStream out;

    private CourseTimeTableTest() {
    }

    public static void main(String[] args) {
        out = Utf8Stdout.stream();
        testSchemeDetection();
        testTimes();
        testFallbackAndUncertainInput();
        testErrors();
        testFullCoverage();

        out.println();
        out.println("通过 " + passed + " 项，失败 " + failed + " 项。");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 用例

    private static void testSchemeDetection() {
        out.println("[地点 → 作息方案]");

        // 来自未清洗数据样例的真实地点
        scheme("校外", CourseTimeTable.Scheme.B, false, "其他楼宇、教学场所兜底");
        scheme("A501（机房）", CourseTimeTable.Scheme.A, true, "A 楼");
        scheme("B210多", CourseTimeTable.Scheme.B, true, "B 楼");
        scheme("C310多", CourseTimeTable.Scheme.B, true, "C 楼");
        scheme("C106多", CourseTimeTable.Scheme.B, true, "C 楼");
        scheme("E406多", CourseTimeTable.Scheme.C, true, "E 楼");
        scheme("E210多", CourseTimeTable.Scheme.C, true, "E 楼");
        scheme("F302多", CourseTimeTable.Scheme.A, true, "F 楼");
        scheme("交8B323", CourseTimeTable.Scheme.B, false, "8B 不是教学楼编号，落兜底");
        scheme("现代交通工程中心8B317-319", CourseTimeTable.Scheme.B, false, "8B 不是教学楼编号，落兜底");
        scheme("J301室", CourseTimeTable.Scheme.A, true, "J301 专用教室");
        scheme("J302室", CourseTimeTable.Scheme.B, true, "J302 专用教室");
        scheme("J303室", CourseTimeTable.Scheme.C, true, "J303 专用教室");

        // 其他书写形式
        scheme("教学楼A楼301", CourseTimeTable.Scheme.A, true, "教学楼A");
        scheme("教学楼D楼", CourseTimeTable.Scheme.C, true, "教学楼D");
        scheme("a501", CourseTimeTable.Scheme.A, true, "小写归一为大写");
        scheme("Ａ５０１（机房）", CourseTimeTable.Scheme.A, true, "全角归一为半角");
        scheme("A501（机房）  李四", CourseTimeTable.Scheme.A, true, "地点后带教师姓名仍可识别");
        scheme("B210多  张三", CourseTimeTable.Scheme.B, true, "地点后带教师姓名仍可识别");
        scheme("D501", CourseTimeTable.Scheme.C, true, "D 楼");
    }

    private static void testTimes() {
        out.println("[地点 + 节次 → 上课时间]");

        // 上午第 3~5 节错峰：同一节次、不同楼宇，时间不同
        time("A501（机房）", "1-5节",
                "第1、2节@08:15 ~ 09:35 第3节@09:55 ~ 10:35 第4、5节@10:40 ~ 12:00 | 08:15 ~ 12:00");
        time("校外", "1-5节",
                "第1、2节@08:15 ~ 09:35 第3、4节@09:55 ~ 11:15 第5节@11:20 ~ 12:00 | 08:15 ~ 12:00");
        time("E406多", "1-5节",
                "第1、2节@08:15 ~ 09:35 第3、4节@10:15 ~ 11:35 第5节@11:40 ~ 12:20 | 08:15 ~ 12:20");

        // 单个节次落在跨节格里，时间按整格给出并标记为非精确
        time("A501（机房）", "第3节", "第3节@09:55 ~ 10:35 | 09:55 ~ 10:35");
        time("J302室", "第3节", "第3节@09:55 ~ 11:15* | 09:55 ~ 11:15");
        time("J303室", "第3节", "第3节@10:15 ~ 11:35* | 10:15 ~ 11:35");
        time("A501（机房）", "10-10节", "第10节@16:35 ~ 17:55* | 16:35 ~ 17:55");
        time("A501（机房）", "14节", "第14节@19:35 ~ 20:15 | 19:35 ~ 20:15");
        time("A501（机房）", "15节", "第15节@20:20 ~ 21:00 | 20:20 ~ 21:00");

        // 第 6 节起三套方案一致
        time("B210多", "6-7节", "第6、7节@13:20 ~ 14:40 | 13:20 ~ 14:40");
        time("A501（机房）", "6-9节", "第6、7节@13:20 ~ 14:40 第8、9节@15:00 ~ 16:20 | 13:20 ~ 16:20");
        time("E210多", "8-9节", "第8、9节@15:00 ~ 16:20 | 15:00 ~ 16:20");
        time("F302多", "10-13节", "第10、11节@16:35 ~ 17:55 第12、13节@18:10 ~ 19:30 | 16:35 ~ 19:30");

        // 其它书写形式
        time("C310多", "3-4节", "第3、4节@09:55 ~ 11:15 | 09:55 ~ 11:15");
        time("E406多", "3-4节", "第3、4节@10:15 ~ 11:35 | 10:15 ~ 11:35");
        time("F302多", "(1-5节 )", "第1、2节@08:15 ~ 09:35 第3节@09:55 ~ 10:35 第4、5节@10:40 ~ 12:00 | 08:15 ~ 12:00");
        time("B210多", "1,3-4节", "第1节@08:15 ~ 09:35* 第3、4节@09:55 ~ 11:15 | 08:15 ~ 11:15");
        time("Ａ５０１（机房）", "１－５节", "第1、2节@08:15 ~ 09:35 第3节@09:55 ~ 10:35 第4、5节@10:40 ~ 12:00 | 08:15 ~ 12:00");
    }

    private static void testFallbackAndUncertainInput() {
        out.println("[兜底与不确定输入]");

        // 兜底不打异常，但必须如实标记 explicit=false
        CourseTimeTable.Result result = CourseTimeTable.resolve("校外", "1-2节");
        check("兜底方案标记为不确定", !result.match.explicit);
        check("兜底方案取 B 列", result.match.scheme == CourseTimeTable.Scheme.B);
    }

    private static void testErrors() {
        out.println("[非法输入]");

        fails("地点为空", "", "1-2节");
        fails("节次为空", "A501（机房）", "");
        fails("节次含周次", "A501（机房）", "18~21周");
        fails("节次混入周次", "A501（机房）", "(18~21周) (1-5节 )");
        fails("节次越界", "A501（机房）", "16节");
        fails("节次起点大于终点", "A501（机房）", "5-3");
        fails("节次无法解析", "A501（机房）", "abc");
        fails("节次只有包装字", "A501（机房）", "第节");
    }

    private static void testFullCoverage() {
        out.println("[作息表覆盖完整性]");

        Map<CourseTimeTable.Scheme, int[]> counter =
                new EnumMap<CourseTimeTable.Scheme, int[]>(CourseTimeTable.Scheme.class);
        for (CourseTimeTable.Scheme scheme : CourseTimeTable.Scheme.values()) {
            counter.put(scheme, new int[CourseTimeTable.MAX_PERIOD + 2]);
        }
        for (CourseTimeTable.Scheme scheme : CourseTimeTable.Scheme.values()) {
            for (CourseTimeTable.Block block : CourseTimeTable.blocksOf(scheme)) {
                for (int period = block.fromPeriod; period <= block.toPeriod; period++) {
                    counter.get(scheme)[period]++;
                }
            }
        }
        for (CourseTimeTable.Scheme scheme : CourseTimeTable.Scheme.values()) {
            boolean ok = true;
            for (int period = CourseTimeTable.MIN_PERIOD; period <= CourseTimeTable.MAX_PERIOD; period++) {
                if (counter.get(scheme)[period] != 1) {
                    ok = false;
                    out.println("  方案 " + scheme + " 第" + period + "节覆盖 " + counter.get(scheme)[period] + " 次");
                }
            }
            check("方案 " + scheme + " 的第1~15节被且仅被覆盖一次", ok);
        }

        // 每一节都必须能换算，且时间落在同一天内
        boolean allResolved = true;
        for (CourseTimeTable.Scheme scheme : CourseTimeTable.Scheme.values()) {
            for (int period = CourseTimeTable.MIN_PERIOD; period <= CourseTimeTable.MAX_PERIOD; period++) {
                String periodSpec = period + "节";
                for (String location : new String[] {"A501", "B210", "E406"}) {
                    CourseTimeTable.Result result = CourseTimeTable.resolve(location, periodSpec);
                    if (result.segments.isEmpty()
                            || result.overallStartMinute >= result.overallEndMinute
                            || result.overallEndMinute > 24 * 60) {
                        allResolved = false;
                        out.println("  换算异常：" + location + " " + periodSpec);
                    }
                }
            }
        }
        check("所有节次都能换算成合法时间区间", allResolved);
    }

    // ------------------------------------------------------------ 断言工具

    private static void scheme(String location, CourseTimeTable.Scheme expected, boolean explicit, String note) {
        try {
            CourseTimeTable.LocationMatch match = CourseTimeTable.matchLocation(location);
            boolean ok = match.scheme == expected && match.explicit == explicit;
            report(ok, "地点「" + location + "」→ 方案 " + expected + (explicit ? "" : "(兜底)") + "（" + note + "）",
                    ok ? null : "实际 " + match.scheme + (match.explicit ? "" : "(兜底)") + "，判据：" + match.evidence);
        } catch (RuntimeException e) {
            report(false, "地点「" + location + "」", "抛出 " + e);
        }
    }

    private static void time(String location, String periodSpec, String expected) {
        if (expected == null) {
            return;
        }
        try {
            String actual = render(CourseTimeTable.resolve(location, periodSpec));
            report(expected.equals(actual), "「" + location + "」+「" + periodSpec + "」→ " + expected,
                    expected.equals(actual) ? null : "实际 " + actual);
        } catch (RuntimeException e) {
            report(false, "「" + location + "」+「" + periodSpec + "」", "抛出 " + e);
        }
    }

    private static void fails(String note, String location, String periodSpec) {
        try {
            CourseTimeTable.Result result = CourseTimeTable.resolve(location, periodSpec);
            report(false, note, "本应报错，实际得到 " + render(result));
        } catch (CourseTimeTable.CourseTimeException e) {
            report(true, note + " → 报错：" + e.getMessage(), null);
        }
    }

    private static String render(CourseTimeTable.Result result) {
        StringBuilder builder = new StringBuilder();
        for (CourseTimeTable.Segment segment : result.segments) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segment.periodLabel()).append('@').append(segment.block.timeLabel());
            if (!segment.exact()) {
                builder.append('*');
            }
        }
        builder.append(" | ").append(result.overallLabel());
        return builder.toString();
    }

    private static void check(String note, boolean ok) {
        report(ok, note, null);
    }

    private static void report(boolean ok, String note, String detail) {
        if (ok) {
            passed++;
            out.println("  PASS  " + note);
        } else {
            failed++;
            out.println("  FAIL  " + note);
            if (detail != null) {
                out.println("        " + detail);
            }
        }
    }
}
