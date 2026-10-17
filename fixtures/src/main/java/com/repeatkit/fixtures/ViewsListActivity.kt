package com.repeatkit.fixtures

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ViewsListActivity : BaseViewsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_views_list)

        FixtureLog.event("views_list", "open", "views_list")

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_views_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ViewsListAdapter()
    }

    private class ViewsListAdapter : RecyclerView.Adapter<ViewsListAdapter.ViewHolder>() {
        private var row45Logged = false

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.item_title)
            val subtitle: TextView = itemView.findViewById(R.id.item_subtitle)
            val btnOpen: Button = itemView.findViewById(R.id.btn_item_open)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_views_list, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = 60

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val itemNumber = position + 1
            holder.title.text = "Item $itemNumber"
            holder.subtitle.text = "Subtitle"

            if (itemNumber == 45 && !row45Logged) {
                row45Logged = true
                FixtureLog.event("views_list", "scroll_reached", "row_45")
            }

            holder.itemView.setOnClickListener {
                FixtureLog.event("views_list", "tap", "row_$itemNumber")
            }

            holder.btnOpen.setOnClickListener {
                FixtureLog.event("views_list", "tap", "row_${itemNumber}_open")
            }
        }
    }
}
