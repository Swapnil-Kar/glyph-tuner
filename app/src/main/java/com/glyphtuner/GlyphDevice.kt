package com.glyphtuner

import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph

/** Which Nothing phone we're on, and how big its Glyph Matrix is. */
object GlyphDevice {
    fun id(): String = try {
        if (Common.is25111p()) Glyph.DEVICE_25111p else Glyph.DEVICE_23112
    } catch (_: Throwable) {
        Glyph.DEVICE_23112
    }

    /** 25 on Phone (3), 13 on Phone (4a) Pro. */
    fun matrixSize(): Int = try {
        Common.getDeviceMatrixLength().takeIf { it > 0 } ?: 25
    } catch (_: Throwable) {
        25
    }
}
