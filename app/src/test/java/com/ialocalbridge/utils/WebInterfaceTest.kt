package com.ialocalbridge.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInterfaceTest {
    @Test fun dashboardAndChatUseLocalOpenRoutesWithoutEmbeddingSecrets() {
        val paths = listOf(File("src/main/assets/www/index.html"), File("app/src/main/assets/www/index.html"))
        val html = paths.first { it.isFile }.readText()
        assertTrue(html.contains("/web/chat"))
        assertTrue(html.contains("/web/config"))
        assertTrue(html.contains("Réseau local ouvert"))
        assertFalse(html.contains("sk-bridge-"))
    }
}
