package com.repeatkit.fixtures

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar

class DelayedActivity : BaseViewsActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var revealRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delayed)

        FixtureLog.event("delayed", "open", "delayed")

        val progressBar = findViewById<ProgressBar>(R.id.progress_delayed)
        val btnContinue = findViewById<Button>(R.id.btn_continue)

        revealRunnable = Runnable {
            progressBar.visibility = View.GONE
            btnContinue.visibility = View.VISIBLE
        }
        handler.postDelayed(revealRunnable!!, 2500)

        btnContinue.setOnClickListener {
            FixtureLog.event("delayed", "tap", "btn_continue")
            FixtureLog.event("delayed", "nav", "step_two")
            startActivity(Intent(this, DelayedStepTwoActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        revealRunnable?.let { handler.removeCallbacks(it) }
    }
}
