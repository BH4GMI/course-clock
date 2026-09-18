package courseclock.coursetime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「课程地点 + 节次」换算成上课时间。
 *
 * <p>作息时间按教学楼分三套方案（对应作息表的三列）：上午第 3~5 节错峰，
 * 第 1、2 节与第 6 节及以后三套方案一致。</p>
 *
 * <p>作息表只精确到格，不细分到单节：例如第 10 节与第 10、11 节落在同一格里，
 * 得到的都是 16:35~17:55。这种情形由 {@link Segment#exact()} 标出，调用方可自行决定如何处理。</p>
 *
 * <p>时间一律用「距 0:00 的分钟数」表示，不依赖 {@code java.time}，
 * 因此在任意 Android API level 上都可直接使用。</p>
 */
public final class CourseTimeTable {

    /** 作息表覆盖的节次范围。 */
    public static final int MIN_PERIOD = 1;
    public static final int MAX_PERIOD = 15;

    private CourseTimeTable() {
    }

    // ------------------------------------------------------------ 作息表本身

    /** 作息表的三列。 */
    public enum Scheme {
        /** 教学楼 A、F 楼和 J301 室。 */
        A("教学楼A、F楼和J301室"),
        /** 教学楼 B、C 楼和 J302 室，以及其他楼宇、教学场所。 */
        B("教学楼B、C楼和J302室 / 其他楼宇、教学场所"),
        /** 教学楼 D、E 楼和 J303 室。 */
        C("教学楼D、E楼和J303室");

        private final String label;

        Scheme(String label) {
            this.label = label;
        }

        /** 作息表中该列的标题。 */
        public String label() {
            return label;
        }
    }

    /** 作息表中的一格：一段连续节次及其上下课时间。 */
    public static final class Block {
        public final int fromPeriod;
        public final int toPeriod;
        /** 上课时间，距 0:00 的分钟数。 */
        public final int startMinute;
        /** 下课时间，距 0:00 的分钟数。 */
        public final int endMinute;

        Block(int fromPeriod, int toPeriod, int startMinute, int endMinute) {
            this.fromPeriod = fromPeriod;
            this.toPeriod = toPeriod;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }

        public boolean covers(int period) {
            return period >= fromPeriod && period <= toPeriod;
        }

        public int durationMinutes() {
            return endMinute - startMinute;
        }

        /** 形如「第3节」「第4、5节」「第10-13节」。 */
        public String periodLabel() {
            return CourseTimeTable.periodLabel(fromPeriod, toPeriod);
        }

        /** 形如「10:40 ~ 12:00」。 */
        public String timeLabel() {
            return CourseTimeTable.formatTime(startMinute) + " ~ " + CourseTimeTable.formatTime(endMinute);
        }

        @Override
        public String toString() {
            return periodLabel() + " " + timeLabel();
        }
    }

    private static Block block(int from, int to, int startHour, int startMinute, int endHour, int endMinute) {
        return new Block(from, to, startHour * 60 + startMinute, endHour * 60 + endMinute);
    }

    /** 三套方案共有的时段。 */
    private static final List<Block> COMMON = Collections.unmodifiableList(Arrays.asList(
            block(1, 2, 8, 15, 9, 35),
            block(6, 7, 13, 20, 14, 40),
            block(8, 9, 15, 0, 16, 20),
            block(10, 11, 16, 35, 17, 55),
            block(12, 13, 18, 10, 19, 30),
            block(14, 14, 19, 35, 20, 15),
            block(15, 15, 20, 20, 21, 0)));

    /** 上午第 3~5 节：A 楼方案。 */
    private static final List<Block> MORNING_A = Collections.unmodifiableList(Arrays.asList(
            block(3, 3, 9, 55, 10, 35),
            block(4, 5, 10, 40, 12, 0)));

    /** 上午第 3~5 节：B 楼方案。 */
    private static final List<Block> MORNING_B = Collections.unmodifiableList(Arrays.asList(
            block(3, 4, 9, 55, 11, 15),
            block(5, 5, 11, 20, 12, 0)));

    /** 上午第 3~5 节：C 楼方案。 */
    private static final List<Block> MORNING_C = Collections.unmodifiableList(Arrays.asList(
            block(3, 4, 10, 15, 11, 35),
            block(5, 5, 11, 40, 12, 20)));

    private static final Map<Scheme, List<Block>> BLOCKS = new EnumMap<Scheme, List<Block>>(Scheme.class);

    static {
        BLOCKS.put(Scheme.A, combine(MORNING_A));
        BLOCKS.put(Scheme.B, combine(MORNING_B));
        BLOCKS.put(Scheme.C, combine(MORNING_C));
    }

    private static List<Block> combine(List<Block> morning) {
        List<Block> all = new ArrayList<Block>(morning.size() + COMMON.size());
        all.addAll(morning);
        all.addAll(COMMON);
        Collections.sort(all, new Comparator<Block>() {
            @Override
            public int compare(Block left, Block right) {
                return left.fromPeriod - right.fromPeriod;
            }
        });
        return Collections.unmodifiableList(all);
    }

    /** 某套作息方案的完整时段表，按节次升序。 */
    public static List<Block> blocksOf(Scheme scheme) {
        return BLOCKS.get(scheme);
    }

    // ------------------------------------------------------------ 地点判定

    /** 地点判定结果。 */
    public static final class LocationMatch {
        public final String location;
        public final Scheme scheme;
        /** true 表示命中了楼宇代号；false 表示按「其他楼宇、教学场所」兜底。 */
        public final boolean explicit;
        /** 判定依据，便于排查误判。 */
        public final String evidence;

        LocationMatch(String location, Scheme scheme, boolean explicit, String evidence) {
            this.location = location;
            this.scheme = scheme;
            this.explicit = explicit;
            this.evidence = evidence;
        }

        @Override
        public String toString() {
            return location + " -> " + scheme + (explicit ? "" : "(兜底)");
        }
    }

    /** J301 / J302 / J303 三个专用教室。 */
    private static final Pattern P_ROOM_J = Pattern.compile("(?<![0-9A-Z])J30([123])(?![0-9])");
    /** 「教学楼A」这类写法。 */
    private static final Pattern P_TEACHING_BUILDING = Pattern.compile("教学楼\\s*([A-F])");
    /**
     * 房号：一个 A~F 字母后面紧跟 2~4 位数字，字母前不能是数字或字母。
     * 字母前禁止数字，是为了让「交8B323」「8B317-319」这类非教学楼编号落到兜底方案。
     */
    private static final Pattern P_ROOM_CODE = Pattern.compile("(?<![0-9A-Z])([A-F])(?:楼|栋|座|区|室)?\\d{2,4}");

    /**
     * 由课程地点判定作息方案。
     *
     * <p>识别不出来时不报错，按作息表第二列的「其他楼宇、教学场所」处理，
     * 但 {@link LocationMatch#explicit} 为 false，调用方可以据此决定是否提示用户。</p>
     */
    public static LocationMatch matchLocation(String location) {
        if (location == null || location.trim().isEmpty()) {
            throw new CourseTimeException("课程地点为空，无法判定教学楼。"
                    + "请传入形如「A501（机房）」「B210多」「校外」的地点。");
        }
        String text = normalize(location);

        Matcher roomJ = P_ROOM_J.matcher(text);
        if (roomJ.find()) {
            return new LocationMatch(location, schemeOfJRoom(roomJ.group(1)), true, "命中教室编号 " + roomJ.group());
        }
        Matcher teaching = P_TEACHING_BUILDING.matcher(text);
        if (teaching.find()) {
            return new LocationMatch(location, schemeOfLetter(teaching.group(1).charAt(0)), true,
                    "命中「" + teaching.group() + "」");
        }
        Matcher room = P_ROOM_CODE.matcher(text);
        if (room.find()) {
            return new LocationMatch(location, schemeOfLetter(room.group(1).charAt(0)), true,
                    "命中房号 " + room.group());
        }
        return new LocationMatch(location, Scheme.B, false, "未识别出楼宇代号，按「其他楼宇、教学场所」处理");
    }

    private static Scheme schemeOfJRoom(String digit) {
        if ("1".equals(digit)) {
            return Scheme.A;
        }
        if ("2".equals(digit)) {
            return Scheme.B;
        }
        return Scheme.C;
    }

    private static Scheme schemeOfLetter(char letter) {
        switch (letter) {
            case 'A':
            case 'F':
                return Scheme.A;
            case 'B':
            case 'C':
                return Scheme.B;
            case 'D':
            case 'E':
                return Scheme.C;
            default:
                throw new CourseTimeException("未知的楼宇代号「" + letter + "」，作息表未覆盖该楼宇。");
        }
    }

    // ------------------------------------------------------------ 节次解析

    private static final Pattern P_WEEK = Pattern.compile("[周週]");
    private static final Pattern P_PERIOD_TOKEN = Pattern.compile("(\\d{1,2})(?:-(\\d{1,2}))?");

    /**
     * 解析节次表达，返回去重升序的节次集合。
     *
     * <p>支持「1-5节」「第3节」「1,3-4节」「10~13节」「(1-5节 )」，全角字符会被归一。
     * 含「周」的字符串一律报错：周次不属于本程序的输入契约。</p>
     */
    public static SortedSet<Integer> parsePeriods(String periodSpec) {
        if (periodSpec == null || periodSpec.trim().isEmpty()) {
            throw new CourseTimeException("节次为空。请传入形如「1-5节」「第3节」「1,3-4节」的节次。");
        }
        String text = normalize(periodSpec);
        if (P_WEEK.matcher(text).find()) {
            throw new CourseTimeException("节次「" + periodSpec + "」里含有周次。本程序只处理节次，"
                    + "请先把周次（如「18~21周」）与节次拆开后只传节次。");
        }
        String body = text.replaceAll("[第节（）()\\[\\]【】\\s]", "");
        SortedSet<Integer> periods = new TreeSet<Integer>();
        for (String token : body.split("[,;、，；/]+")) {
            if (token.isEmpty()) {
                continue;
            }
            Matcher matcher = P_PERIOD_TOKEN.matcher(token);
            if (!matcher.matches()) {
                throw new CourseTimeException("无法识别的节次片段「" + token + "」。"
                        + "支持的形式：1-5节、第3节、1,3-4节。");
            }
            int from = Integer.parseInt(matcher.group(1));
            int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
            if (from > to) {
                throw new CourseTimeException("节次区间「" + token + "」的起点大于终点。");
            }
            if (from < MIN_PERIOD || to > MAX_PERIOD) {
                throw new CourseTimeException("节次「" + token + "」超出作息表范围（"
                        + MIN_PERIOD + "-" + MAX_PERIOD + " 节）。");
            }
            for (int period = from; period <= to; period++) {
                periods.add(Integer.valueOf(period));
            }
        }
        if (periods.isEmpty()) {
            throw new CourseTimeException("未能从节次「" + periodSpec + "」中解析出任何节次。");
        }
        return periods;
    }

    // ------------------------------------------------------------ 换算

    /** 结果中的一段：输入要求的节次，以及覆盖这些节次的作息时段。 */
    public static final class Segment {
        /** 输入要求的起始节次。 */
        public final int fromPeriod;
        /** 输入要求的结束节次。 */
        public final int toPeriod;
        /** 覆盖这些节次的作息时段。 */
        public final Block block;

        Segment(int fromPeriod, int toPeriod, Block block) {
            this.fromPeriod = fromPeriod;
            this.toPeriod = toPeriod;
            this.block = block;
        }

        /** 输入节次是否与作息时段的节次完全一致；false 表示时间按更宽的时段给出。 */
        public boolean exact() {
            return fromPeriod == block.fromPeriod && toPeriod == block.toPeriod;
        }

        public String periodLabel() {
            return CourseTimeTable.periodLabel(fromPeriod, toPeriod);
        }

        public int startMinute() {
            return block.startMinute;
        }

        public int endMinute() {
            return block.endMinute;
        }

        @Override
        public String toString() {
            return periodLabel() + " " + block.timeLabel();
        }
    }

    /** 换算结果。 */
    public static final class Result {
        public final String location;
        /** 原始的节次输入。 */
        public final String periodSpec;
        public final LocationMatch match;
        /** 按节次升序的时段；节次跨作息格时会被拆成多段。 */
        public final List<Segment> segments;
        /** 整体起始时间，含课间。 */
        public final int overallStartMinute;
        /** 整体结束时间，含课间。 */
        public final int overallEndMinute;

        Result(String location, String periodSpec, LocationMatch match,
               List<Segment> segments, int overallStartMinute, int overallEndMinute) {
            this.location = location;
            this.periodSpec = periodSpec;
            this.match = match;
            this.segments = Collections.unmodifiableList(segments);
            this.overallStartMinute = overallStartMinute;
            this.overallEndMinute = overallEndMinute;
        }

        public String overallLabel() {
            return formatTime(overallStartMinute) + " ~ " + formatTime(overallEndMinute);
        }

        /** 整体时长，包含段与段之间的课间。 */
        public int overallDurationMinutes() {
            return overallEndMinute - overallStartMinute;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(location).append(' ').append(periodSpec).append(" -> ");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append(segments.get(i));
            }
            return builder.toString();
        }
    }

    /**
     * 换算入口。
     *
     * @param location   课程地点，如「A501（机房）」「B210多」「校外」
     * @param periodSpec 节次，如「1-5节」「第3节」
     * @throws CourseTimeException 地点或节次不合法
     */
    public static Result resolve(String location, String periodSpec) {
        LocationMatch match = matchLocation(location);
        SortedSet<Integer> periods = parsePeriods(periodSpec);
        List<Block> blocks = blocksOf(match.scheme);

        List<Segment> segments = new ArrayList<Segment>();
        Block runBlock = null;
        int runFrom = 0;
        int runTo = 0;
        for (Integer periodValue : periods) {
            int period = periodValue.intValue();
            Block block = blockAt(blocks, period);
            if (block == runBlock) {
                runTo = period;
                continue;
            }
            if (runBlock != null) {
                segments.add(new Segment(runFrom, runTo, runBlock));
            }
            runBlock = block;
            runFrom = period;
            runTo = period;
        }
        if (runBlock != null) {
            segments.add(new Segment(runFrom, runTo, runBlock));
        }

        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (Segment segment : segments) {
            start = Math.min(start, segment.block.startMinute);
            end = Math.max(end, segment.block.endMinute);
        }
        return new Result(location, periodSpec, match, segments, start, end);
    }

    private static Block blockAt(List<Block> blocks, int period) {
        for (Block block : blocks) {
            if (block.covers(period)) {
                return block;
            }
        }
        throw new CourseTimeException("节次 " + period + " 超出作息表范围（"
                + MIN_PERIOD + "-" + MAX_PERIOD + " 节）。");
    }

    // ------------------------------------------------------------ 工具

    /** 形如「第3节」「第4、5节」「第10-13节」。 */
    public static String periodLabel(int from, int to) {
        if (from == to) {
            return "第" + from + "节";
        }
        if (to == from + 1) {
            return "第" + from + "、" + to + "节";
        }
        return "第" + from + "-" + to + "节";
    }

    /** 把距 0:00 的分钟数格式化成「HH:mm」。 */
    public static String formatTime(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d",
                Integer.valueOf(minute / 60), Integer.valueOf(minute % 60));
    }

    /**
     * 归一化：全角转半角、统一区间分隔符、转大写。
     * 只处理书写形式，不猜测语义。
     */
    static String normalize(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            } else if (c == '\u3000') {
                c = ' ';
            }
            switch (c) {
                case '~':
                case '\u301C':
                case '\u2014':
                case '\u2013':
                case '\u2500':
                case '至':
                    c = '-';
                    break;
                default:
                    break;
            }
            builder.append(c);
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }

    /** 输入不合法时抛出，message 面向调用方，说明原因与期望输入。 */
    public static final class CourseTimeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        CourseTimeException(String message) {
            super(message);
        }
    }
}
