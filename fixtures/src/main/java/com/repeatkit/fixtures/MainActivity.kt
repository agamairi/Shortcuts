package com.repeatkit.fixtures

import android.content.Intent
import android.os.Bundle
import android.widget.Button

class MainActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FixtureLog.event("main", "open", "main")

        setupScenarioButtons()

        val directScenario = intent.getStringExtra("scenario")
        if (directScenario != null) {
            launchScenario(directScenario)
        }
    }

    private fun setupScenarioButtons() {
        findViewById<Button>(R.id.btn_scenario_views_basic).setOnClickListener {
            launchScenario("views_basic")
        }
        findViewById<Button>(R.id.btn_scenario_compose_basic).setOnClickListener {
            launchScenario("compose_basic")
        }
        findViewById<Button>(R.id.btn_scenario_views_list).setOnClickListener {
            launchScenario("views_list")
        }
        findViewById<Button>(R.id.btn_scenario_compose_list).setOnClickListener {
            launchScenario("compose_list")
        }
        findViewById<Button>(R.id.btn_scenario_near_duplicates).setOnClickListener {
            launchScenario("near_duplicates")
        }
        findViewById<Button>(R.id.btn_scenario_text_views).setOnClickListener {
            launchScenario("text_views")
        }
        findViewById<Button>(R.id.btn_scenario_text_compose).setOnClickListener {
            launchScenario("text_compose")
        }
        findViewById<Button>(R.id.btn_scenario_dialogs).setOnClickListener {
            launchScenario("dialogs")
        }
        findViewById<Button>(R.id.btn_scenario_delayed).setOnClickListener {
            launchScenario("delayed")
        }
        findViewById<Button>(R.id.btn_scenario_navigation).setOnClickListener {
            launchScenario("navigation")
        }
        findViewById<Button>(R.id.btn_scenario_webview).setOnClickListener {
            launchScenario("webview")
        }
        findViewById<Button>(R.id.btn_scenario_tabs).setOnClickListener {
            launchScenario("tabs")
        }
    }

    private fun launchScenario(scenarioId: String) {
        FixtureLog.event("main", "nav", scenarioId)
        val targetClass = when (scenarioId) {
            "views_basic" -> ViewsBasicActivity::class.java
            "compose_basic" -> ComposeBasicActivity::class.java
            "views_list" -> ViewsListActivity::class.java
            "compose_list" -> ComposeListActivity::class.java
            "near_duplicates" -> NearDuplicatesActivity::class.java
            "text_views" -> TextViewsActivity::class.java
            "text_compose" -> TextComposeActivity::class.java
            "dialogs" -> DialogsActivity::class.java
            "delayed" -> DelayedActivity::class.java
            "navigation" -> NavigationHomeActivity::class.java
            "webview" -> WebViewActivity::class.java
            "tabs" -> TabsActivity::class.java
            else -> null
        }
        if (targetClass != null) {
            startActivity(Intent(this, targetClass))
        }
    }
}
