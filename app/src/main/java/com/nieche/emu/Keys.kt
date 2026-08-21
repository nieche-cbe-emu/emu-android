package com.nieche.emu

object Keys {
    val mask: Map<String, Int> = mapOf(
        "lsk" to (1 shl 12),
        "rsk" to (1 shl 13),
        "call" to (1 shl 20),
        "end" to 0,
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
