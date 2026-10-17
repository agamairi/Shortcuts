package com.repeatkit.fixtures

import android.os.Bundle
import android.widget.TextView

class SearchResultsActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_results)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        FixtureLog.event("search_results", "open", "search_results", query.ifEmpty { null })

        val txtResults = findViewById<TextView>(R.id.txt_search_results)
        txtResults.text = "Results for \"$query\""
    }

    companion object {
        const val EXTRA_QUERY = "extra_query"
    }
}
