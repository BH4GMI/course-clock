package courseclock.timetable.today_appwidget

/**
 * 2×2「当天课程（小）」——设计稿三形态里的**小正方形**。
 *
 * 它没有自己的逻辑，一行都没有：内容、状态口径、按实测尺寸选形态全部继承
 * [TodayCourseAppWidget]，画面也全在 [TodayColorfulService] 里按可用宽高决定。
 *
 * ## 为什么必须有这个"空类"
 *
 * 桌面按 **provider** 决定"添加小部件时摆多大"（`targetCellWidth/targetCellHeight` 与
 * `minWidth/minHeight`），而**一个 provider 只有一个默认尺寸**；同一个
 * `<receiver android:name>` 又不能在清单里出现两次。所以想让"添加小部件"列表里多出一个
 * 2×2 的入口，就必须是另一个 provider —— 这个类承担的就是这个身份。
 *
 * ## 广播为什么仍然发给父类
 *
 * 导航按钮的 `PendingIntent` 指向 [TodayCourseAppWidget]，本类的实例上按下去，收到的也是
 * 父类的 `onReceive`，这没有问题：刷新走
 * `AppWidgetUtils.refreshTodayWidgets(widgetId = id)`，按**实例 id** 重画，
 * 与实例属于哪个 provider 无关。实例枚举则两个 provider 都要列上，漏一个就会出现
 * "改了课表只有大的那个刷新、小的还挂着旧课"。
 */
class SmallTodayCourseAppWidget : TodayCourseAppWidget()
