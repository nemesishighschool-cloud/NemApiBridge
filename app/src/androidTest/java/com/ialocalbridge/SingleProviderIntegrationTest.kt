package com.ialocalbridge

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ialocalbridge.models.ProviderConfig
import com.ialocalbridge.models.ProviderSelectors
import com.ialocalbridge.models.UiSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleProviderIntegrationTest {
    @Test fun replacementIsAtomicAndInvalidImportPreservesCurrent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("provider_configs", Context.MODE_PRIVATE)
        val oldProviders = prefs.getString("providers", null)
        val oldActive = prefs.getString("active_provider", null)
        val repo = ProviderRepository(context)
        val selector = listOf(UiSelector(by = "text", value = "x"))
        val initial = ProviderConfig(provider = "Initial", packageName = "com.example.first", selectors = ProviderSelectors(input = selector, send = selector, assistantMessage = selector))
        try {
            repo.replace(initial)
            assertEquals(listOf("Initial"), repo.getAll().map { it.provider })
            assertEquals("Initial", repo.getActiveName())
            try {
                repo.replace(initial.copy(provider = "Invalid", packageName = "bad"))
                fail("Invalid provider was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(listOf("Initial"), repo.getAll().map { it.provider })
            assertEquals("Initial", repo.getActiveName())
            repo.replace(initial.copy(provider = "Replacement", packageName = "com.example.second"))
            assertEquals(listOf("Replacement"), repo.getAll().map { it.provider })
            assertEquals("Replacement", repo.getActiveName())
        } finally {
            prefs.edit().apply {
                if (oldProviders == null) remove("providers") else putString("providers", oldProviders)
                if (oldActive == null) remove("active_provider") else putString("active_provider", oldActive)
            }.commit()
        }
    }
}
