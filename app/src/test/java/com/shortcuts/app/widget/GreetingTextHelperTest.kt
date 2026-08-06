package com.shortcuts.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTextHelperTest {

    @Test
    fun `greetingFor hour 4 returns Hi`() {
        assertEquals("Hi, Alice", GreetingTextHelper.greetingFor(4, "Alice"))
    }

    @Test
    fun `greetingFor hour 5 returns Good morning`() {
        assertEquals("Good morning, Alice", GreetingTextHelper.greetingFor(5, "Alice"))
    }

    @Test
    fun `greetingFor hour 11 returns Good morning`() {
        assertEquals("Good morning, Alice", GreetingTextHelper.greetingFor(11, "Alice"))
    }

    @Test
    fun `greetingFor hour 12 returns Good afternoon`() {
        assertEquals("Good afternoon, Alice", GreetingTextHelper.greetingFor(12, "Alice"))
    }

    @Test
    fun `greetingFor hour 16 returns Good afternoon`() {
        assertEquals("Good afternoon, Alice", GreetingTextHelper.greetingFor(16, "Alice"))
    }

    @Test
    fun `greetingFor hour 17 returns Good evening`() {
        assertEquals("Good evening, Alice", GreetingTextHelper.greetingFor(17, "Alice"))
    }

    @Test
    fun `greetingFor hour 21 returns Good evening`() {
        assertEquals("Good evening, Alice", GreetingTextHelper.greetingFor(21, "Alice"))
    }

    @Test
    fun `greetingFor hour 22 returns Hi`() {
        assertEquals("Hi, Alice", GreetingTextHelper.greetingFor(22, "Alice"))
    }

    @Test
    fun `greetingFor hour 0 returns Hi`() {
        assertEquals("Hi, Alice", GreetingTextHelper.greetingFor(0, "Alice"))
    }

    @Test
    fun `greetingFor blank name defaults to there`() {
        assertEquals("Good morning, there", GreetingTextHelper.greetingFor(8, ""))
        assertEquals("Good morning, there", GreetingTextHelper.greetingFor(8, "   "))
        assertEquals("Good afternoon, there", GreetingTextHelper.greetingFor(14, ""))
        assertEquals("Good evening, there", GreetingTextHelper.greetingFor(19, ""))
        assertEquals("Hi, there", GreetingTextHelper.greetingFor(2, ""))
    }
}
