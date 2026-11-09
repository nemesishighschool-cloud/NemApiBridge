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
    @Test
    fun parsesStructuralSelectorsAndSemanticScrolling() {
        val config = ProviderConfigParser.parse(
            """
            {
              "provider": "DeepSeek", "package_name": "com.deepseek.chat",
              "selectors": {
                "input": [{"by": "class_name", "value": "android.widget.EditText"}],
                "input_activation": [{"by": "text", "value": "Type a message"}],
                "copy_response": [{"by": "content_description", "value": "Copy"}],
                "allow_input_gesture": true,
                "allow_stop_gesture": true,
                "detect_identical_responses": true,
                "allow_reconstruction_gesture": true,
                "send": [{"by": "content_description", "value": "Send"}],
                "assistant_message": [{"by": "content_description", "value": "Copy", "ancestor_levels": 2, "text_only": true, "reject_clipped": true, "reconstruct_clipped": true}],
                "scroll_to_end": [{"by": "scrollable", "value": "true"}]
              }
            }
            """.trimIndent()
        )
        val answer = config.selectors.assistantMessage.single()
        assertEquals(2, answer.ancestorLevels)
        assertEquals(true, answer.textOnly)
        assertEquals(true, answer.rejectClipped)
        assertEquals(true, answer.reconstructClipped)
        assertEquals(true, config.selectors.allowReconstructionGesture)
        assertEquals("true", config.selectors.scrollToEnd.single().value)
        assertEquals("Type a message", config.selectors.inputActivation.single().value)
        assertEquals(true, config.selectors.allowInputGesture)
        assertEquals(true, config.selectors.allowStopGesture)
        assertEquals(false, ProviderConfigParser.parse(com.google.gson.Gson().toJson(config).replace("\"allow_stop_gesture\":true", "\"allow_stop_gesture\":false")).selectors.allowStopGesture)
        assertEquals(true, config.selectors.detectIdenticalResponses)
        assertEquals("Copy", config.selectors.copyResponse.single().value)
    }

    @Test
    fun rejectsInvalidStructuralSelectorValues() {
        val base = ProviderConfig(
            provider = "test", packageName = "com.example.ai",
            selectors = ProviderSelectors(
                input = listOf(UiSelector("class_name", "android.widget.EditText")),
                send = listOf(UiSelector("text", "Send")),
                assistantMessage = listOf(UiSelector("text", "Copy", ancestorLevels = 9))
            )
        )
        assertThrows(IllegalArgumentException::class.java) { ProviderConfigParser.validate(base) }
        val badScroll = base.copy(selectors = base.selectors.copy(
            assistantMessage = listOf(UiSelector("text", "Copy")),
            scrollToEnd = listOf(UiSelector("scrollable", "maybe"))
        ))
        assertThrows(IllegalArgumentException::class.java) { ProviderConfigParser.validate(badScroll) }
        val noActivation = base.copy(selectors = base.selectors.copy(
            assistantMessage = listOf(UiSelector("text", "Copy")), allowInputGesture = true
        ))
        assertThrows(IllegalArgumentException::class.java) { ProviderConfigParser.validate(noActivation) }
    }

    @Test fun oldProviderDoesNotEnableClipboardByDefault() {
        val config = ProviderConfigParser.parse("""{"provider":"Legacy","package_name":"com.example.ai","selectors":{"input":[{"by":"text","value":"input"}],"send":[{"by":"text","value":"send"}],"assistant_message":[{"by":"text","value":"answer"}]}}""")
        assertEquals(emptyList<UiSelector>(), config.selectors.copyResponse)
    }

    @Test fun invalidClipboardSelectorIsRejected() {
        val config = ProviderConfig(provider = "Test", packageName = "com.example.ai", selectors = ProviderSelectors(
            input = listOf(UiSelector("text", "input")), send = listOf(UiSelector("text", "send")),
            assistantMessage = listOf(UiSelector("text", "answer")), copyResponse = listOf(UiSelector("coordinates", "1,2"))))
        assertThrows(IllegalArgumentException::class.java) { ProviderConfigParser.validate(config) }
    }

}
