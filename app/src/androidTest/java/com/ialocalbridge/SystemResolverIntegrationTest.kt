package com.ialocalbridge

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Requires the external test.resolver probe APK. It is not shipped with the app. */
@RunWith(AndroidJUnit4::class)
class SystemResolverIntegrationTest {
    @Test fun selectsSystemFilesFromRealAndroidChooser() {
        val i = InstrumentationRegistry.getInstrumentation()
        val automation = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }
        assumeTrue("Install tests/resolver-probe.sh fixture to exercise the chooser", shell("pm path test.resolver").isNotBlank())
        val component = "com.ialocalbridge/.ClickAccessibilityService"
        val old = shell("settings get secure enabled_accessibility_services")
        val oldEnabled = shell("settings get secure accessibility_enabled")
        val others = old.takeUnless { it == "null" || it.isBlank() }.orEmpty().split(':').filter { it.isNotBlank() && it != component }
        fun services(list: List<String>) { shell("settings put secure enabled_accessibility_services ${list.joinToString(":").ifEmpty { "null" }}") }
        fun waitFor(message: String, test: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) { if (test()) return; Thread.sleep(100) }
            fail(message)
        }
        try {
            shell("settings put secure accessibility_enabled 1")
            services(others); Thread.sleep(750); services(others + component)
            waitFor("service did not connect") { ClickAccessibilityService.instance != null }
            val service = ClickAccessibilityService.instance!!
            shell("am force-stop com.google.android.documentsui")
            shell("am force-stop test.resolver")
            shell("am start -n test.resolver/.Probe")
            waitFor("Android chooser not displayed") { service.currentPackageName() == "android" }
            var selected = false
            i.runOnMainSync { selected = service.selectSystemDocumentResolver() }
            assertTrue("system Files label not selected", selected)
            waitFor("DocumentsUI did not open") { service.currentPackageName() == "com.google.android.documentsui" || service.currentPackageName() == "com.android.documentsui" }
        } finally {
            shell("am force-stop test.resolver")
            if (old == "null") shell("settings delete secure enabled_accessibility_services") else shell("settings put secure enabled_accessibility_services $old")
            if (oldEnabled == "null") shell("settings delete secure accessibility_enabled") else shell("settings put secure accessibility_enabled $oldEnabled")
        }
    }
}
