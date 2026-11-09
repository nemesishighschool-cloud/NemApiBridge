package com.ialocalbridge

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a real IME with the accessibility service. No stored coordinates. */
@RunWith(AndroidJUnit4::class)
class KeyboardDismissIntegrationTest {
    @Test fun hidesKeyboardWithoutNavigatingAwayAndRestoresKeyboardUse() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val automation = i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText().trim() }
        val component = "com.ialocalbridge/.ClickAccessibilityService"
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldEnabled = shell("settings get secure accessibility_enabled")
        val others = oldServices.takeUnless { it == "null" || it.isBlank() }.orEmpty()
            .split(':').filter { it.isNotBlank() && it != component }
        fun setServices(list: List<String>) { shell("settings put secure enabled_accessibility_services ${list.joinToString(":").ifEmpty { "null" }}") }
        var activity: MainActivity? = null
        fun waitUntil(message: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
            fail(message)
        }
        try {
            // am instrument force-stops the target: toggle just this service to
            // rebind it while preserving any unrelated enabled services.
            activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            shell("settings put secure accessibility_enabled 1")
            setServices(others)
            Thread.sleep(750) // Let the accessibility manager process unbinding.
            setServices(others + component)
            waitUntil("Accessibility service did not connect") { ClickAccessibilityService.instance != null }
            val service = ClickAccessibilityService.instance!!
            i.runOnMainSync { screen.findViewById<android.widget.Button>(R.id.nav_settings).performClick() }
            i.waitForIdleSync()
            Thread.sleep(500)
            fun openKeyboard() = i.runOnMainSync {
                val port = screen.findViewById<EditText>(R.id.edt_port)
                port.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(port, InputMethodManager.SHOW_IMPLICIT)
            }
            openKeyboard()
            waitUntil("Keyboard did not open") { service.isSoftwareKeyboardVisible() }
            val previous = service.softKeyboardController.showMode
            assertFalse("Wrong package must not hide the keyboard", runBlocking { service.hideKeyboardAfterSend("com.example.other") })
            assertTrue("Wrong-package call unexpectedly hid keyboard", service.isSoftwareKeyboardVisible())
            assertTrue("Expected keyboard to be hidden", runBlocking { service.hideKeyboardAfterSend(context.packageName) })
            waitUntil("Keyboard still visible") { !service.isSoftwareKeyboardVisible() }
            assertEquals("Keyboard policy not restored", previous, service.softKeyboardController.showMode)
            assertEquals(context.packageName, service.currentPackageName())
            assertFalse("No keyboard must be a no-op", runBlocking { service.hideKeyboardAfterSend(context.packageName) })
            assertEquals("No-IME call navigated away", context.packageName, service.currentPackageName())
            openKeyboard()
            waitUntil("Keyboard cannot be reopened afterwards") { service.isSoftwareKeyboardVisible() }
        } finally {
            activity?.let { screen -> i.runOnMainSync {
                val view = screen.findViewById<EditText>(R.id.edt_port)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                screen.finish()
            } }
            if (oldServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            if (oldEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $oldEnabled")
        }
    }
}
