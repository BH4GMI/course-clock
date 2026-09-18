package courseclock.timetable

import android.content.Context
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库版本不匹配时的行为。
 *
 * 当前配置是 `fallbackToDestructiveMigration()`（见 [AppDatabase]，注释写明"个人 fork
 * 不做跨版本升级，库结构变化时直接重建"），所以**任何**版本不匹配都会重建库：课表、作息、
 * 课程全部消失，只留一份空库；用户看到的是一张空课表。
 *
 * 这条属于数据安全层面的事实，不能只停在"注释说了"。两个方向各一个类：
 * [AppDatabase] 是单例（静态 INSTANCE），同一个测试类里第二次打开不会重新判定版本，
 * 所以拆开才能各自用真实的 `getDatabase` 配置去验（自己再 new 一个 Room 实例是假绿：
 * 那样验的是测试里写的配置，不是产品里写的）。
 */
private object LegacyDb {

    /** 造一份"上个版本遗留"的库：文件在、`user_version` 是旧值、里面有数据。 */
    fun seed(context: Context, userVersion: Int) {
        // [AppDatabase] 是静态单例：同一个 Robolectric 沙箱里前面的测试类很可能已经把它建出来了，
        // 而它那时握着的是**这个路径上旧文件**的连接。不先关掉，下面"删文件 + 写一份旧版本库"
        // 就白做了 —— 用例会以"旧数据还在"的形式假红（实测：整包一起跑时它数到的是别的用例
        // 留下的 tablebean，单独跑就正常）。关掉之后下一次取库才会真的重新走一遍版本判定，
        // 也就是这条用例要验的东西。
        AppDatabase.getDatabase(context).close()
        val file: File = context.getDatabasePath("wakeup")
        file.parentFile?.mkdirs()
        context.deleteDatabase("wakeup")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'legacy-hash')")
            db.execSQL("CREATE TABLE tablebean (id INTEGER PRIMARY KEY, tableName TEXT)")
            db.execSQL("INSERT INTO tablebean (id, tableName) VALUES (1, '上个版本留下的课表')")
            db.version = userVersion
        }
    }

    /** 用**产品自己的**入口打开库，再数一数旧数据还在不在。 */
    fun legacyRowsAfterOpen(context: Context): Int =
            AppDatabase.getDatabase(context).openHelper.writableDatabase
                    .query("SELECT count(*) FROM tablebean").use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DatabaseUpgradeFromOldVersionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 旧版本（v9）遗留库被新版本（v10）打开。 */
    @Test
    fun 旧版本库会被重建_旧数据不再保留() {
        LegacyDb.seed(context, userVersion = 9)

        val rows = LegacyDb.legacyRowsAfterOpen(context)

        assertEquals("当前是 fallbackToDestructiveMigration：预期旧数据被清空。" +
                "如果这里失败，说明有人加了真正的迁移——那是好事，请一并更新 AppDataBase 的注释与本文档",
                0, rows)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DatabaseDowngradeFromNewerVersionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 装回旧版本包时，库的版本比代码新（v11 > v10）：Room 的破坏性回退覆盖降级方向。 */
    @Test
    fun 降级打开新版本库同样会被重建() {
        LegacyDb.seed(context, userVersion = 11)

        val rows = LegacyDb.legacyRowsAfterOpen(context)

        assertEquals("降级与升级一样会清库；用户可见后果是「装回旧版本后课表空了」", 0, rows)
    }
}
