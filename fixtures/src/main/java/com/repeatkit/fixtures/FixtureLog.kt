package com.repeatkit.fixtures

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object FixtureLog {
    private val seqCounter = AtomicInteger(1)
    private val entries = LinkedList<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: (List<String>) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(getRecentEntries())
        return { listeners.remove(listener) }
    }

    @Synchronized
    fun getRecentEntries(): List<String> = entries.toList()

    fun event(scenario: String, event: String, target: String, value: String? = null) {
        val seq = seqCounter.getAndIncrement()
        val jsonObject = JSONObject().apply {
            put("scenario", scenario)
            put("event", event)
            put("target", target)
            if (value != null) {
                put("value", value)
            }
            put("seq", seq)
        }
        val json = jsonObject.toString()
        Log.i("FixtureEvent", json)

        val humanReadable = if (value != null) {
            "#$seq [$scenario] $event -> $target ($value)"
        } else {
            "#$seq [$scenario] $event -> $target"
        }

        val snapshot: List<String>
        synchronized(this) {
            entries.add(humanReadable)
            while (entries.size > 5) {
                entries.removeFirst()
            }
            snapshot = entries.toList()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (listener in listeners) {
                listener(snapshot)
            }
        } else {
            mainHandler.post {
                for (listener in listeners) {
                    listener(snapshot)
                }
            }
        }
    }
}
