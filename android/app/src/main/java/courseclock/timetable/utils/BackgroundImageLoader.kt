package courseclock.timetable.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import courseclock.timetable.R
import java.io.InputStream
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/**
 * 课表背景图的加载器：**只处理本机文件**，不做网络、不做动图。
 *
 * ## 为什么可以不用 Glide
 *
 * `BackgroundStore` 把用户选的图**复制进 App 私有目录**（`filesDir/background/bg_<id>.<ext>`），
 * 数据库里存的恒为 `file://` URI —— 这张图是 App 自己的文件，没有跨进程权限问题、没有网络、
 * 没有动图。Glide 在这里提供的能力实际只剩"采样 + 旋转 + 裁剪 + 圆角 + 缓存"五项，
 * 而它为此常驻了 BitmapPool、MemoryCache、活动资源表与一组引擎线程，并在 R8 之后仍留下
 * 321 个类（占保留类的 5.3%，是全项目最大的单一第三方块）。
 *
 * ## 刻意保留的行为（缺一项就是可见的回归）
 *
 * - **EXIF 方向**：相册/相机拍的照片带旋转标记，Glide 默认认它。不认的话用户刚设好的背景
 *   会躺下来 —— 这是最容易发生的一类回归，所以这里显式处理（见 [orientationOfFile]）。
 * - **采样而非整图解码**：先读 bounds，再按 Glide 的 `CENTER_OUTSIDE` 策略算采样倍率与
 *   缩放密度（见 [applyScaling]），极端长宽比的图也不会整图进堆。
 * - **列表复用不串图**：给 ImageView 打上本次的 key，回调回来时 key 不匹配就直接丢弃；
 *   没有背景图的那条分支必须调 [cancel] 把 key 清掉，否则路上那次解码会贴到复用后的条目上。
 * - **同一张图只解码一次**：见 [loadInto]，两个视图共用同一份 Bitmap，各建各的 Drawable。
 *
 * ## 生命周期
 *
 * 不持有 Activity：解码只[弱引用][java.lang.ref.WeakReference] ImageView，回调前先查 key。
 * 系统报内存吃紧时用 [clearMemory] 交出缓存；View 被复用时用 [cancel] 撤掉在途请求
 * （见 [inFlight]）。两者都不影响正在显示的画面。
 *
 * ## 刻意不做的事
 *
 * 不做磁盘缓存（源文件就在本机，重解码比读两份文件更省）、不做动图/WebP 动图、
 * 不做变换链（本工程只需要居中裁剪与圆角两种）。
 */
object BackgroundImageLoader {

    private const val CROSSFADE_MS = 220

    /**
     * 密度缩放的基准分母。
     *
     * `BitmapFactory` 的 `inScaled` 只认 `inTargetDensity / inDensity` 这个比值，绝对值本身
     * 没有意义（解码完我们马上把 density 归位成显示密度）。用 1000 是 Glide 的原值，留一个
     * 够大的分母是为了让比值有千分之一的精度，不至于把 0.8 这种零头四舍五入掉。
     */
    private const val DENSITY_BASE = 1000

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 两个线程、守护线程。
     *
     * 守护线程是刻意的：本类在进程里没有生命周期钩子，非守护线程会把"进程该退出了"往后拖。
     * 两个而不是一个，是为了避免侧栏头图与列表头像互相排队。
     */
    private val executor = Executors.newFixedThreadPool(2, ThreadFactory { runnable ->
        Thread(runnable, "bg-image").apply { isDaemon = true }
    })

    /**
     * 每个 View **正在排队或正在跑**的那次解码。
     *
     * 只在主线程读写：[loadInto] 由 RecyclerView 的绑定路径调用，[cancel] 由解绑路径调用，
     * 两者都在主线程，所以一个普通 [WeakHashMap] 就够，不需要加锁 —— 也不需要额外的清理协议，
     * View 被 GC 时条目自己消失。
     *
     * 它治的是"滑得快就白解码"：一屏十几个条目，每个都往两个线程的队列里排一次解码，等排到时
     * 这个 View 早被绑到别的数据上了。把还没开始的那些取消掉，只留真正要显示的那几张 ——
     * 对手机是实打实的 CPU 与电量，对用户是无感的（取消的本来就是看不见的图）。
     */
    private val inFlight = WeakHashMap<ImageView, Future<*>>()

