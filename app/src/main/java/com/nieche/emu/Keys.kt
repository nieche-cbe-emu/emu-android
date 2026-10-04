package com.nieche.emu

/**
 * 手机键 -> 32 位掩码。
 *
 * 这张表和桌面版 swiftapp/Keypad.swift 里的默认值是同一份，来源也一样：
 * 把全部模块跑一遍、记下它们传给 GAME_isKeyDown 的掩码反推出来的，
 * 结果全是成对的（方向键 或 对应数字键）。方向、中心键、软键比较可靠；
 * 数字 1/3/7/9/✱/0/# 没有直接证据，是顺排的猜测。
 *
 * 注意 bit13 是**挂断键**不是右软键——13 个模块都查它，按下去游戏会退出。
 * 右软键是两段式的，所以它在表里是 0，走 soft_key（和另外两端一致）。
 */
object Keys {
    val mask: Map<String, Int> = mapOf(
        "lsk" to (1 shl 12),
        // **右软键不是一个位。** 场景里右下角那个「任务」／「商城」是游戏自己画的
        // 触摸按钮，没有按键入口；菜单里的「返回」才吃 bit13。核心做成两段式
        // （先戳角落、几帧后画面没动才补发按键位），这里走 soft_key，不发位。
        "rsk" to 0,
        "call" to (1 shl 20),
        // bit13 是挂断／返回：13 个模块都查它，按下去游戏会退出
        "end" to (1 shl 13),
        "up" to ((1 shl 2) or (1 shl 17)),
        "down" to ((1 shl 8) or (1 shl 18)),
        "left" to ((1 shl 4) or (1 shl 15)),
        "right" to ((1 shl 6) or (1 shl 16)),
        "ok" to ((1 shl 5) or (1 shl 14)),
        "k1" to (1 shl 19), "k2" to (1 shl 18), "k3" to (1 shl 20),
        "k4" to (1 shl 15), "k5" to (1 shl 14), "k6" to (1 shl 16),
        "k7" to (1 shl 21), "k8" to (1 shl 17), "k9" to (1 shl 22),
        "star" to (1 shl 23), "k0" to (1 shl 24), "pound" to (1 shl 25),
    )

    val label: Map<String, String> = mapOf(
        "lsk" to "左软键", "rsk" to "右软键", "call" to "呼叫", "end" to "挂断",
        "up" to "▲", "down" to "▼", "left" to "◀", "right" to "▶", "ok" to "OK",
        "k1" to "1", "k2" to "2", "k3" to "3", "k4" to "4", "k5" to "5",
        "k6" to "6", "k7" to "7", "k8" to "8", "k9" to "9",
        "star" to "✱", "k0" to "0", "pound" to "#",
    )
}
