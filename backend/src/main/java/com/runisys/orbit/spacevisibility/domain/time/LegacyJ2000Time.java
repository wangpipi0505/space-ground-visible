package com.runisys.orbit.spacevisibility.domain.time;

import java.util.Locale;

/**
 * 原弹道UTC日历与J2000绝对秒的唯一转换主体，纯JDK且不读取全局偏移。
 * 历元为2000-01-01 12:00:00 UTC日历，不是Orekit的J2000 TT；一天固定86400秒。
 * 保留旧解析器、浮点运算和日历边界，包括2000年前不反向累计年份的限制。
 * 本轮只抽取算法，不能用Instant往返修正旧double，也不能声称支持完整公历或闰秒。
 */
public final class LegacyJ2000Time {
    private LegacyJ2000Time() {
    }

    /** 解析原斜杠/连字符UTC日历字符串，单位秒；显式偏移仅为历史数据兼容。格式字段不足仍返回0，数字异常仍向外抛出。 */
    public static double utcToJ2000Sec(String utcTime, double utcOffsetSec) {
        // 保留原空格分隔规则，不额外接受ISO字符串或自动trim。
        String normalized = utcTime.replace('/', '-');
        String[] dateTime = normalized.split(" ");
        if (dateTime.length < 2) {
            return 0.0;
        }
        String[] dateParts = dateTime[0].split("-");
        String[] timeParts = dateTime[1].split(":");
        if (dateParts.length < 3 || timeParts.length < 3) {
            return 0.0;
        }
        int year = Integer.parseInt(dateParts[0]);
        int month = Integer.parseInt(dateParts[1]);
        int day = Integer.parseInt(dateParts[2]);
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);
        double second = Double.parseDouble(timeParts[2]);

        return utcPartsToJ2000Sec(year, month, day, hour, minute, second, true, utcOffsetSec);
    }

    /** 按原日历字段计算UTC起算秒；CSV字段传false，必须直接返回value，不能通过加零替代原分支。 */
    public static double utcPartsToJ2000Sec(int year,
                                          int month,
                                          int day,
                                          int hour,
                                          int minute,
                                          double second,
                                          boolean applyConfiguredOffset,
                                          double utcOffsetSec) {
        long days = daysFrom2000(year, month, day);
        double dayFraction = (hour * 3600.0 + minute * 60.0 + second) / 86400.0;
        double value = (days - 0.5 + dayFraction) * 86400.0;
        return applyConfiguredOffset ? value + utcOffsetSec : value;
    }

    /** 保留原2000年向前累计日历算法：不修正2000年前年份和非法月份的历史行为。 */
    private static long daysFrom2000(int year, int month, int day) {
        // 从2000-01-01累计到目标年月日的日数，保持原日历计数。
        long total = 0;
        for (int y = 2000; y < year; y++) {
            total += isLeapYear(y) ? 366 : 365;
        }
        int[] monthDays = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (isLeapYear(year)) {
            monthDays[1] = 29;
        }
        for (int m = 1; m < month; m++) {
            total += monthDays[m - 1];
        }
        total += day - 1;
        return total;
    }

    /** 沿用原公历闰年判据，不新增闰秒处理。 */
    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /** 返回原秒精度UTC字符串；先按毫秒格式舍入，再截取前19字符。 */
    public static String j2000ToUtc(double j2000Sec, double utcOffsetSec) {
        String millis = j2000ToUtcMillis(j2000Sec, utcOffsetSec);
        return millis.substring(0, 19);
    }

    /** 返回原毫秒UTC字符串并反向扣除显式偏移；日历进位与Math.round顺序全部保留。 */
    public static String j2000ToUtcMillis(double j2000Sec, double utcOffsetSec) {
        // 反向消除可选旧偏移；正常UTC流程的utcOffsetSec保持0。
        double adjustedSec = j2000Sec - utcOffsetSec;
        double totalDays = adjustedSec / 86400.0 + 0.5; // 原日历J2000从正午起算
        long wholeDays = (long) Math.floor(totalDays);
        double dayFraction = totalDays - wholeDays;
        int hour = (int) (dayFraction * 24);
        int minute = (int) ((dayFraction * 24 - hour) * 60);
        double secondValue = ((dayFraction * 24 - hour) * 60 - minute) * 60;
        int second = (int) Math.floor(secondValue);
        int millis = (int) Math.round((secondValue - second) * 1000.0);
        if (millis >= 1000) {
            millis -= 1000;
            second++;
        }
        if (second >= 60) {
            second -= 60;
            minute++;
        }
        if (minute >= 60) {
            minute -= 60;
            hour++;
        }
        if (hour >= 24) {
            hour -= 24;
            wholeDays++;
        }

        int year = 2000;
        long remaining = wholeDays;
        while (true) {
            int daysInYear = isLeapYear(year) ? 366 : 365;
            if (remaining < daysInYear) {
                break;
            }
            remaining -= daysInYear;
            year++;
        }
        int[] monthDays = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (isLeapYear(year)) {
            monthDays[1] = 29;
        }
        int month = 1;
        for (int m = 0; m < 12; m++) {
            if (remaining < monthDays[m]) {
                break;
            }
            remaining -= monthDays[m];
            month++;
        }
        int day = (int) remaining + 1;
        return String.format(Locale.US, "%04d/%02d/%02d %02d:%02d:%02d.%03d", year, month, day, hour, minute, second, millis);
    }
}
