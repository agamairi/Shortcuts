package com.repeatkit.fixtures

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class NavigationHomeActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_home)

        FixtureLog.event("navigation", "open", "nav_home")

        findViewById<Button>(R.id.btn_nav_profile).setOnClickListener {
            FixtureLog.event("navigation", "tap", "btn_profile")
            startActivity(Intent(this, NavigationProfileActivity::class.java))
        }
    }
}

class NavigationProfileActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_profile)

        FixtureLog.event("navigation", "open", "nav_profile")

        findViewById<Button>(R.id.btn_nav_edit).setOnClickListener {
            FixtureLog.event("navigation", "tap", "btn_edit")
            startActivity(Intent(this, NavigationEditActivity::class.java))
        }
    }
}

class NavigationEditActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_edit)

        FixtureLog.event("navigation", "open", "nav_edit")

        val editName = findViewById<EditText>(R.id.edit_name)
        val btnSave = findViewById<Button>(R.id.btn_profile_save)

        editName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = editName.text.toString()
                if (text.isNotEmpty()) {
                    FixtureLog.event("navigation", "text", "edit_name", text)
                }
            }
        }

        btnSave.setOnClickListener {
            val text = editName.text.toString()
            if (text.isNotEmpty()) {
                FixtureLog.event("navigation", "text", "edit_name", text)
            }
            FixtureLog.event("navigation", "tap", "btn_profile_save")
            finish()
        }
    }
}
