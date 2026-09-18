package courseclock.timetable.schedule_import

import courseclock.timetable.schedule_import.bean.WeekBean

object Common {

    const val TYPE_SUES = "sues" // 上海工程技术大学，走 WebVPN 登录后从课表接口取数

    /**
     * 上海工程技术大学 WebVPN 入口。
     *
     * 校内服务（jxfw.sues.edu.cn）只在校园网内可达，公网访问不到；从这里进去，
     * 由 WebVPN 网关带着统一身份认证的会话去访问校内地址。登录、人机验证、进入课表
     * 页面都由用户自己在 WebView 里完成，App 只负责拉起这个入口和最后取数。
     */
    const val SUES_WEBVPN_URL = "https://webvpn.sues.edu.cn"

    fun weekIntList2WeekBeanList(input: MutableList<Int>): MutableList<WeekBean> {
        var reset = 0
        var temp = WeekBean(0, 0, -1)
        val list = arrayListOf<WeekBean>()
        for (i in input.indices) {
            if (reset == 1) {
                list.add(temp)
                temp = WeekBean(0, 0, -1)
                reset = 0
            }
            if (i < input.size - 1) {
                if (temp.type == 0 && input[i + 1] - input[i] == 1) temp.end = input[i + 1]
                else if ((temp.type == 1 || temp.type == 2) && input[i + 1] - input[i] == 2)
                    temp.end = input[i + 1]
                else if (temp.type != -1) {
                    reset = 1
                }
            }
            if (i < input.size - 1 && temp.type == -1) {
                temp.start = input[i]
                when (input[i + 1] - input[i]) {
                    1 -> {
                        temp.type = 0
                        temp.end = input[i + 1]
                    }
                    2 -> {
                        temp.type = if (input[i] % 2 != 0) 1 else 2
                        temp.end = input[i + 1]
                    }
                    else -> {
                        temp.end = input[i]
                        temp.type = 0
                        reset = 1
                    }
                }
            }
            if (i == input.size - 1 && temp.type != -1) list.add(temp)
            if (i == input.size - 1 && temp.type == -1) {
                temp.start = input[i]
                temp.end = input[i]
                temp.type = 0
                list.add(temp)
            }
        }
        return list
    }

}
