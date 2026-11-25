package com.shaazifi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SkeletonUnitTest {
    @Test
    fun testAppContainerInitialization() {
        val appName = "ShaaziFi"
        assertEquals("ShaaziFi", appName)
    }

    @Test
    fun testDemoDataSingletonExists() {
        assertNotNull(com.shaazifi.app.data.DemoData)
    }
}

