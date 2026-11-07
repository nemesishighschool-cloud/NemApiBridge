package com.ialocalbridge.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderConfigParserTest {

    @Test
    fun parsesProviderAndSelectorConfiguration() {
        val config = ProviderConfigParser.parse(
            """
            {
              "provider": "Example AI",
              "version": "2",
              "package_name": "com.example.ai",
              "selectors": {
                "input": [{"by": "resource_id", "value": "com.example.ai:id/prompt"}],
                "send": [{"by": "content_description", "value": "Send"}],
                "assistant_message": [{"by": "class_name", "value": "android.widget.TextView"}],
                "generating": [{"by": "text", "value": "Stop", "match": "contains"}]
              },
              "timeouts": {
                "element_ms": 5000,
                "response_ms": 90000,
                "poll_interval_ms": 500,
                "stable_polls": 4
              },
              "max_history_chars": 6000
            }
            """.trimIndent()
        )

        assertEquals("Example AI", config.provider)
        assertEquals("com.example.ai", config.packageName)
        assertEquals("resource_id", config.selectors.input.single().by)
        assertEquals("android.widget.TextView", config.selectors.assistantMessage.single().value)
        assertEquals(90_000L, config.timeouts.responseMs)
        assertEquals(6000, config.maxHistoryChars)
    }

    @Test
    fun rejectsMissingRequiredSelectors() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigParser.parse(
                """
                {
                  "provider": "Example",
                  "package_name": "com.example.ai",
                  "selectors": {
                    "input": [],
                    "send": [{"by": "text", "value": "Send"}],
                    "assistant_message": [{"by": "text", "value": "Answer"}]
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals("Le provider doit définir au moins un sélecteur input.", error.message)
    }

    @Test
    fun rejectsUnknownSelectorTypesAndInvalidPackageNames() {
        val unknownSelectorError = assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigParser.parse(
                """
                {
                  "provider": "Example",
                  "package_name": "com.example.ai",
                  "selectors": {
                    "input": [{"by": "screen_coordinate", "value": "1,2"}],
                    "send": [{"by": "text", "value": "Send"}],
                    "assistant_message": [{"by": "text", "value": "Answer"}]
                  }
                }
                """.trimIndent()
            )
        }
        assertEquals(true, unknownSelectorError.message?.contains("Type de sélecteur inconnu"))

        val invalidPackageError = assertThrows(IllegalArgumentException::class.java) {
            ProviderConfigParser.parse(
                """
                {
                  "provider": "Example",
                  "package_name": "not a package",
                  "selectors": {
                    "input": [{"by": "text", "value": "Prompt"}],
                    "send": [{"by": "text", "value": "Send"}],
                    "assistant_message": [{"by": "text", "value": "Answer"}]
                  }
                }
                """.trimIndent()
            )
        }
        assertEquals(true, invalidPackageError.message?.contains("package_name"))
    }
}
