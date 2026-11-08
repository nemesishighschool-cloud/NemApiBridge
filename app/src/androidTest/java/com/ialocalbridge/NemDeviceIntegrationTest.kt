package com.ialocalbridge

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ialocalbridge.utils.NetworkHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device UI security smoke test; it copies and then clears the local clipboard. */
@RunWith(AndroidJUnit4::class)
class NemDeviceIntegrationTest {
    @Test fun dashboardKeyAndUrlControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launch) as MainActivity
        instrumentation.waitForIdleSync()
        val key = ApiKeyStore(context).getOrCreate()
        assertTrue("Bearer key missing", key.isNotBlank())
        var checked = false
        instrumentation.runOnMainSync {
            activity.findViewById<Button>(R.id.nav_settings).performClick()
            val keyField = activity.findViewById<EditText>(R.id.edt_api_key)
            val actualPort = activity.findViewById<EditText>(R.id.edt_port).text.toString().toInt()
            val url = activity.findViewById<TextView>(R.id.txt_server_address).text.toString()
            assertTrue("Key field must match locally stored key", keyField.text.toString() == key)
            assertTrue("Key must be masked by default", keyField.transformationMethod is PasswordTransformationMethod)
            val wifi = NetworkHelper.getWifiIPAddress(context)
            assertEquals(if (wifi == null) "Wi-Fi local indisponible" else "http://$wifi:$actualPort/v1", url)
            val usb = activity.findViewById<TextView>(R.id.txt_usb_address).text.toString()
            assertEquals("http://127.0.0.1:$actualPort/v1", usb)
            val required = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_api_key_required)
            val initial = required.isChecked
            required.performClick()
            assertEquals(!initial, context.getSharedPreferences("server_settings", Context.MODE_PRIVATE).getBoolean("api_key_required", false))
            required.performClick()
            assertEquals(initial, context.getSharedPreferences("server_settings", Context.MODE_PRIVATE).getBoolean("api_key_required", false))
            val toggle = activity.findViewById<Button>(R.id.btn_toggle_api_key)
            toggle.performClick()
            assertTrue("Toggle should reveal the field", keyField.transformationMethod !is PasswordTransformationMethod)
            toggle.performClick()
            assertTrue("Toggle should remask the field", keyField.transformationMethod is PasswordTransformationMethod)
            activity.findViewById<Button>(R.id.btn_copy_api_key).performClick()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue("Copied key differs", clipboard.primaryClip?.getItemAt(0)?.text?.toString() == key)
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            checked = true
            activity.findViewById<Button>(R.id.nav_dashboard).performClick()
        }
        assertTrue(checked)
        instrumentation.runOnMainSync {
            activity.findViewById<Button>(R.id.nav_settings).performClick()
            activity.findViewById<Button>(R.id.btn_rotate_api_key).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        assertTrue("Cancel must not rotate the key", ApiKeyStore(context).getOrCreate() == key)
    }
}
