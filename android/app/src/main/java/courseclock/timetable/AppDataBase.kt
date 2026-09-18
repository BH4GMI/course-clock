package courseclock.timetable

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import courseclock.timetable.bean.*
import courseclock.timetable.dao.*

@Database(entities = [CourseBaseBean::class, CourseDetailBean::class, AppWidgetBean::class, TimeDetailBean::class,
    TimeTableBean::class, TableBean::class],
        version = 10, exportSchema = true)

abstract class AppDatabase : RoomDatabase() {

    companion object {
        // 双检锁的共享状态必须是 volatile，否则另一线程可能拿到未完成构造的实例
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // 单例可能**已经被关掉过**：`RoomDatabase.close()` 不会把 INSTANCE 置空，而把它继续
            // 交出去，下一个使用者第一句查询就会抛
            // "Cannot perform this operation because the connection pool has been closed."，
            // 并且这个坏状态会一直留在进程里 —— 之后每一次取库拿到的都是同一个死实例。
            // 单例的契约是"给我一个能用的库"，所以这里用 Room 自己的 `isOpen()` 判活，死了就重建。
            // （`Room.databaseBuilder().build()` 会**立刻**打开库，所以刚建出来的实例 `isOpen()` 就是真，
            //  不会出现"还没查过就被判死、于是每次调用都重建"的问题。）
            val current = INSTANCE
            if (current != null && current.isOpen) return current
            return synchronized(AppDatabase::class.java) {
                val existing = INSTANCE
                if (existing != null && existing.isOpen) {
                    existing
                } else {
                    Room.databaseBuilder(context.applicationContext,
                            AppDatabase::class.java, "wakeup")
                            .allowMainThreadQueries()
                            // 个人 fork：不做跨版本升级。库结构变化时直接重建，
                            // 课表数据由 .wakeup_schedule 文件重新导入即可
                            .fallbackToDestructiveMigration()
                            .build()
                            .also { INSTANCE = it }
                }
            }
        }

    }

    abstract fun courseDao(): CourseDao

    abstract fun appWidgetDao(): AppWidgetDao

    abstract fun timeTableDao(): TimeTableDao

    abstract fun timeDetailDao(): TimeDetailDao

    abstract fun tableDao(): TableDao
}