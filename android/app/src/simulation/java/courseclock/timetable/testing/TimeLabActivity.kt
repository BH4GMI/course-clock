package courseclock.timetable.testing

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import courseclock.timetable.base_view.BaseActivity
import courseclock.timetable.schedule.ScheduleActivity
import courseclock.timetable.utils.CourseClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TimeLabActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var date: Button
    private lateinit var time: Button
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: ImageButton
    private lateinit var speed: Spinner
    private val controls = mutableListOf<View>()
    private var dragging = false
    private var dragDay = 0L
    private val speeds = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0)
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), getStatusBarHeight() + dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(body) })
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(12), 0, dp(12))
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label("时间实验室", 24f)
        status = label("真实时间")
        date = Button(this).apply {
            contentDescription = "选择测试日期"
            setOnClickListener { chooseDate() }
        }
        body.addView(date, LinearLayout.LayoutParams(-1, dp(56)))
        time = Button(this).apply {
            textSize = 28f
            contentDescription = "输入测试时间"
            setOnClickListener { chooseTime() }
        }
        body.addView(time, LinearLayout.LayoutParams(-1, dp(72)))
        val endpoints = LinearLayout(this)
        endpoints.addView(TextView(this).apply { text = "00:00" }, LinearLayout.LayoutParams(0, -2, 1f))
        endpoints.addView(TextView(this).apply { text = "23:59"; gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(endpoints)
        seek = SeekBar(this).apply {
            max = 86399
            contentDescription = "全天测试时间"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                    dragDay = CourseClock.nowMillis()
                }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        if (!dragging) dragDay = CourseClock.nowMillis()
                        displayTime(timeAt(dragDay, value))
                        if (!dragging) CourseClock.seek(timeAt(dragDay, value))
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    CourseClock.seek(timeAt(dragDay, bar.progress))
                    render()
                }
            })
        }
        body.addView(seek, LinearLayout.LayoutParams(-1, dp(56)))
        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER }
        body.addView(transport, LinearLayout.LayoutParams(-1, dp(64)))
        fun icon(resource: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
            setImageResource(resource)
            contentDescription = description
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = description
            setOnClickListener { action(); render() }
            transport.addView(this, LinearLayout.LayoutParams(dp(64), dp(56)).apply { marginEnd = dp(8) })
            controls += this
        }
        icon(android.R.drawable.ic_media_rew, "后退一分钟") { CourseClock.seek(CourseClock.nowMillis() - 60_000) }
        play = icon(android.R.drawable.ic_media_play, "播放") { CourseClock.playPause() }
        icon(android.R.drawable.ic_media_ff, "前进一分钟") { CourseClock.seek(CourseClock.nowMillis() + 60_000) }
        label("倍速")
        speed = Spinner(this).apply {
            adapter = ArrayAdapter(this@TimeLabActivity, android.R.layout.simple_spinner_dropdown_item,
                    speeds.map { "${it.toString().removeSuffix(".0")} 倍" })
            setSelection(speeds.indexOf(CourseClock.selectedSpeed).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (CourseClock.selectedSpeed != speeds[position]) CourseClock.setSpeed(speeds[position])
                }
            }
        }
        body.addView(speed, LinearLayout.LayoutParams(-1, dp(56)))
        controls += listOf(date, time, seek, speed)
        body.addView(Button(this).apply {
            text = "查看课表"
            setOnClickListener { startActivity(Intent(this@TimeLabActivity, ScheduleActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        val reset = Button(this).apply {
            text = "恢复真实时间"
            setOnClickListener { CourseClock.restoreRealTime(); render() }
        }
        controls += reset
        body.addView(reset, LinearLayout.LayoutParams(-1, dp(56)))
        render()
    }

    private fun chooseDate() {
        val current = calendar()
        DatePickerDialog(this, { _, year, month, day ->
            current.set(year, month, day)
            CourseClock.seek(current.timeInMillis)
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun chooseTime() {
        val current = calendar()
        TimePickerDialog(this, { _, hour, minute ->
            current.set(Calendar.HOUR_OF_DAY, hour)
            current.set(Calendar.MINUTE, minute)
            current.set(Calendar.SECOND, 0)
            current.set(Calendar.MILLISECOND, 0)
            CourseClock.seek(current.timeInMillis)
        }, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE), true).show()
    }

    private fun calendar() = Calendar.getInstance().apply { timeInMillis = CourseClock.nowMillis() }

    private fun timeAt(day: Long, seconds: Int): Long = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, seconds / 3600)
        set(Calendar.MINUTE, seconds / 60 % 60)
        set(Calendar.SECOND, seconds % 60)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun displayTime(at: Long) {
        date.text = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.getDefault()).format(Date(at))
        time.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    private fun render() {
        status.text = CourseClock.message + if (CourseClock.playing) " · ${CourseClock.speed} 倍速" else ""
        controls.forEach { it.isEnabled = !CourseClock.applying }
        play.setImageResource(if (CourseClock.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        play.contentDescription = if (CourseClock.playing) "暂停" else "播放"
        if (!dragging) {
            displayTime(CourseClock.nowMillis())
            val calendar = calendar()
            seek.progress = calendar.get(Calendar.HOUR_OF_DAY) * 3600 + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
