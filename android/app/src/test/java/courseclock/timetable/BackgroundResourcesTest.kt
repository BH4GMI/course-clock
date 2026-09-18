package courseclock.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import courseclock.timetable.utils.BackgroundImageLoader
import courseclock.timetable.utils.BackgroundStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 背景图资源生命周期的回归测试。
 *
 * ## 为什么不直接用 `Uri.fromFile`
 *
 * `android.net.Uri.fromFile(File)` 会把 `File.getAbsolutePath()` 当作**已解码的路径段**塞进
 * 分层 URI。在真机（POSIX）上这得到 `file:///data/user/0/...`，是对的；在 Windows 测试宿主上
 * 得到的是 `file://C%3A%5CUsers%5C...` —— 整条绝对路径落在 **authority** 里，
 * `Uri.parse(...).getPath()` 返回**空串**，于是 `BitmapFactory.decodeFile("")` 解不出图、
 * `File("")` 找不到文件。实测输出（用户名为占位，已脱敏）：
 *
 * ```
 * spec=file://C%3A%5CUsers%5C%3C本机用户名%3E%5C...%5Cbackground-source....png
 * Uri.parse(spec).path=          (空)
 * ```
 *
 * 产品代码只跑在 Android 上，所以这是**测试宿主的平台限制**，不是产品缺陷 —— 不去改产品代码
 * 迁就它，而是在夹具里造一个两边都认的 URI（见 [hostFileUri]），也不放宽任何断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * 造一个**在本机也能解出真实路径**的 `file://` URI：`file://` + 去掉盘符的 POSIX 风格路径。
     *
     * `Uri.parse` 得到的 `/Users/...` 在 Windows 上会被 `java.io.File` 解析到当前盘符下的同名
     * 路径，而 Robolectric 的临时目录就在当前盘上，于是两边对得上。构造完立刻用
     * `canonicalFile` 校验一次：万一换到别的盘跑，这里会**明确报错**，而不是让后面的断言
     * 悄悄退化成"什么都没测到"。
     */
    private fun hostFileUri(file: File): Uri {
        val posix = file.absolutePath.replace('\\', '/')
        val uri = Uri.parse("file://" + posix.replaceFirst(Regex("^[A-Za-z]:"), ""))
        val resolved = File(uri.path!!)
        check(resolved.canonicalFile == file.canonicalFile) {
            "本机无法用 file:// 表示 $file：解析成了 ${resolved.canonicalPath}"
        }
        return uri
    }

    /** 产品接口收的是字符串形态的 URI（数据库里存的就是它）。 */
    private fun hostFileSpec(file: File): String = hostFileUri(file).toString()

    private fun picture(color: Int, width: Int = 32, height: Int = 32): File {
        val file = File.createTempFile("background-source", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    // ---- BackgroundStore：写入的原子性与文件的归属 ------------------------------

    private fun backgroundDir(): File = File(context.filesDir, "background")

    private fun backgroundNames(): Set<String> =
            backgroundDir().listFiles()?.map { it.name }?.toSet() ?: emptySet()

    /** 取这次导入**新产生**的那个文件；多一个少一个都算失败。 */
    private fun newBackgroundFile(before: Set<String>): File {
        val added = (backgroundDir().listFiles() ?: emptyArray()).filter { it.name !in before }
        assertEquals("一次导入应当且只应当产生一个新文件", 1, added.size)
        return added.single()
    }

    @Test
    fun replacementKeepsPreviousFileUntilCommitAndHasNewCacheIdentity() {
        val before = backgroundNames()
        val first = BackgroundStore.import(context, 7, hostFileUri(picture(Color.RED)))!!
        val firstFile = newBackgroundFile(before)
        val original = firstFile.readBytes()

        val second = BackgroundStore.import(context, 7, hostFileUri(picture(Color.BLUE)))!!
        val secondFile = newBackgroundFile(before + firstFile.name)

        assertNotEquals("新图片必须有独立身份，不能覆盖尚在使用的文件", first, second)
        assertNotEquals("两张图必须落在两个不同的文件上",
                firstFile.canonicalPath, secondFile.canonicalPath)
        assertArrayEquals("尚未提交时旧图必须原样保留", original, firstFile.readBytes())

        BackgroundStore.delete(context, hostFileSpec(firstFile))
        assertFalse("交出的旧图应当被删掉", firstFile.exists())
        assertTrue("释放旧图不能删除新图", secondFile.exists())
    }

    @Test
    fun invalidReplacementDoesNotDamagePreviousImage() {
        val before = backgroundNames()
        assertNotNull(BackgroundStore.import(context, 8, hostFileUri(picture(Color.RED))))
        val firstFile = newBackgroundFile(before)
        val original = firstFile.readBytes()

        val invalid = File.createTempFile("invalid-image", ".png", context.cacheDir)
        invalid.writeText("not an image")

        assertNull("非图片必须拒绝，不能把打不开的路径写进库",
                BackgroundStore.import(context, 8, hostFileUri(invalid)))
        assertArrayEquals("拒绝替换不能破坏仍在使用的旧图", original, firstFile.readBytes())
        assertEquals("失败不能留下未完成的临时文件", before.size + 1, backgroundNames().size)
    }

    @Test
    fun adjacentDirectoryIsNotOwnedBackgroundStorage() {
        val file = File(context.filesDir, "background-other/user.png")
        file.parentFile!!.mkdirs()
        file.writeText("用户文件")
        BackgroundStore.delete(context, hostFileSpec(file))
        assertTrue("只允许删 background/ 下的文件，相邻目录一律不动", file.exists())
    }

    // ---- BackgroundImageLoader：渐变与复用 --------------------------------------

    @Test
    fun repeatedTransitionsDoNotKeepNestedHistory() {
        val view = ImageView(context)
        repeat(10) {
            BackgroundImageLoader.attach(view, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), true)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val drawable = view.drawable
        assertTrue("连续换图之后仍然是渐变", drawable is TransitionDrawable)
        assertTrue("渐变的旧层不能再持有历史渐变",
                (drawable as TransitionDrawable).getDrawable(0) is BitmapDrawable)
    }

    @Test
    fun cancellingAPendingRequestLeavesTheViewUntouched() {
        val view = ImageView(context)
        BackgroundImageLoader.loadInto(view, hostFileSpec(picture(Color.RED)), 200, 300)
        BackgroundImageLoader.cancel(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("没有背景的条目不能被上一条数据的图写进来", view.drawable)
    }

    // ---- BackgroundImageLoader：解码的规模与方向 --------------------------------

    @Test
    fun longImageIsDownscaledToCoverTheTargetInsteadOfDecodedWhole() {
        // 4000×1000 放进 400×600：旧实现里"宽高都还得比目标大"判定不成立，倍率退回 1，
        // 整图 4000×1000（16 MB）进堆。CENTER_OUTSIDE 给出的精确缩放是 0.6。
        val source = picture(Color.RED, width = 4000, height = 1000)
        val decoded = BackgroundImageLoader.decode(context, hostFileSpec(source), 400, 600, false, 0)!!
        assertEquals(2400, decoded.width)
        assertEquals(600, decoded.height)
        decoded.recycle()
    }

    @Test
    fun residualScaleIsAppliedThroughDensitySoTheResultHitsTheTarget() {
        // 4000/1500 与 1000/600 的整数倍都是 1，采样帮不上忙，余下的 0.75 只能靠密度缩放；
        // 不归位密度的话 BitmapDrawable 会再缩一次，尺寸和清晰度都会变。
        val source = picture(Color.RED, width = 2000, height = 400)
        val decoded = BackgroundImageLoader.decode(context, hostFileSpec(source), 200, 300, false, 0)!!
        assertEquals(1500, decoded.width)
        assertEquals(300, decoded.height)
        assertEquals("解码后密度必须归位成显示密度",
                context.resources.displayMetrics.densityDpi, decoded.density)
        decoded.recycle()
    }

    @Test
    fun integerSamplingAloneIsEnoughWhenTheRatioIsAPowerOfTwo() {
        val source = picture(Color.RED, width = 1600, height = 1200)
        val decoded = BackgroundImageLoader.decode(context, hostFileSpec(source), 200, 300, false, 0)!!
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)
        decoded.recycle()
    }

    @Test
    fun imageSmallerThanTheRequestIsNeverUpscaled() {
        val source = picture(Color.RED, width = 100, height = 100)
        val decoded = BackgroundImageLoader.decode(context, hostFileSpec(source), 400, 600, false, 0)!!
        assertEquals("解码期放大只会白白多占内存", 100, decoded.width)
        assertEquals(100, decoded.height)
        decoded.recycle()
    }

    @Test
    fun centreCropProducesExactlyTheRequestedBox() {
        val source = picture(Color.RED, width = 2000, height = 400)
        val decoded = BackgroundImageLoader.decode(context, hostFileSpec(source), 200, 300, true, 0)!!
        assertEquals(200, decoded.width)
        assertEquals(300, decoded.height)
        decoded.recycle()
    }

    @Test
    fun undecodableFileFailsVisiblyInsteadOfReturningAGarbageBitmap() {
        val broken = File.createTempFile("broken", ".png", context.cacheDir)
        broken.writeText("not an image")
        assertNull(BackgroundImageLoader.decode(context, hostFileSpec(broken), 200, 300, false, 0))
    }

    @Test
    fun nonPositiveRequestSizeIsRejected() {
        val source = picture(Color.RED)
        assertNull(BackgroundImageLoader.decode(context, hostFileSpec(source), 0, 300, false, 0))
        assertNull(BackgroundImageLoader.decode(context, hostFileSpec(source), 200, 0, false, 0))
    }
}
