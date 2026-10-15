package com.example.phasefixture

import android.app.Activity
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Synthetic receiver only. Never schedules alarms or claims OEM compatibility. */
class ClockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("fixture", 0)
        val setting = intent.action == AlarmClock.ACTION_SET_ALARM
        if (setting && savedInstanceState == null) prefs.edit().putInt("received", prefs.getInt("received", 0) + 1).commit()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 100, 30, 30) }
        fun text(value: String) { body.addView(TextView(this).apply { text = value; textSize = 20f }) }
        text("FIXTURE $packageName")
        text("received=${prefs.getInt("received", 0)} saved=${prefs.getInt("saved", 0)}")
        if (setting) {
            text("target=%02d:%02d skip=%s".format(intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1),
                intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1), intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)))
            text("label=${intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)}")
            body.addView(Button(this).apply {
                text = "FIXTURE SAVE"
                setOnClickListener { prefs.edit().putInt("saved", prefs.getInt("saved", 0) + 1).commit(); finish() }
            })
            body.addView(Button(this).apply { text = "FIXTURE CANCEL"; setOnClickListener { finish() } })
        } else text("FIXTURE LIST")
        setContentView(body)
    }
}
