package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.Button

class NearDuplicatesActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_near_duplicates)

        FixtureLog.event("near_duplicates", "open", "near_duplicates")

        findViewById<Button>(R.id.btn_internet_sim).setOnClickListener {
            FixtureLog.event("near_duplicates", "tap", "btn_internet_sim")
        }

        findViewById<Button>(R.id.btn_internet).setOnClickListener {
            FixtureLog.event("near_duplicates", "tap", "btn_internet")
        }

        findViewById<Button>(R.id.btn_internet_settings).setOnClickListener {
            FixtureLog.event("near_duplicates", "tap", "btn_internet_settings")
        }
    }
}
