package courseclock.timetable.schedule

import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.setMargins
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import courseclock.timetable.R
import courseclock.timetable.base_view.Ui
import courseclock.timetable.utils.Const
import courseclock.timetable.utils.ViewUtils
import courseclock.timetable.utils.ViewUtils.getStatusBarHeight
import courseclock.timetable.utils.getPrefer
import splitties.dimensions.dip
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.colorSL
import splitties.resources.styledColor

class ScheduleActivityUI(override val ctx: Context) : Ui {

    private val iconFont = ResourcesCompat.getFont(ctx, R.font.iconfont)

    /**
     * 顶部栏第一行的上沿。
     *
     * 只在状态栏高度上加 4dp：加 8dp 时本机实测整个头部（状态栏 160px + 8dp + 34dp 按钮行
     * + 周次行）吃掉 337px，第一节课要等到 y≈538px 才出现，占掉屏幕的五分之一。
     */
    private val statusBarMargin = getStatusBarHeight(ctx) + ctx.dip(4)
    /**
     * 顶栏图标按钮的圆形底（设计稿里的圆钮）。
     *
     * View 只有一个背景槽位，"圆底 + 按下水波纹"就必须合成一个 Drawable；原来是
     * selectableItemBackgroundBorderless，只有波纹没有底，而浅灰圆底是顶栏层次的一半。
     */
    private fun circleBackground(): Drawable {
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(ctx, R.color.card2_background))
        }
        return RippleDrawable(
                ColorStateList.valueOf(ctx.styledColor(android.R.attr.colorControlHighlight)), oval, null)
    }

    val viewPager: ViewPager = ViewPager(ctx).apply {
        id = R.id.anko_vp_schedule
    }

    val bg = AppCompatImageView(ctx).apply {
        id = R.id.anko_iv_bg
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** 顶栏副标题（日期 · 周几 · 第几周）的字号。这三段原来散在两行、字号还都不一样。 */
    private val subtitleTextSize = 12f

    /**
     * 顶栏大标题：这一屏叫什么。
     *
     * 原版顶栏最大的一行是"今天的日期"（20sp），于是"这是哪一屏"和"这是哪一天"挤在一起；
     * 现在标题固定是「课表」，日期降成副标题的第一段。
     */
    val titleView = AppCompatTextView(ctx).apply {
        id = R.id.anko_tv_title
        setText(R.string.schedule_title)
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    /** 副标题第一段：今天的日期（"9月15日"）。 */
    val dateView = AppCompatTextView(ctx).apply {
        id = R.id.anko_tv_date
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    /** 副标题第三段：第几周。 */
    val weekView = AppCompatTextView(ctx).apply {
        id = R.id.anko_tv_week
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    /** 副标题第二段：周几（看别的周时这里是"非本周"）。仍然可点，点一下回到本周。 */
    val weekDayView = AppCompatTextView(ctx).apply {
        id = R.id.anko_tv_weekday
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    /**
     * 抽屉入口（三条杠）。
     *
     * 是 ImageView 而不是像另外四个按钮那样用 iconfont 的 TextView：三条杠的长度由矢量图
     * 决定，放大 textSize 只会让那个小字形整体变大（本机实测 24f 时三条杠仍只有 23px 长），
     * 见 `drawable/ic_menu.xml` 的说明。
     */
    val navBtn = AppCompatImageView(ctx).apply {
        id = R.id.anko_ib_nav
        setImageResource(R.drawable.ic_menu)
        // 内缩 4dp 后绘制区正好 24dp，CENTER_INSIDE 不再放大，三条杠就是 18dp。
        setPadding(dip(4), dip(4), dip(4), dip(4))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = circleBackground()
    }

    /**
     * 主操作：添加课程。
     *
     * 从顶栏挪到右下角（设计稿的加号），沿用原版的写法：iconfont 字形 + 自己画的圆底，
     * 不引入 FloatingActionButton —— 这一屏的按钮本来就是这么拼的，混两套写法反而难维护。
     * 圆形底色用主题品牌色，字形用 colorOnPrimary（深色模式下也是它）。
     */
    val addBtn = AppCompatTextView(ctx).apply {
        id = R.id.anko_ib_add
        text = "\uE6DC"
        textSize = 26f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = iconFont
        setTextColor(ctx.styledColor(R.attr.colorOnPrimary))
        elevation = dp(6)
        background = RippleDrawable(
                ColorStateList.valueOf(ctx.styledColor(android.R.attr.colorControlHighlight)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ctx.color(R.color.colorPrimary))
                }, null)
    }

    val importBtn = AppCompatTextView(ctx).apply {
        id = R.id.anko_ib_import
        text = "\uE6E2"
        background = circleBackground()
        textSize = 20f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = iconFont
    }

    val shareBtn = AppCompatTextView(ctx).apply {
        id = R.id.anko_ib_share
        text = "\uE6BA"
        background = circleBackground()
        textSize = 20f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = iconFont
    }

    val moreBtn = AppCompatTextView(ctx).apply {
        id = R.id.anko_ib_more
        text = "\uE6BF"
        background = circleBackground()
        textSize = 20f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = iconFont
    }

    val content = ConstraintLayout(ctx).apply {
        id = R.id.anko_cl_schedule
        // 主界面默认背景 = 纯色（浅色纯白 / 深色纯黑，见 values 与 values-night 里的
        // main_background）。bg 是铺满它的第一个子 view：用户设了背景图时被图盖住，
        // 没设时露出这个颜色。所以"默认背景"不再需要任何图片资源。
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.main_background))
        addView(bg, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT).apply {
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
            topToTop = PARENT_ID
            bottomToBottom = PARENT_ID
        })

        // 顶栏：抽屉入口 + 大标题「课表」/副标题「9月15日 周一 · 第 3 周」+ 右侧三个圆钮。
        // 副标题三段排成一行，日期在最前（设计稿的顺序），依次向右接。
        addView(titleView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.anko_ib_nav
            topToTop = PARENT_ID
            topMargin = statusBarMargin
            marginStart = dip(8)
        })

        addView(dateView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = R.id.anko_tv_title
            topToBottom = R.id.anko_tv_title
            topMargin = dip(2)
        })

        addView(weekDayView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.anko_tv_date
            topToBottom = R.id.anko_tv_title
            topMargin = dip(2)
            marginStart = dip(6)
        })

        addView(weekView, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.anko_tv_weekday
            topToBottom = R.id.anko_tv_title
            topMargin = dip(2)
            marginStart = dip(6)
        })

        // 三个圆钮 36dp 见方（设计稿的圆钮直径），右起：更多 ← 分享 ← 导入。
        addView(navBtn, ConstraintLayout.LayoutParams(dip(36), dip(36)).apply {
            startToStart = PARENT_ID
            topToTop = PARENT_ID
            marginStart = dip(8)
            topMargin = statusBarMargin
        })

        addView(importBtn, ConstraintLayout.LayoutParams(dip(36), dip(36)).apply {
            topMargin = statusBarMargin
            endToStart = R.id.anko_ib_share
            topToTop = PARENT_ID
        })

        addView(shareBtn, ConstraintLayout.LayoutParams(dip(36), dip(36)).apply {
            topMargin = statusBarMargin
            endToStart = R.id.anko_ib_more
            topToTop = PARENT_ID
        })

        addView(moreBtn, ConstraintLayout.LayoutParams(dip(36), dip(36)).apply {
            topMargin = statusBarMargin
            marginEnd = dip(8)
            endToEnd = PARENT_ID
            topToTop = PARENT_ID
        })

        addView(viewPager, ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT).apply {
            topToBottom = R.id.anko_tv_week
            bottomToBottom = PARENT_ID
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
        })

        // 加号放在最后添加：它要盖在课表上面，先加就被课表画过去了。
        addView(addBtn, ConstraintLayout.LayoutParams(dip(56), dip(56)).apply {
            endToEnd = PARENT_ID
            bottomToBottom = PARENT_ID
            marginEnd = dip(20)
            // 与底部弹窗同一套让位规则：铺满屏（隐藏导航栏）时把加号抬到虚拟导航栏之上。
            bottomMargin = dip(24) + if (ctx.getPrefer().getBoolean(Const.KEY_HIDE_NAV_BAR, false)) {
                ViewUtils.getVirtualBarHeight(ctx)
            } else {
                0
            }
        })

    }

    // ---- 侧栏抽屉 ----------------------------------------------------------
    //
    // 抽屉不再用 NavigationView：它的行样式被 Material 定死（纯图标 + 文字，行高/内边距都不受控），
    // 而这一屏要的是「当前项蓝底胶囊 + 32dp 圆形图标底 + 56dp 行高」。行改由这里自己搭 ——
    // 顶栏、底部面板本来也是这么搭的，同一套写法。菜单 XML（res/menu/main_navigation_menu.xml）
    // 随之撤掉。

    /** 抽屉宽 272dp（设计稿）：它是一张从 start 侧滑出的卡，铺满屏幕会让人以为这是一级页面。 */
    private val drawerWidth = ctx.dip(272)

    /** 抽屉 end 侧两角的圆角（设计稿 24dp）。 */
    private val drawerCornerRadius = ctx.dip(24)

    /** 行高 56dp（与设置页的行同一个 token）。 */
    private val navRowHeight = ctx.dip(56)

    /** 行内图标底 32dp 直径、行本身的 16dp 圆角。 */
    private val navIconSize = ctx.dip(32)
    private val navRowRadius = ctx.dip(16)

    /** 行到抽屉两边的距离（16dp）；行内再留 16dp，图标底就落在设计稿的位置上。 */
    private val navRowInset = ctx.dip(16)

    /**
     * 抽屉的卡片底：card_background + end 侧两角 24dp 圆角。
     *
     * 圆角只给 end 侧：另一侧贴着屏幕边缘，那一侧再圆角会在里面露出屏幕外的空白。
     * supportsRtl="true" 且这一层用的是 gravity = START（解析结果跟着布局方向走），
     * 所以 RTL 下圆角要换到左边 —— 写死右边会在阿拉伯语环境里变成"外侧直角、内侧圆角"。
     */
    private fun drawerBackground(): Drawable {
        val r = drawerCornerRadius.toFloat()
        // cornerRadii 的顺序是「左上、右上、右下、左下」，每个角 x/y 两个值。
        val radii = if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ctx.color(R.color.card_background))
            cornerRadii = radii
        }
    }

    /** 图标底：当前项用浅强调色，其余用次级容器色（设计稿的 accent-soft / card2）。 */
    private fun navIconBackground(current: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ctx.color(if (current) R.color.accent_soft else R.color.card2_background))
    }

    /** 图标颜色：当前项与它那一行文字同色（品牌色），其余是次文字色（设计稿的灰色线性图标）。 */
    private fun navIconColor(current: Boolean): Int =
            ctx.color(if (current) R.color.colorPrimary else R.color.text_secondary)

    /** iconfont 字形做的图标（这一屏的图标都是这么写的，见 [addBtn]）。 */
    private fun navGlyphIcon(glyph: String, current: Boolean): AppCompatTextView =
            AppCompatTextView(ctx).apply {
                text = glyph
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = iconFont
                setTextColor(navIconColor(current))
                background = navIconBackground(current)
            }

    /**
     * 矢量图做的图标（设置 / 关于，两份矢量图本来就是给这个抽屉画的）。
     *
     * 内缩 4dp：24dp 的图形正好落在 32dp 圆底中间。必须染色 —— 这两份矢量图自带深靛蓝
     * （见 drawable/setting.xml 的注释），不染色在深色模式下就是深色底上的深色图标。
     */
    private fun navImageIcon(resId: Int, current: Boolean): AppCompatImageView =
            AppCompatImageView(ctx).apply {
                setImageResource(resId)
                imageTintList = ColorStateList.valueOf(navIconColor(current))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dip(4), dip(4), dip(4), dip(4))
                background = navIconBackground(current)
            }

    /**
     * 行形状的水波纹掩膜。
     *
     * 掩膜色只用到 alpha 通道：写白色才是"整个圆角矩形都能出水波纹"，写成透明就什么都看不到。
     * 抽出来是因为抽屉底部那一行「关于」也要同一个形状的波纹（见 [navRowAbout]）。
     */
    private fun navRowMask(): GradientDrawable {
        val r = navRowRadius.toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
            setColor(Color.WHITE)
        }
    }

    /**
     * 行的底：当前项是浅强调色胶囊，其余行透明 —— 两种都带一圈同形状的水波纹。
     */
    private fun navRowBackground(current: Boolean): Drawable {
        val r = navRowRadius.toFloat()
        val radii = floatArrayOf(r, r, r, r, r, r, r, r)
        val pill = if (current) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(ctx.color(R.color.accent_soft))
            }
        } else {
            null
        }
        return RippleDrawable(
                ColorStateList.valueOf(ctx.styledColor(android.R.attr.colorControlHighlight)), pill, navRowMask())
    }

    /**
     * 抽屉里的一行：32dp 圆形图标底 + 15sp 文字。
     *
     * 文字色走主题（?attr/colorOnBackground）而不是课表那套 scheduleTextColor：抽屉底是卡片色，
     * 与用户设的课表背景图无关，跟主题走才在两套主题下都成立。
     */
    private fun navRow(id: Int, label: String, current: Boolean, icon: View): LinearLayoutCompat =
            LinearLayoutCompat(ctx).apply {
                this.id = id
                orientation = LinearLayoutCompat.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = navRowBackground(current)
                setPaddingRelative(navRowInset, 0, navRowInset, 0)
                addView(icon, LinearLayoutCompat.LayoutParams(navIconSize, navIconSize))
                addView(AppCompatTextView(ctx).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(if (current) ctx.color(R.color.colorPrimary)
                            else ctx.styledColor(R.attr.colorOnBackground))
                    if (current) typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayoutCompat.LayoutParams(
                        LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                        LinearLayoutCompat.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dip(12)
                })
                // 行与行之间留 4dp（设计稿里两行圆心相距 60dp、行高 56dp）。
                layoutParams = LinearLayoutCompat.LayoutParams(
                        LinearLayoutCompat.LayoutParams.MATCH_PARENT, navRowHeight).apply {
                    marginStart = navRowInset
                    marginEnd = navRowInset
                    bottomMargin = dip(4)
                }
            }

    /**
     * 抽屉头部：应用名 + 一句说明，[navHeaderImage] 盖在最上层铺用户的背景图。
     *
     * 顶部留白按**实际状态栏高度**补：抽屉铺满整个屏幕高（含状态栏那一块），
     * 而这份高度只有运行时才知道（见 nav_header.xml 的说明）。
     * 左右/下内边距仍由 XML 持有，一个值只在一个地方定义。
     */
    private val navHeader: View =
            LayoutInflater.from(ctx).inflate(R.layout.nav_header, null, false).apply {
                findViewById<AppCompatTextView>(R.id.nav_header_subtitle).text = "课程表 · 上课提醒"
                val titles = findViewById<View>(R.id.nav_header_titles)
                titles.setPaddingRelative(titles.paddingStart, getStatusBarHeight(ctx) + dip(24),
                        titles.paddingEnd, titles.paddingBottom)
            }

    /** 用户设置的侧栏背景图（在「课表设置 → 课程表背景」里换），没设时是空的，露出卡片色。 */
    val navHeaderImage: AppCompatImageView = navHeader.findViewById(R.id.iv_header)

    /** 「我的课表」＝当前页：高亮着，点它只把抽屉收起来。 */
    val navRowSchedule: View = navRow(R.id.nav_row_schedule, "我的课表", current = true,
            icon = navGlyphIcon("\uE6C1", current = true))

    val navRowCourse: View = navRow(R.id.nav_row_course, "课程管理", current = false,
            icon = navGlyphIcon("\uE6DC", current = false))

    val navRowSetting: View = navRow(R.id.nav_row_setting, "设置", current = false,
            icon = navImageIcon(R.drawable.setting, current = false))

    /**
     * 抽屉最下面的一行「关于」：它不是导航项，是这一页的页脚。
     *
     * 上面三行走的是"导航行"那套语言 —— 32dp 圆底图标、15sp 文字、当前项的强调色胶囊。
     * 那套语言回答的是"现在在哪、还能去哪"，而「关于」既不是目的地也不是状态；把它摆在同一个
     * 语言里，用户会读成与上面三行平级的第四项。所以页脚只留：一枚 16dp 的线性图标（去掉圆底、
     * 也不再有 32dp 的色块）+ 一行 13sp 次要文字色的文字，居中，行高 48dp（导航行是 56dp）。
     *
     * 水波纹保留：它可点，按下去必须有回执，这一条与抽屉其它行同口径。
     */
    val navRowAbout: View = LinearLayoutCompat(ctx).apply {
        id = R.id.nav_row_about
        orientation = LinearLayoutCompat.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
                ColorStateList.valueOf(ctx.styledColor(android.R.attr.colorControlHighlight)),
                null, navRowMask())
        addView(AppCompatImageView(ctx).apply {
            setImageResource(R.drawable.about)
            // 与文字同色：这一页的图标都是"跟随主题染色"的，不染色在深色模式下是深色底上的深色图标。
            imageTintList = ColorStateList.valueOf(ctx.color(R.color.text_secondary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayoutCompat.LayoutParams(dip(16), dip(16)).apply {
            marginEnd = dip(6)
        })
        addView(AppCompatTextView(ctx).apply {
            text = "关于"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ctx.color(R.color.text_secondary))
            includeFontPadding = false
            gravity = Gravity.CENTER
        }, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, dip(48)).apply {
            bottomMargin = dip(12)
        }
    }

    /**
     * 页脚上方的一条细线：把三行导航项和页脚的「关于」分开。
     *
     * 抽屉里原先那条分隔线是拿来分「去哪儿」和「新建课表」的，随「新建课表」一并撤掉了；
     * 现在这条只服务页脚，所以贴着页脚（下方 8dp），不是悬在两组导航项中间。
     */
    private val navFooterDivider: View = View(ctx).apply {
        setBackgroundColor(ctx.color(R.color.list_divider))
    }

    /** 占住剩余高度：把三行导航项顶到上方之后，「关于」自然落在抽屉最下方。 */
    private val navFooterSpacer: View = View(ctx)

    /**
     * 侧栏抽屉本身（272dp 的圆角卡片）。
     *
     * 头部原先底下还挂着版本号与开源协议，已撤掉：那是开发信息，署名在「关于」页里已经有了。
     */
    val navViewStart = LinearLayoutCompat(ctx).apply {
        id = R.id.anko_nv
        orientation = LinearLayoutCompat.VERTICAL
        background = drawerBackground()
        // 铺满屏幕高度（含状态栏区域）：内容自己让开状态栏（见 navHeader 的上内边距）。
        // 交给系统加内边距，顶部会留出一条露出课表的缝。
        fitsSystemWindows = false
        // 圆角卡片贴在遮罩上：没有影子，24dp 的圆角看起来就是被切掉的一块色块。
        elevation = dp(8)

        addView(navHeader, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT))
        addView(navRowSchedule)
        addView(navRowCourse)
        addView(navRowSetting)
        // 三行导航项之后是弹簧 + 页脚：「关于」固定在抽屉最下方，不跟着导航项一起往上挤。
        addView(navFooterSpacer, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(navFooterDivider, LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT, dip(1)).apply {
            marginStart = dip(24)
            marginEnd = dip(24)
            bottomMargin = dip(8)
        })
        addView(navRowAbout)
    }

    val rvTableName = RecyclerView(ctx).apply {
        id = R.id.bottom_sheet_rv_table
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutManager = LinearLayoutManager(context).apply {
            orientation = RecyclerView.HORIZONTAL
        }
    }

    val drawerLayout = DrawerLayout(ctx).apply {
        id = R.id.anko_drawer_layout
        addView(content, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT))

        addView(navViewStart, DrawerLayout.LayoutParams(drawerWidth,
                DrawerLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = GravityCompat.START
        })
    }

    val changeWeekBtn = createTextButton().apply {
        id = R.id.bottom_sheet_change_week_btn
        text = "修改当前周"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val createScheduleBtn = createTextButton().apply {
        id = R.id.bottom_sheet_create_schedule_btn
        text = "新建课表"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val manageScheduleBtn = createTextButton().apply {
        id = R.id.bottom_sheet_manage_schedule_btn
        text = "管理"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val weekToggleGroup = MaterialButtonToggleGroup(ctx).apply {
        id = R.id.bottom_sheet_cg_week
        isSingleSelection = true
        isSelectionRequired = true
    }

    val weekScrollView = HorizontalScrollView(ctx).apply {
        id = R.id.bottom_sheet_sv_week
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        addView(weekToggleGroup)
    }

    val timeBtn = createTextButton().apply {
        id = R.id.bottom_sheet_modify_time_btn
        text = "上课时间"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val changeBgBtn = createTextButton().apply {
        id = R.id.bottom_sheet_bg_btn
        text = "更换背景"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val courseBtn = createTextButton().apply {
        id = R.id.bottom_sheet_check_course_btn
        text = "已添课程"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    /**
     * 「捷径」行里的上课提醒入口。
     *
     * 为什么要单独一个入口：提醒原先唯一的开关在设置页，而那个开关还被"必须先往桌面放一个
     * 日视图小部件"卡着 —— 用户不想要小部件，就既找不到、也打不开提醒。提醒与桌面放不放
     * 东西毫无关系（详见 `CourseReminderNotifier` 的类文档），所以它需要一个不经过小部件的
     * 入口，且要落在用户真正会看的地方。这一行本来就是「捷径」，上课时间/更换背景都在这里。
     */
    val remindBtn = createTextButton().apply {
        id = R.id.bottom_sheet_remind_btn
        text = "上课提醒"
        minWidth = 0
        minimumWidth = 0
        textSize = 12f
    }

    val cardContent = ConstraintLayout(ctx).apply {
        val space = dip(16)
        setPadding(space, 0, space, 0)
        isMotionEventSplittingEnabled = false
        addView(AppCompatTextView(context).apply {
            id = R.id.bottom_sheet_title_week
            text = "周数"
            textSize = 12f
        }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            topToTop = PARENT_ID
            topMargin = dip(16)
        })
        addView(changeWeekBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            endToEnd = PARENT_ID
            topToTop = R.id.bottom_sheet_title_week
            bottomToBottom = R.id.bottom_sheet_title_week
        })
        addView(weekScrollView, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
            topToBottom = R.id.bottom_sheet_title_week
            topMargin = dip(8)
        })
        addView(AppCompatTextView(context).apply {
            id = R.id.bottom_sheet_title_schedule
            text = "多课表"
            textSize = 12f
        }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            topToBottom = R.id.bottom_sheet_sv_week
            topMargin = dip(8)
        })
        addView(rvTableName, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
            topToBottom = R.id.bottom_sheet_title_schedule
            topMargin = dip(16)
        })
        addView(manageScheduleBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            endToEnd = PARENT_ID
            topToTop = R.id.bottom_sheet_title_schedule
            bottomToBottom = R.id.bottom_sheet_title_schedule
        })
        addView(createScheduleBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            endToStart = R.id.bottom_sheet_manage_schedule_btn
            topToTop = R.id.bottom_sheet_title_schedule
            bottomToBottom = R.id.bottom_sheet_title_schedule
        })
        addView(AppCompatTextView(context).apply {
            id = R.id.bottom_sheet_title_shortcut
            text = "捷径"
            textSize = 12f
        }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            topToBottom = R.id.bottom_sheet_rv_table
            topMargin = dip(16)
        })
        addView(timeBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = PARENT_ID
            topToBottom = R.id.bottom_sheet_title_shortcut
            endToStart = R.id.bottom_sheet_bg_btn
        })
        addView(changeBgBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.bottom_sheet_modify_time_btn
            topToBottom = R.id.bottom_sheet_title_shortcut
            endToStart = R.id.bottom_sheet_remind_btn
        })
        // 上课提醒入口插在「更换背景」与「已添课程」之间：这一行四个按钮首尾都要有约束，
        // 链中间的一个悬空就会让整行挤到屏幕外（这一行早先因此出过按钮跑到 −60px 的问题）。
        addView(remindBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.bottom_sheet_bg_btn
            topToBottom = R.id.bottom_sheet_title_shortcut
            endToStart = R.id.bottom_sheet_check_course_btn
        })
        addView(courseBtn, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToEnd = R.id.bottom_sheet_remind_btn
            topToBottom = R.id.bottom_sheet_title_shortcut
            // 捷径这一行由 timeBtn 起、courseBtn 止，两端都钉在父容器上，链尾不会悬空。
            endToEnd = PARENT_ID
        })
    }

    val bottomSheet = FrameLayout(ctx).apply {
        addView(MaterialCardView(context).apply {
            setCardBackgroundColor(styledColor(R.attr.colorSurface))
            cardElevation = dp(8)
            addView(cardContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dip(320)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dip(16))
            if (context.getPrefer().getBoolean(Const.KEY_HIDE_NAV_BAR, false)) {
                bottomMargin = dip(16) + ViewUtils.getVirtualBarHeight(ctx)
            }
        })
    }

    override val root = CoordinatorLayout(ctx).apply {

        addView(drawerLayout, CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT)
        )

        addView(bottomSheet, CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                ViewUtils.getScreenInfo(context)[1]).apply {
            behavior = BottomSheetBehavior<FrameLayout>(ctx, null).apply {
                isHideable = true
                peekHeight = 0
            }
        })

    }

    fun createTextButton() = MaterialButton(ctx).apply {
        setTextColor(colorSL(R.color.mtrl_text_btn_text_color_selector))
        val space = dip(8)
        setPadding(space, 0, space, 0)
        backgroundTintList = colorSL(R.color.mtrl_btn_text_btn_bg_color_selector)
        rippleColor = colorSL(R.color.mtrl_btn_text_btn_ripple_color)
        elevation = 0f
        stateListAnimator = StateListAnimator()
    }

    fun createOutlineButton() = createTextButton().apply {
        strokeColor = colorSL(R.color.mtrl_btn_stroke_color_selector)
        strokeWidth = dip(1)
    }

}
