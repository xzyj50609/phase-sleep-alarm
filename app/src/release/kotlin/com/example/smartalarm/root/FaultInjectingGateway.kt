package com.example.smartalarm.root

import android.content.Context
import com.example.smartalarm.core.interaction.ClockGateway

/** Release has no file-controlled fault injector. The debug implementation is in src/debug only. */
internal object FaultInjectingGateway {
    @Suppress("UNUSED_PARAMETER")
    fun wrap(context: Context, real: ClockGateway): ClockGateway = real
}
