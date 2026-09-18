package courseclock.timetable

import courseclock.timetable.bean.TimeTableBean
import courseclock.timetable.utils.UpdateUtils
import courseclock.timetable.utils.getPrefer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 删除保护的两条不变量（Robolectric + 真实 Room）：
 *
 * 1. 删掉**默认课表**时，默认标记必须转移到剩余的课表上——「全库恰好一行 type = 1」是
 *    提醒、首页、日视图共同依赖的前提，此前删除不做转移，删掉默认表后 App 表现为
 *    "什么都没有了"。
 * 2. 时间表被课表引用时，删除入口必须能查出来：外键是 SET_DEFAULT，不拦的话删除
 *    "成功"，引用它的课表作息被静默改指默认时间表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TableDeletionGuardTest {

    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db get() = AppDatabase.getDatabase(context)
    private val tableDao get() = db.tableDao()

    /**
     * AppDatabase 单例（静态 INSTANCE）在同一个 Robolectric 类沙箱里跨测试方法存活。
     * 不能用 clearAllTables()：TableBean.timeTable 外键是 SET_DEFAULT 而列本身 NOT NULL
     * 且无 SQL 默认值，先删 TimeTableBean 会让 SQLite 在子行上写 NULL 而报错。所以按
     * 外键依赖顺序手动清行（先删子表 tablebean，再删其余）。
     */
    @org.junit.Before
    fun resetState() {
        val database = AppDatabase.getDatabase(context)
        val sqlite = database.openHelper.writableDatabase
        for (table in listOf("tablebean", "coursedetailbean", "coursebasebean",
                        "appwidgetbean", "timedetailbean", "timetablebean")) {
            sqlite.execSQL("DELETE FROM $table")
        }
        context.getPrefer().edit().clear().commit()
    }

    private suspend fun seedTwoTables() {
        // 全新安装的种子只种作息、**不种课表**（见 UpdateUtilsSeedTest），两张表都由这里显式建。
        // id 全部写死：AppDatabase 是单例，自增序列跨测试方法不会重置，靠自增取 id 会和
        // 固定的第二张表撞主键（撞出来的是 UNIQUE 约束错，和"代码算错了"长得一模一样）。
        UpdateUtils.initDefaultData(context)
        db.timeTableDao().insertTimeTable(TimeTableBean(id = 2, name = "第二套作息"))
        tableDao.insertTable(courseclock.timetable.bean.TableBean(
                id = 1, tableName = "默认", timeTable = 1, type = 1))
        tableDao.insertTable(courseclock.timetable.bean.TableBean(
                id = 2, tableName = "第二张", timeTable = 2))
    }

    @Test
    fun `deleting the default table promotes the remaining one`() = runBlocking {
        seedTwoTables()
        assertEquals(1, tableDao.getDefaultTableId())

        tableDao.deleteTableAndFixDefault(1)

        assertNull(tableDao.getTableById(1))
        assertEquals("默认标记应转移到剩余课表", 2, tableDao.getDefaultTableId())
    }

    @Test
    fun `deleting a non-default table keeps the default`() = runBlocking {
        seedTwoTables()
        tableDao.deleteTableAndFixDefault(2)
        assertEquals(1, tableDao.getDefaultTableId())
    }

    @Test
    fun `time table usage is countable`() = runBlocking {
        seedTwoTables()
        assertEquals(1, tableDao.countTablesUsingTimeTable(2))
        assertEquals(0, tableDao.countTablesUsingTimeTable(99))
        assertNotNull(db.timeTableDao().getTimeTable(2))
    }
}
