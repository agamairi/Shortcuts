package com.repeatkit.fixtures

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText

class TextViewsActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_views)

        FixtureLog.event("text_views", "open", "text_views")

        val editSearch = findViewById<EditText>(R.id.edit_search)
        val editMessage = findViewById<EditText>(R.id.edit_message)
        val btnSend = findViewById<Button>(R.id.btn_send)

        var lastSearchSubmitTime = 0L
        fun submitSearch() {
            val now = SystemClock.uptimeMillis()
            if (now - lastSearchSubmitTime < 250L) return
            lastSearchSubmitTime = now
            val text = editSearch.text.toString()
            FixtureLog.event("text_views", "submit", "edit_search", text)
            val intent = Intent(this, SearchResultsActivity::class.java).apply {
                putExtra(SearchResultsActivity.EXTRA_QUERY, text)
            }
            startActivity(intent)
        }

        editSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_NULL || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                submitSearch()
                true
            } else {
                false
            }
        }

        editSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                submitSearch()
                true
            } else {
                false
            }
        }

        editSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = editSearch.text.toString()
                if (text.isNotEmpty()) {
                    FixtureLog.event("text_views", "text", "edit_search", text)
                }
            }
        }

        var lastMessageSubmitTime = 0L
        fun submitMessage() {
            val now = SystemClock.uptimeMillis()
            if (now - lastMessageSubmitTime < 250L) return
            lastMessageSubmitTime = now
            val text = editMessage.text.toString()
            FixtureLog.event("text_views", "submit", "edit_message", text)
            editMessage.text.clear()
        }

        editMessage.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_NULL || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                submitMessage()
                true
            } else {
                false
            }
        }

        editMessage.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                submitMessage()
                true
            } else {
                false
            }
        }

        editMessage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = editMessage.text.toString()
                if (text.isNotEmpty()) {
                    FixtureLog.event("text_views", "text", "edit_message", text)
                }
            }
        }

        btnSend.setOnClickListener {
            submitMessage()
        }
    }
}
