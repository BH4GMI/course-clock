package courseclock.timetable.course_add

import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.widget.AppCompatEditText
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import courseclock.timetable.R
import courseclock.timetable.bean.CourseEditBean
import courseclock.timetable.schedule_import.Common
import courseclock.timetable.utils.CourseUtils

class AddCourseAdapter(layoutResId: Int, data: MutableList<CourseEditBean>) :
        BaseQuickAdapter<CourseEditBean, BaseViewHolder>(layoutResId, data) {

    private var mListener: OnItemEditTextChangedListener? = null

    fun setListener(listener: OnItemEditTextChangedListener) {
        mListener = listener
    }

    override fun convert(helper: BaseViewHolder, item: CourseEditBean?) {
        if (item == null) return

        // 周数 / 节次是只读值，选择面板保存后经 notifyItemChanged 回填。
        val week = Common.weekIntList2WeekBeanList(item.weekList.value!!).toString()
        helper.setText(R.id.et_weeks, week.substring(1, week.length - 1))
        helper.setText(R.id.et_time, "${CourseUtils.getDayStr(item.time.value!!.day)}    第${item.time.value!!.startNode} - ${item.time.value!!.endNode}节")

        // 教师 / 地点是行内输入框（设计稿 6：能直接打字）。重绑时**值没变就不 setText**：
        // EditText 的 TextWatcher 是常驻的，每次重绑都硬塞一遍会在光标位置上打架。
        bindEditText(helper, R.id.et_teacher, item.teacher, "teacher")
        bindEditText(helper, R.id.et_room, item.room, "room")

        // 删除只留给卡右上角的 ✕（ib_delete 的点击在 Activity 的 childClick 里）。
    }

    /** 回填 + 挂一次常驻 TextWatcher（tag 兜底，重绑不重复挂），改动实时写回 viewModel。 */
    private fun bindEditText(helper: BaseViewHolder, viewId: Int, value: String?, what: String) {
        val editText = helper.getView<AppCompatEditText>(viewId)
        val text = value ?: ""
        if (editText.text.toString() != text) {
            editText.setText(text)
            editText.setSelection(text.length)
        }
        if (editText.tag == null) {
            editText.tag = true
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    // layoutPosition 是 RecyclerView 的原始位置（头部基本信息卡占第 0 位），
                    // 必须减掉 headerLayoutCount 换算成数据位置，否则输入会写进**下一个**时间段。
                    val position = helper.layoutPosition - headerLayoutCount
                    if (position in data.indices) {
                        mListener?.onEditTextAfterTextChanged(
                                s ?: Editable.Factory.getInstance().newEditable(""), position, what)
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }

    interface OnItemEditTextChangedListener {
        fun onEditTextAfterTextChanged(editable: Editable, position: Int, what: String)
    }

    override fun onDetachedFromRecyclerView(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        mListener = null
    }

}