    /**
     * 解码结果的内存缓存。
     *
     * 上限刻意压到 6 MB：这些图全是装饰性的，一张半屏背景（540×1200）约 2.6 MB，
     * 6 MB 足够装下"当前课表 + 列表里几张表头"，再多就是在为没人看的像素付内存。
     * 命中即同步返回，列表快速来回滑动时不再重复解码。
     */
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun cacheSizeKb(): Int {
        val heapKb = Runtime.getRuntime().maxMemory() / 1024
        return (heapKb * 0.06f).toInt().coerceIn(1024, 6 * 1024)
    }

    /**
     * 把 [spec] 解码成一张不超过 [reqWidth]×[reqHeight] 的图，并放到 [view] 上。
     *
     * @param centerCrop 是否把结果**裁成** [reqWidth]×[reqHeight]。默认 false —— 只采样、
     *                   由 ImageView 自己的 scaleType 决定怎么摆，这与原先 Glide 未加
     *                   transform 的调用点一致；只有原先显式用了 `CenterCrop()` 的地方才传 true。
     * @param cornerRadiusPx 圆角半径（px），0 表示不裁圆角。
     * @param crossfade 是否与旧图交叉淡入，用于课表主背景这种"整屏换图"的地方；
     *                   列表头像不要开（快速滑动时连续的淡入只会糊成一片）。
     * @param onError 解码失败时回调；**不设置图片**，由调用方决定兜底（置空 / 纯色 / 报错）。
     */
    fun loadInto(
            view: ImageView,
            spec: String,
            reqWidth: Int,
            reqHeight: Int,
            centerCrop: Boolean = false,
            cornerRadiusPx: Int = 0,
            crossfade: Boolean = false,
            onError: (() -> Unit)? = null
    ) {
        val key = keyOf(spec, reqWidth, reqHeight, centerCrop, cornerRadiusPx)
        // 复用防串图：先占位，回调回来时 key 对不上说明这个 View 已经绑到别的数据了。
        view.setTag(R.id.bg_image_load_key, key)
        // 这个 View 上一次的解码此刻已经没有任何意义：它要么还没开始（直接砍掉），
        // 要么已经在跑（砍不掉，但回调会因上面那行 key 不匹配而被丢弃）。
        inFlight.remove(view)?.cancel(false)

        cache.get(key)?.let {
            attach(view, it, crossfade)
            return
        }

        val appContext = view.context.applicationContext
        // 只弱引用 View：解码期间用户可能已经退出这一屏，此时不该把整棵 View 树钉住。
        val viewRef = java.lang.ref.WeakReference(view)
        val task = executor.submit {
            val bitmap = decode(appContext, spec, reqWidth, reqHeight, centerCrop, cornerRadiusPx)
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post {
                val target = viewRef.get() ?: return@post
                if (target.getTag(R.id.bg_image_load_key) != key) return@post
                if (bitmap == null) {
                    onError?.invoke()
                } else {
                    attach(target, bitmap, crossfade)
                }
            }
        }
        inFlight[view] = task
    }

    /**
     * 只解码、不绑定视图：结果交给 [onResult]，由调用方分发给任意多个视图。
     *
     * 存在理由是**课表页那两个视图共用一张背景图**（主背景与侧栏头）—— 用两次 [loadInto] 的话，
     * 若两次都未命中缓存就会解码两遍，正是要修的那个问题。这里解码一次、缓存一次，
     * 调用方拿到的 Bitmap 直接 [attach] 到两个 ImageView：Bitmap 共享，Drawable 各建一个。
     *
     * 失败时 [onResult] 收到 null，调用方自己决定兜底。
     */
    fun loadAsync(
            context: Context,
            spec: String,
            reqWidth: Int,
            reqHeight: Int,
            centerCrop: Boolean = false,
            cornerRadiusPx: Int = 0,
            onResult: (Bitmap?) -> Unit
    ) {
        val key = keyOf(spec, reqWidth, reqHeight, centerCrop, cornerRadiusPx)
        cache.get(key)?.let {
            mainHandler.post { onResult(it) }
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val bitmap = decode(appContext, spec, reqWidth, reqHeight, centerCrop, cornerRadiusPx)
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { onResult(bitmap) }
        }
    }

