package courseclock.timetable

import courseclock.timetable.bean.CourseBean
import courseclock.timetable.bean.TableBean
import courseclock.timetable.bean.TimeDetailBean
import courseclock.timetable.utils.CourseReminderScheduler as Scheduler
import courseclock.timetable.utils.CourseTimes
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpcomingCourseTest {
    private lateinit var originalZone: TimeZone
    private val table = TableBean(1, "倒计时回归", startDate = "2026-09-07", maxWeek = 20)
    private val times = CourseTimes(listOf(TimeDetailBean(1, "09:00", "10:20", 1)))
    private fun at(value: String) = LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun course(day: Int = 1, type: Int = 0, startWeek: Int = 1, endWeek: Int = 20) =
            CourseBean(1, "课程", day, "B210", "教师", 1, 1, startWeek, endWeek, type, "#2979ff", 1)

    @Before fun setZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }
    @After fun restoreZone() { TimeZone.setDefault(originalZone) }

    @Test fun emptyWeekendFindsMondayInsteadOfStoppingAtTomorrow() {
        val now = at("2026-09-11T12:00:00")
        val next = Scheduler.upcomingOccurrences(listOf(course()), times, table, now).single()
        assertEquals(at("2026-09-14T09:00:00"), next.startAt)
    }

    @Test fun oddEvenWeeksAndFuturePlacementsStayWithinTheirRanges() {
        val now = at("2026-09-07T11:00:00")
        assertEquals(at("2026-09-21T09:00:00"),
                Scheduler.upcomingOccurrences(listOf(course(type = 1)), times, table, now).single().startAt)
        assertEquals(at("2026-09-14T09:00:00"),
                Scheduler.upcomingOccurrences(listOf(course(type = 2)), times, table, now).single().startAt)
        assertEquals(at("2026-10-05T09:00:00"),
                Scheduler.upcomingOccurrences(listOf(course(startWeek = 5, endWeek = 5)), times, table, now).single().startAt)
        assertTrue(Scheduler.upcomingOccurrences(listOf(course(startWeek = 5, endWeek = 5)), times,
                table.copy(maxWeek = 4), now).isEmpty())
    }

    @Test fun beforeTermAfterTermAndMissingStartDateAreDistinct() {
        assertEquals(at("2026-09-07T09:00:00"), Scheduler.upcomingOccurrences(listOf(course()), times, table,
                at("2026-08-01T12:00:00")).single().startAt)
        assertTrue(Scheduler.upcomingOccurrences(listOf(course()), times, table,
                at("2027-02-01T12:00:00")).isEmpty())
        assertTrue(Scheduler.upcomingOccurrences(listOf(course()), times, table.copy(startDate = ""),
                at("2026-09-07T08:00:00")).isEmpty())
        assertTrue(Scheduler.upcomingOccurrences(listOf(course().copy(notAttend = true)), times, table,
                at("2026-09-07T08:00:00")).isEmpty())
    }

    @Test fun sundayFirstKeepsSundayInTheCorrectOddWeek() {
        val now = at("2026-09-12T12:00:00")
        val next = Scheduler.upcomingOccurrences(listOf(course(day = 7, type = 1)), times,
                table.copy(sundayFirst = true), now).single()
        assertEquals(at("2026-09-20T09:00:00"), next.startAt)
    }

    @Test fun previousDaysOvernightClassRemainsCurrent() {
        val overnight = CourseTimes(listOf(TimeDetailBean(1, "23:50", "00:30", 1)))
        val now = at("2026-09-08T00:10:00")
        val state = Scheduler.stateOf(Scheduler.upcomingOccurrences(listOf(course()), overnight, table, now), now)!!
        assertTrue(state.inClass)
        assertEquals(at("2026-09-08T00:30:00"), state.transitionAt)
    }

    @Test fun hiddenFutureOccurrenceIsRetainedForPruningButNextWeekIsVisible() {
        val now = at("2026-09-11T12:00:00")
        val hidden = Scheduler.upcomingOccurrences(listOf(course()), times, table, now).single().key
        val occurrences = Scheduler.upcomingOccurrences(listOf(course()), times, table, now, setOf(hidden))
        assertTrue(occurrences.any { it.key == hidden })
        assertEquals(at("2026-09-21T09:00:00"), Scheduler.stateOf(occurrences, now, setOf(hidden))!!.transitionAt)
    }

    @Test fun upcomingClockSurvivesDaylightSavingWithoutShiftingLocalStart() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val now = at("2026-03-06T12:00:00")
        val next = Scheduler.upcomingOccurrences(listOf(course()), times,
                table.copy(startDate = "2026-03-02"), now).single()
        assertEquals(at("2026-03-09T09:00:00"), next.startAt)
    }

    @Test fun widgetRefreshesOnlyWhenTheRoundedHourChangesAndThenEveryMinute() {
        val day = at("2026-09-07T00:00:00")
        val now = at("2026-09-07T07:00:00")
        val instants = Scheduler.countdownInstants(listOf(course()), times, day, now, includeStart = true)
        assertEquals(at("2026-09-07T07:04:00"), instants.first())
        assertFalse(instants.contains(now + 60_000L))
        val boundary = at("2026-09-07T08:00:00")
        for (minute in 0..60) assertTrue(instants.contains(boundary + minute * 60_000L))
    }
}
