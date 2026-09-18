package courseclock.timetable.utils

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * 自定义背景图的存放处：图片**复制进 App 私有目录**，不再引用外部 URI。
 *
 * ## 为什么不直接存 content:// URI
 *
 * 那条读权限要么是"临时"的（Activity 一结束就没了），要么依赖对方的 FilesProvider
 * （对方被卸载、被清理就没了）。真机上的表现就是"刚设完能用，过一会儿/下次打开弹
 * 『无法检索背景图片』"（日志里的 Permission Denial 就是这么来的）。
 * 复制进来之后这张图就是 App 自己的文件，只有 App 自己会删掉它。
 *
 * 每次导入创建独立文件；完整写入并校验后才返回 URI，URI 同时作为图片缓存的版本身份。
 * 旧文件由设置的保存流程在数据库更新成功后释放，失败不会破坏仍在使用的图片。
 */
object BackgroundStore {

    private const val DIR = "background"
    private const val TAG = "BackgroundStore"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** 这张图是不是 App 自己存的 —— 只有这种才允许删。 */
    fun isInternal(context: Context, background: String): Boolean {
        if (background.isEmpty()) return false
        val path = Uri.parse(background).path ?: return false
        return try {
            File(path).canonicalFile.parentFile == dir(context).canonicalFile
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把 [uri] 复制进 App 私有目录，返回要写进数据库的值（file:// URI）。
     *
     * 失败返回 null —— 调用方必须报错并且**不要**改数据库：存一个打不开的路径，
     * 就是把"下次启动弹无法检索背景图片"埋进去。
     */
    fun import(context: Context, tableId: Int, uri: Uri): String? {
        val ext = (uri.lastPathSegment ?: "")
                .substringAfterLast('.', "")
                .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "png"
        var file: File? = null
        return try {
            val destination = File.createTempFile("bg_${tableId}_", ".$ext", dir(context))
            file = destination
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
                true
            } ?: false
            check(copied) { "无法打开图片来源" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destination.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可解码的图片" }
            Uri.fromFile(destination).toString()
        } catch (e: Exception) {
            Log.e(TAG, "背景图复制失败：$uri", e)
            file?.let { if (it.exists() && !it.delete()) Log.w(TAG, "无法清理未完成的图片：$it") }
            null
        }
    }

    /** 不再用这张图时删掉它（只删 App 自己复制进来的，绝不动外部文件）。 */
    fun delete(context: Context, background: String) {
        if (!isInternal(context, background)) return
        val path = Uri.parse(background).path ?: return
        val file = File(path)
        if (file.exists() && !file.delete()) Log.w(TAG, "无法删除旧图片：$file")
    }
}