    /**
     * 把一张已解码的图挂到视图上；[crossfade] 为真时与**上一次挂上去的那张**交叉淡入。
     *
     * 每个视图**各建一个** BitmapDrawable：Bitmap 是共享的，但 Drawable 会被
     * [TransitionDrawable] 改写 alpha，两个视图共用一个 Drawable 实例会互相污染。
     *
     * ## 为什么渐变底座必须自己记，不能读 `view.drawable`
     *
     * 渐变进行中的 `view.drawable` 是 TransitionDrawable 本身，把它当作下一轮的旧层塞进新的
     * `TransitionDrawable(arrayOf(previous, next))`，就得到"渐变里套渐变"。这条引用链不会断：
     * 每次换图都新建一层，历次换过的每一张 Bitmap 都被永久钉在链上，而且每次绘制都要走一遍
     * 嵌套的 alpha 合成。这是内存泄漏 + 白画的 CPU，不是观感问题。
     *
     * 所以这里只认 [R.id.bg_image_current] 里记的那张**叶子** Drawable —— 它永远是
     * BitmapDrawable，从构造上就不可能嵌套。若这个 tag 不存在（视图上的图是别处设的，
     * 比如没有背景时的 ColorDrawable），也不去读 `view.drawable` 兜底：那等于把上面那条
     * 嵌套路径又放回来，而"从一张纯色淡入"本来也没有意义。
     */
    fun attach(view: ImageView, bitmap: Bitmap, crossfade: Boolean) {
        val next: Drawable = BitmapDrawable(view.resources, bitmap)
        val previous = if (crossfade) view.getTag(R.id.bg_image_current) as? Drawable else null
        // 先让上一条动画归位：上一次若是"首次淡入"且还没跑完，view 会停在半透明上，
        // 接着走交叉淡入就会让整张背景一直是灰的（换课表连点两次就能撞上）。
        // 淡入由 view.animate() 驱动、交叉淡入由 TransitionDrawable 自己驱动，两者互不知道对方。
        view.animate().cancel()
        if (previous == null) {
            view.alpha = 1f
            view.setImageDrawable(next)
            if (crossfade) {
                // 首次出现：整张淡入，而不是先闪一下再淡。
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(CROSSFADE_MS.toLong()).start()
            }
        } else {
            view.alpha = 1f
            val transition = TransitionDrawable(arrayOf(previous, next))
            view.setImageDrawable(transition)
            transition.startTransition(CROSSFADE_MS)
        }
        view.setTag(R.id.bg_image_current, next)
    }

    /**
     * 解绑这个 View 上一切与背景图有关的状态：在途请求、复用标记、渐变底座、未跑完的淡入。
     *
     * 调用点是**没有背景图**的那条分支（见 [courseclock.timetable.schedule.TableNameAdapter]）。
     * 不调用它会串图：
     * 条目 A 的解码还在路上，RecyclerView 把这个 View 复用给了没有背景的条目 B，B 设了纯色，
     * 随后 A 的回调回来 —— 占位用的 key 仍然是 A 的，于是把 A 的图贴到了 B 上。
     *
     * alpha 也一并归位：首次淡入被打断时 view 会停在半透明上，复用出去就是一条"发灰的"纯色。
     */
    fun cancel(view: ImageView) {
        inFlight.remove(view)?.cancel(false)
        view.setTag(R.id.bg_image_load_key, null)
        view.setTag(R.id.bg_image_current, null)
        view.animate().cancel()
        view.alpha = 1f
    }

