package com.shortcuts.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.mockk
import org.xmlpull.v1.XmlPullParser

class AppShortcutCatalogTest {

    // A fake XmlResourceParser that simulates a shortcuts XML
    class FakeXmlResourceParser(private val events: List<Event>) : XmlResourceParser {
        sealed class Event {
            data class StartTag(val name: String, val attributes: Map<String, String>) : Event()
            data class EndTag(val name: String) : Event()
            object EndDocument : Event()
        }

        private var currentIndex = -1

        override fun setFeature(name: String?, state: Boolean) {}
        override fun getFeature(name: String?): Boolean = false
        override fun setProperty(name: String?, value: Any?) {}
        override fun getProperty(name: String?): Any? = null
        override fun setInput(inStream: java.io.Reader?) {}
        override fun setInput(inputStream: java.io.InputStream?, inputEncoding: String?) {}
        override fun getInputEncoding(): String? = null
        override fun defineEntityReplacementText(entityName: String?, replacementText: String?) {}
        override fun getNamespaceCount(depth: Int): Int = 0
        override fun getNamespacePrefix(pos: Int): String? = null
        override fun getNamespaceUri(pos: Int): String? = null
        override fun getNamespace(prefix: String?): String? = null
        override fun getDepth(): Int = 0
        override fun getPositionDescription(): String? = null
        override fun getLineNumber(): Int = 0
        override fun getColumnNumber(): Int = 0
        override fun isWhitespace(): Boolean = false
        override fun getText(): String? = null
        override fun getTextCharacters(holderForStartAndLength: IntArray?): CharArray? = null
        override fun getNamespace(): String? = null
        
        override fun getName(): String {
            val event = events.getOrNull(currentIndex)
            return when (event) {
                is Event.StartTag -> event.name
                is Event.EndTag -> event.name
                else -> ""
            }
        }
        
        override fun getPrefix(): String? = null
        override fun isEmptyElementTag(): Boolean = false
        
        override fun getAttributeCount(): Int {
            val event = events.getOrNull(currentIndex)
            return if (event is Event.StartTag) event.attributes.size else 0
        }
        
        override fun getAttributeNamespace(index: Int): String? = null
        
        override fun getAttributeName(index: Int): String {
            val event = events.getOrNull(currentIndex)
            return if (event is Event.StartTag) event.attributes.keys.elementAt(index) else ""
        }
        
        override fun getAttributePrefix(index: Int): String? = null
        override fun getAttributeType(index: Int): String? = null
        
        override fun getAttributeValue(index: Int): String {
            val event = events.getOrNull(currentIndex)
            return if (event is Event.StartTag) event.attributes.values.elementAt(index) else ""
        }
        
        override fun getAttributeValue(namespace: String?, name: String?): String? {
            val event = events.getOrNull(currentIndex)
            return if (event is Event.StartTag) event.attributes[name] else null
        }
        
        override fun getEventType(): Int {
            if (currentIndex < 0) return XmlPullParser.START_DOCUMENT
            return when (events.getOrNull(currentIndex)) {
                is Event.StartTag -> XmlPullParser.START_TAG
                is Event.EndTag -> XmlPullParser.END_TAG
                is Event.EndDocument -> XmlPullParser.END_DOCUMENT
                else -> XmlPullParser.END_DOCUMENT
            }
        }
        
        override fun next(): Int {
            currentIndex++
            return getEventType()
        }
        
        override fun nextToken(): Int = next()
        override fun require(type: Int, namespace: String?, name: String?) {}
        override fun nextText(): String = ""
        override fun nextTag(): Int = next()
        
        override fun getAttributeNameResource(index: Int): Int = 0
        override fun getAttributeListValue(namespace: String?, attribute: String?, options: Array<out String>?, defaultValue: Int): Int = 0
        override fun getAttributeBooleanValue(namespace: String?, attribute: String?, defaultValue: Boolean): Boolean = false
        override fun getAttributeResourceValue(namespace: String?, attribute: String?, defaultValue: Int): Int = 0
        override fun getAttributeIntValue(namespace: String?, attribute: String?, defaultValue: Int): Int = 0
        override fun getAttributeUnsignedIntValue(namespace: String?, attribute: String?, defaultValue: Int): Int = 0
        override fun getAttributeFloatValue(namespace: String?, attribute: String?, defaultValue: Float): Float = 0f
        override fun getAttributeListValue(index: Int, options: Array<out String>?, defaultValue: Int): Int = 0
        override fun getAttributeBooleanValue(index: Int, defaultValue: Boolean): Boolean = false
        override fun getAttributeResourceValue(index: Int, defaultValue: Int): Int = 0
        override fun getAttributeIntValue(index: Int, defaultValue: Int): Int = 0
        override fun getAttributeUnsignedIntValue(index: Int, defaultValue: Int): Int = 0
        override fun getAttributeFloatValue(index: Int, defaultValue: Float): Float = 0f
        override fun getIdAttribute(): String? = null
        override fun getClassAttribute(): String? = null
        override fun isAttributeDefault(index: Int): Boolean = false
        override fun getIdAttributeResourceValue(defaultValue: Int): Int = 0
        override fun getStyleAttribute(): Int = 0
        override fun close() {}
    }

    @Test
    fun parseShortcutsXml_validXml_returnsShortcuts() {
        val context = mockk<Context>()
        val catalog = AppShortcutCatalog(context)
        val res = mockk<Resources>()
        
        val parser = FakeXmlResourceParser(listOf(
            FakeXmlResourceParser.Event.StartTag("shortcuts", emptyMap()),
            FakeXmlResourceParser.Event.StartTag("shortcut", mapOf("shortcutId" to "new_tab", "shortcutShortLabel" to "New tab")),
            FakeXmlResourceParser.Event.StartTag("intent", mapOf("action" to "android.intent.action.VIEW", "targetPackage" to "com.android.chrome", "targetClass" to "com.google.android.apps.chrome.Main")),
            FakeXmlResourceParser.Event.EndTag("intent"),
            FakeXmlResourceParser.Event.EndTag("shortcut"),
            FakeXmlResourceParser.Event.EndDocument
        ))
        
        val shortcuts = catalog.parseShortcutsXml(parser, res)
        assertEquals(1, shortcuts.size)
        assertEquals("new_tab", shortcuts[0].id)
        assertEquals("New tab", shortcuts[0].shortLabel)
        assertEquals("android.intent.action.VIEW", shortcuts[0].action)
        assertEquals("com.android.chrome", shortcuts[0].targetPackage)
        assertEquals("com.google.android.apps.chrome.Main", shortcuts[0].targetClass)
        assertNull(shortcuts[0].data)
    }
    
    @Test
    fun parseShortcutsXml_malformedXml_returnsEmptyListWithoutThrowing() {
        val context = mockk<Context>()
        val catalog = AppShortcutCatalog(context)
        val res = mockk<Resources>()
        
        val parser = FakeXmlResourceParser(listOf(
            FakeXmlResourceParser.Event.StartTag("shortcuts", emptyMap()),
            FakeXmlResourceParser.Event.StartTag("shortcut", mapOf("shortcutId" to "missing_label")),
            FakeXmlResourceParser.Event.EndTag("shortcut"),
            FakeXmlResourceParser.Event.EndDocument
        ))
        
        val shortcuts = catalog.parseShortcutsXml(parser, res)
        assertTrue(shortcuts.isEmpty())
    }
}
