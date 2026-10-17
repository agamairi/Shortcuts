package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView

class TabsActivity : BaseViewsActivity() {

    private var currentTab = "feed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs)

        FixtureLog.event("tabs", "open", "tabs")

        val txtTabName = findViewById<TextView>(R.id.txt_tab_name)
        val btnLike = findViewById<Button>(R.id.btn_like)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        fun updateTab(tab: String, displayName: String) {
            currentTab = tab
            txtTabName.text = displayName
            btnLike.tag = "btn_like_$tab"
        }

        updateTab("feed", "Feed")

        btnLike.setOnClickListener {
            FixtureLog.event("tabs", "tap", "btn_like_$currentTab")
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_feed -> {
                    FixtureLog.event("tabs", "select", "tab_feed")
                    updateTab("feed", "Feed")
                    true
                }
                R.id.tab_search -> {
                    FixtureLog.event("tabs", "select", "tab_search")
                    updateTab("search", "Search")
                    true
                }
                R.id.tab_profile -> {
                    FixtureLog.event("tabs", "select", "tab_profile")
                    updateTab("profile", "Profile")
                    true
                }
                else -> false
            }
        }
    }
}