    /**
     * 丢掉全部缓存图片，下次要显示时重新解码。
     *
     * 由 [android.app.Application.onTrimMemory] 在系统报告内存吃紧时调用。它**不影响画面**：
     * 当前显示着的图已经被各个 View 的 Drawable 持有，evict 只是放掉"可能还会再用"的那份。
     */
    fun clearMemory() {
        cache.evictAll()
    }

    private fun keyOf(spec: String, w: Int, h: Int, centerCrop: Boolean, radius: Int): String =
            "$spec|${w}x$h|${if (centerCrop) "crop" else "fit"}|$radius"

    // ---- 解码 --------------------------------------------------------------

    /**
     * 解码。
     *
     * 声明为 `internal` 而不是 `private`，是为了让单元测试能**同步**验证采样倍率、密度归位与
     * EXIF 旋转的结果 —— 走 [loadInto]／[loadAsync] 那条路是异步的，测不了这几条不变式。
     * 不为此另开一个"测试专用"入口：那样被验证的就是另一个函数了。
     */
    internal fun decode(
            context: Context,
            spec: String,
            reqWidth: Int,
            reqHeight: Int,
            centerCrop: Boolean,
            cornerRadiusPx: Int
    ): Bitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) return null
        val uri = Uri.parse(spec)
        val path = if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") uri.path else null

        // 只认文件。content:// 那条路不读方向：平台版 ExifInterface 只有从**流**读的构造要
        // API 24，而换成 androidx.exifinterface 实测会让 R8 多保留约 74 KB dex ——
        // 数据库里的背景恒为 file://（BackgroundStore 会把图复制进私有目录），
        // 残留的 content:// 值来自旧版本且多数已经失效，为它们付 74 KB 不划算。
        val orientation = if (path != null) orientationOfFile(path) else ExifInterface.ORIENTATION_UNDEFINED

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path != null) {
            BitmapFactory.decodeFile(path, bounds)
        } else {
            openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val options = BitmapFactory.Options()
        applyScaling(options, srcW, srcH, reqWidth, reqHeight)
        val sampled = if (path != null) {
            BitmapFactory.decodeFile(path, options)
        } else {
            openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } ?: return null

        // 密度归位。applyScaling 一旦用了 inScaled，BitmapFactory 会把 bitmap.density 留成
        // inTargetDensity；不改回来的话，BitmapDrawable 会拿它和自己那边的目标密度**再缩一次**
        // —— 也就是二次缩放，画面发虚、尺寸也对不上。Glide 的 Downsampler 在解码后做的
        // 是同一步（`downsampled.setDensity(displayMetrics.densityDpi)`）。
        sampled.density = context.resources.displayMetrics.densityDpi

        // 旋转只在这一步做：此时图已经按目标尺寸缩过了，旋转的临时分配不会失控。
        val upright = rotate(sampled, orientation)
        val shaped = if (centerCrop) centerCrop(upright, reqWidth, reqHeight) else upright
        return if (cornerRadiusPx > 0) rounded(shaped, cornerRadiusPx) else shaped
    }

    private fun openStream(context: Context, uri: Uri): InputStream? =
            try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }

    /**
     * EXIF 方向。
     *
     * 这一步不能省：Glide 默认认 EXIF，而相册里的照片大多是竖拍横存的（标记 90°）。
     * 跳过它，用户刚设好的背景就是躺着的 —— 属于"优化了性能但改坏了观感"的典型。
     *
     * 只对能拿到真实文件路径的情况读取。content:// 拿不到路径，一律按
     * [ExifInterface.ORIENTATION_UNDEFINED] 处理 —— 那些值来自旧版本、文件本身也多半已经失效，
     * 为它们换成从流读 EXIF 的 `androidx.exifinterface` 要多保留约 74 KB dex，不划算
     * （见 [decode] 里的完整说明）。
     */
    private fun orientationOfFile(path: String): Int =
            try {
                ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_UNDEFINED
            }

    private fun rotate(source: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return source
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        // 先把密度记下来：下面可能在拿到新图之后回收 source，回收过的 Bitmap 不能再读属性。
        val density = source.density
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                    .also {
                        it.density = density
                        if (it !== source) source.recycle()
                    }
        } catch (e: OutOfMemoryError) {
            // 旋不动就把原图交出去：方向可能不对，但至少不是空白。
            source
        }
    }

    /**
     * 新建 Bitmap 时沿用源图的配置。
     *
     * `Bitmap.config` 在 Java 里是可空的，但 Kotlin 看到的是平台类型，直接写
     * `source.config ?: ARGB_8888` 会被编译器判为"?: 左值非空"并告警。显式转成可空类型，
     * 让这个兜底**真的是兜底**而不是被优化掉——解码失败到一半时确实可能拿到无配置的图。
     */
    private fun configOf(source: Bitmap): Bitmap.Config =
            (source.config as Bitmap.Config?) ?: Bitmap.Config.ARGB_8888

    /**
     * 采样倍率 + 解码期的缩放密度。
     *
     * 这是直译 Glide 的默认降采样策略 `DownsampleStrategy.CENTER_OUTSIDE` 与
     * `Downsampler.calculateScaling` 的算法（源码见 bumptech/glide
     * `library/src/main/java/com/bumptech/glide/load/resource/bitmap/`）。移植而不是自己发明，
     * 是因为这段逻辑要同时满足三件事：
     *
     * 1. 图必须**覆盖**目标框（`max(宽比, 高比)`），否则 `centerCrop` 会在边上留白；
     * 2. `inSampleSize` 只能是 2 的幂，取不超过"需要缩小的整数倍"的最大 2 的幂（QUALITY 取整）；
     * 3. 剩下的零头交给 `inTargetDensity`/`inDensity`，让 BitmapFactory **一次**缩到位，
     *    不产生"先解出大图再缩"的中间位图。
     *
     * ## 不改会怎样
     *
     * 原来那版只有一个"宽和高都还得比目标大"的判断：一张 12000×1000 的全景图放进 400×600，
     * 高那一项不成立，倍率直接退回 1 —— 把 3600 万像素、约 48 MB 的位图整个解进堆里。
     * 那是 OOM 级的隐患，不是"慢一点"。改用这套算法后，采样与密度一起把结果压到"刚好覆盖
     * 目标框"的规模。
     */
    private fun applyScaling(
            options: BitmapFactory.Options,
            srcW: Int,
            srcH: Int,
            reqW: Int,
            reqH: Int
    ) {
        // 只缩不放。请求尺寸比原图还大时，解码期放大只是白白多占一份内存：放出来的像素没有
        // 任何新细节，ImageView 本来也会把它铺满同一个区域。
        val exact = minOf(1f, maxOf(reqW / srcW.toFloat(), reqH / srcH.toFloat()))
        val outW = (exact * srcW + 0.5f).toInt()
        val outH = (exact * srcH + 0.5f).toInt()
        if (outW <= 0 || outH <= 0) return
        val factor = minOf(srcW / outW, srcH / outH)
        val sample = maxOf(1, Integer.highestOneBit(factor))
        options.inSampleSize = sample
        // sample * exact 恰好是"采样之后还差多少"，也就是密度该乘的倍数：
        // 采样先砍到 1/sample，密度再乘 sample*exact，净效果刚好是 exact。
        val densityScale = sample * exact
        if (densityScale < 1f) {
            options.inScaled = true
            options.inDensity = DENSITY_BASE
            options.inTargetDensity = (DENSITY_BASE * densityScale + 0.5f).toInt()
        }
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale: Float
        val dx: Float
        val dy: Float
        if (source.width * height > width * source.height) {
            scale = height.toFloat() / source.height
            dx = (width - source.width * scale) / 2f
            dy = 0f
        } else {
            scale = width.toFloat() / source.width
            dx = 0f
            dy = (height - source.height * scale) / 2f
        }
        val out = Bitmap.createBitmap(width, height, configOf(source))
        out.density = source.density
        Canvas(out).drawBitmap(source, Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        if (out !== source) source.recycle()
        return out
    }

    private fun rounded(source: Bitmap, radiusPx: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, configOf(source))
        out.density = source.density
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                radiusPx.toFloat(), radiusPx.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (out !== source) source.recycle()
        return out
    }
}
