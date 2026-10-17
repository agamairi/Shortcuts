package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.Button

class DelayedStepTwoActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delayed_step_two)

        FixtureLog.event("delayed", "open", "step_two")

        findViewById<Button>(R.id.btn_done).setOnClickListener {
            FixtureLog.event("delayed", "tap", "btn_done")
            finish()
        }
    }
}
