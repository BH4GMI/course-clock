package courseclock.timetable.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.BaseDialogFragment
import androidx.fragment.app.activityViewModels
import courseclock.timetable.R
import courseclock.timetable.databinding.FragmentSelectTimeDetailBinding
import courseclock.timetable.utils.CourseUtils
import com.google.android.material.card.MaterialCardView

class SelectTimeDetailFragment : BaseDialogFragment() {
    override val layoutId: Int
        get() = R.layout.fragment_select_time_detail

    private var _binding: FragmentSelectTimeDetailBinding? = null
    private val binding get() = _binding!!

    var position = -1
    var tablePosition = 0
    private val viewModel by activityViewModels<TimeSettingsViewModel>()
    private var mListener: DialogResultListener? = null

    fun setListener(listener: DialogResultListener) {
        mListener = listener
    }

    interface DialogResultListener {
        fun refreshTimeResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            position = it.getInt("position")
            tablePosition = it.getInt("tablePosition")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSelectTimeDetailBinding.bind(view.findViewById<MaterialCardView>(R.id.base_card_view).getChildAt(0))
        val valueArray = viewModel.timeSelectList.toTypedArray()

        binding.wpStart.displayedValues = valueArray
        binding.wpStart.minValue = 0
        binding.wpStart.maxValue = valueArray.size - 1

        binding.wpEnd.displayedValues = valueArray
        binding.wpEnd.minValue = 0
        binding.wpEnd.maxValue = valueArray.size - 1

        if (viewModel.timeTableList[tablePosition].sameLen) {
            binding.tvTitle.text = "请选择开始时间"
            binding.wpEnd.visibility = View.GONE
        } else {
            binding.tvTitle.text = "请选择时间"
            binding.wpEnd.visibility = View.VISIBLE
        }
        initEvent()
    }

    private fun initEvent() {
        var startIndex: Int
        var endIndex: Int
        startIndex = viewModel.timeSelectList.indexOf(viewModel.timeList[position].startTime)
        endIndex = viewModel.timeSelectList.indexOf(viewModel.timeList[position].endTime)
        if (startIndex < 0) {
            startIndex = 0
        }
        if (endIndex < 0) {
            endIndex = 0
        }

        binding.wpStart.value = startIndex
        binding.wpEnd.value = endIndex

        binding.wpStart.setOnValueChangedListener { _, _, newVal ->
            startIndex = newVal
            if (endIndex < startIndex) {
                binding.wpEnd.smoothScrollToValue(startIndex, false)
                endIndex = startIndex
            }
        }
        binding.wpEnd.setOnValueChangedListener { _, _, newVal ->
            endIndex = newVal
            if (endIndex < startIndex) {
                binding.wpEnd.smoothScrollToValue(startIndex, false)
                endIndex = startIndex
            }
        }

        binding.btnSave.setOnClickListener {
            val startStr = viewModel.timeSelectList[startIndex]
            viewModel.timeList[position].startTime = startStr
            if (viewModel.timeTableList[tablePosition].sameLen) {
                viewModel.timeList[position].endTime = CourseUtils.calAfterTime(startStr, viewModel.timeTableList[tablePosition].courseLen)
            } else {
                viewModel.timeList[position].endTime = viewModel.timeSelectList[endIndex]
            }
            mListener?.refreshTimeResult()
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(arg0: Int, arg1: Int) =
                SelectTimeDetailFragment().apply {
                    arguments = Bundle().apply {
                        putInt("position", arg1)
                        putInt("tablePosition", arg0)
                    }
                }
    }
}
