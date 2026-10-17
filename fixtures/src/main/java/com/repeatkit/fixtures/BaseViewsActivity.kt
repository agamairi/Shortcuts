package com.repeatkit.fixtures

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

open class BaseViewsActivity : AppCompatActivity() {
    private var removeLogListener: (() -> Unit)? = null

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        setupEdgeToEdgeAndFooter()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        setupEdgeToEdgeAndFooter()
    }

    protected fun setupEdgeToEdgeAndFooter() {
        val root = findViewById<View>(android.R.id.content)
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }
        val footerLogs = findViewById<TextView>(R.id.footer_logs)
        if (footerLogs != null) {
            removeLogListener?.invoke()
            removeLogListener = FixtureLog.addListener { entries ->
                val lines = if (entries.isEmpty()) {
                    "No events yet\n\n\n\n"
                } else {
                    val list = entries.takeLast(5)
                    val padded = list + List(maxOf(0, 5 - list.size)) { "" }
                    padded.joinToString("\n")
                }
                footerLogs.text = lines
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeLogListener?.invoke()
        removeLogListener = null
    }
}
