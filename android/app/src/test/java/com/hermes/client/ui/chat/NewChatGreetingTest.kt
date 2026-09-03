package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class NewChatGreetingTest {
    @Test fun fiveTimeBucketsInBothLanguages() {
        assertEquals("早上好", greetingForHour(6, null, AppLanguage.ZH))
        assertEquals("上午好", greetingForHour(10, null, AppLanguage.ZH))
        assertEquals("下午好", greetingForHour(15, null, AppLanguage.ZH))
        assertEquals("晚上好", greetingForHour(21, null, AppLanguage.ZH))
        assertEquals("夜深了", greetingForHour(2, null, AppLanguage.ZH))
        assertEquals("Good afternoon", greetingForHour(15, null, AppLanguage.EN))
    }

    @Test fun customNameJoinsTheGreeting() {
        assertEquals("下午好，国盛", greetingForHour(15, "国盛", AppLanguage.ZH))
        assertEquals("Good afternoon, Sheng", greetingForHour(15, "Sheng", AppLanguage.EN))
    }

    @Test fun blankNameIsOmittedNotPlaceholdered() {
        assertEquals("下午好", greetingForHour(15, "", AppLanguage.ZH))
        assertEquals("下午好", greetingForHour(15, "   ", AppLanguage.ZH))
    }
}
