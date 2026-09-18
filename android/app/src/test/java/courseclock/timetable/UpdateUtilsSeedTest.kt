package courseclock.timetable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.bean.TableBean
import courseclock.timetable.utils.UpdateUtils
import courseclock.timetable.utils.getPrefer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全新安装的种子数据必须**幂等**，而且**只种作息、不种课表**。
 *
 * 幂等：本函数在冷启动、小部件更新时都会被调到，重复执行不允许叠出重复行。旧实现的隐患是
 * 两层的：插入靠主键冲突 + 空 catch 兜着，播种失败也照样置位 has_adjust。
 *
 * 不种课表：以前这里会插一行 `tableName = ""` 的默认课表，于是"刚装好的 App"和"课表被清空了的
 * App"长得一模一样 —— 管理课表里挂着一张没名字的表，用户以为自己的数据出了问题。
 * 现在全新安装停在**无课表**状态，首页据此走「去导入」的引导。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateUtilsSeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    @Test
    fun `seeding twice does not duplicate rows`() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        UpdateUtils.initDefaultData(context)
        val afterFirst = db.timeDetailDao().getTimeList(1).size
        assertEquals("首次播种应有 30 行作息", 30, afterFirst)

        // 把标记清掉，强制走完整的第二次播种路径
        context.getPrefer().edit().clear().commit()
        UpdateUtils.initDefaultData(context)

        assertEquals("重复播种不得叠行", 30, db.timeDetailDao().getTimeList(1).size)
        assertNotNull("默认作息必须种下去：新建课表的外键指向它", db.timeTableDao().getTimeTable(1))
    }

    @Test
    fun `seeding marks the adjust flag`() = runBlocking {
        UpdateUtils.initDefaultData(context)
        assertTrue(context.getPrefer().getBoolean("has_adjust", false))
    }

    /**
     * 全新安装 = **无课表**：一行 tablebean 都不该有。
     *
     * 这是"首次进入应该是无课表状态而不是空课表"的落地断言 —— 空课表会以一张真表的形式
     * 出现在管理课表里，和"用户把课表删光了"完全无法区分。
     */
    @Test
    fun `fresh install seeds no timetable at all`() = runBlocking {
        UpdateUtils.initDefaultData(context)
        val db = AppDatabase.getDatabase(context)
        assertNull("全新安装不该有默认课表", db.tableDao().getDefaultTable())
        assertEquals("全新安装不该有任何课表", 0, db.tableDao().getTableSelectList().size)
        assertNull("连 id=1 那行也不能有", db.tableDao().getTableById(1))
    }

    /**
     * 没有课表时，**用户自己建的第一张表**必须接管默认表。
     *
     * 否则建完表回到首页仍停在「去导入」的引导上，看着像没建成（`getDefaultTable()` 一直是 null）。
     * 第二张表则不抢默认标记，避免同一个入口产生两种语义。
     */
    @Test
    fun `first created table becomes the default, the second does not`() = runBlocking {
        UpdateUtils.initDefaultData(context)
        val tableDao = AppDatabase.getDatabase(context).tableDao()

        val first = tableDao.insertTableAsDefaultIfNone(TableBean(id = 0, tableName = "第一张"))
        assertEquals("第一张表应成为默认表", first, tableDao.getDefaultTableId())

        val second = tableDao.insertTableAsDefaultIfNone(TableBean(id = 0, tableName = "第二张"))
        assertEquals("第二张表不该抢走默认标记", first, tableDao.getDefaultTableId())
        assertEquals(2, tableDao.getTableSelectList().size)
        assertEquals("全库仍恰好一张默认表", 1,
                tableDao.getTableSelectList().count { it.type == 1 })
    }
}
