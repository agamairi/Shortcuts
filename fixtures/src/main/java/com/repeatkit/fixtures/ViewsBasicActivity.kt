package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

class ViewsBasicActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_views_basic)

        FixtureLog.event("views_basic", "open", "views_basic")

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            FixtureLog.event("views_basic", "tap", "btn_save")
        }

        findViewById<ImageButton>(R.id.btn_gear).setOnClickListener {
            FixtureLog.event("views_basic", "tap", "btn_gear")
        }

        findViewById<ImageButton>(R.id.icon_unlabeled).setOnClickListener {
            FixtureLog.event("views_basic", "tap", "icon_unlabeled")
        }

        findViewById<CheckBox>(R.id.chk_remember).setOnCheckedChangeListener { _, isChecked ->
            FixtureLog.event("views_basic", "toggle", "chk_remember", if (isChecked) "on" else "off")
        }

        findViewById<SwitchMaterial>(R.id.sw_wifi).setOnCheckedChangeListener { _, isChecked ->
            FixtureLog.event("views_basic", "toggle", "sw_wifi", if (isChecked) "on" else "off")
        }

        val txtHold = findViewById<TextView>(R.id.txt_hold)
        txtHold.setOnClickListener {
            FixtureLog.event("views_basic", "tap", "txt_hold")
        }
        txtHold.setOnLongClickListener {
            FixtureLog.event("views_basic", "long_press", "txt_hold")
            true
        }
    }
}
