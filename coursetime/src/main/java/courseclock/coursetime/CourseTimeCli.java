package courseclock.coursetime;

import java.io.PrintStream;
import java.util.List;

/**
 * 命令行入口。
 *
 * <pre>
 *   java courseclock.coursetime.CourseTimeCli &lt;地点&gt; &lt;节次&gt;
 *   java courseclock.coursetime.CourseTimeCli --table
 * </pre>
 */
public final class CourseTimeCli {

    private CourseTimeCli() {
    }

    public static void main(String[] args) {
        PrintStream out = utf8Stdout();
        if (args.length == 0) {
            printUsage(out);
            return;
        }
        if ("--table".equals(args[0])) {
            printTable(out);
            return;
        }
        if (args.length != 2) {
            out.println("参数个数不对：需要「地点」和「节次」两个参数。");
            out.println();
            printUsage(out);
            System.exit(2);
            return;
        }
        try {
            printResult(out, CourseTimeTable.resolve(args[0], args[1]));
        } catch (CourseTimeTable.CourseTimeException e) {
            out.println("无法换算：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void printResult(PrintStream out, CourseTimeTable.Result result) {
        out.println("地点  " + result.location);
        out.println("方案  " + result.match.scheme.name() + "  " + result.match.scheme.label());
        out.println("判据  " + result.match.evidence + (result.match.explicit ? "" : "（兜底）"));
        out.println("节次  " + result.periodSpec);
        out.println();
        for (CourseTimeTable.Segment segment : result.segments) {
            StringBuilder line = new StringBuilder("  ");
            line.append(padTo(segment.periodLabel(), 14));
            line.append(segment.block.timeLabel());
            if (!segment.exact()) {
                line.append("   作息表该段实际为 ").append(segment.block.periodLabel());
            }
            out.println(line.toString());
        }
        out.println();
        out.println("整体  " + result.overallLabel() + "   共 " + result.overallDurationMinutes() + " 分钟（含段间课间）");
    }

    private static void printTable(PrintStream out) {
        out.println("程序内转录的作息表，用于与原始表格核对：");
        out.println();
        for (CourseTimeTable.Scheme scheme : CourseTimeTable.Scheme.values()) {
            out.println("方案 " + scheme.name() + "  " + scheme.label());
            List<CourseTimeTable.Block> blocks = CourseTimeTable.blocksOf(scheme);
            for (CourseTimeTable.Block block : blocks) {
                out.println("  " + padTo(block.periodLabel(), 14) + block.timeLabel());
            }
            out.println();
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("用法");
        out.println("  java courseclock.coursetime.CourseTimeCli <地点> <节次>");
        out.println("  java courseclock.coursetime.CourseTimeCli --table");
        out.println();
        out.println("示例");
        out.println("  java courseclock.coursetime.CourseTimeCli \"A501（机房）\" \"1-5节\"");
        out.println("  java courseclock.coursetime.CourseTimeCli \"E406多\" \"1-2节\"");
        out.println("  java courseclock.coursetime.CourseTimeCli \"校外\" \"6-9节\"");
    }

    /** 按显示宽度补齐，中日韩字符按两列计算。 */
    private static String padTo(String text, int displayWidth) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += displayWidth(text.charAt(i));
        }
        StringBuilder builder = new StringBuilder(text);
        for (int i = width; i < displayWidth; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static int displayWidth(char c) {
        if (c >= 0x1100 && (c <= 0x115F
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6))) {
            return 2;
        }
        return 1;
    }

    /** 固定按 UTF-8 输出，避免 Windows 控制台编码按默认代码页走。 */
    private static PrintStream utf8Stdout() {
        return Utf8Stdout.stream();
    }
}
