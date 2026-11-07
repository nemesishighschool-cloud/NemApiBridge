package com.ialocalbridge.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInterfaceTest {

    @Test
    fun dashboardShellDoesNotEmbedRuntimeServerConfiguration() {
        val html = WebInterface.getHtml()

        assertTrue(html.contains("id=\"serverAddress\">--</div>"))
        assertTrue(html.contains("Authentifiez-vous pour afficher la configuration."))
        assertFalse(html.contains("192.168."))
    }
}
