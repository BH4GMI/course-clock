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
            // Room 首次查询时才打开连接；isOpen=false 也可能只是尚未查询，不能据此重建。
            // 实例由 close() 显式释放，启动时的观察者、提醒和界面始终共享同一个库。
            val current = INSTANCE
            if (current != null) return current
            return synchronized(AppDatabase::class.java) {
                val existing = INSTANCE
                if (existing != null) {
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

    override fun close() {
        synchronized(AppDatabase::class.java) {
            super.close()
            if (INSTANCE === this) INSTANCE = null
        }
    }

    abstract fun courseDao(): CourseDao

    abstract fun appWidgetDao(): AppWidgetDao

    abstract fun timeTableDao(): TimeTableDao

    abstract fun timeDetailDao(): TimeDetailDao

    abstract fun tableDao(): TableDao
}
