package com.ialocalbridge

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImagePayloadIntegrationTest {
    private fun url(): String {
        val image=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888); image.eraseColor(Color.RED)
        val out=ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG,100,out); image.recycle()
        return "data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
    private fun image(url:String)=JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",url))
    private fun user(parts:JSONArray)=JSONObject().put("role","user").put("content",parts)
    @Test fun validatesAndTransformsLatestImageWithoutLosingText() {
        val parts=JSONArray().put(JSONObject().put("type","text").put("text","What color?")).put(image(url()))
        val parsed=ImagePayload.parse(JSONArray().put(user(parts)))
        assertEquals("What color?\n[Image 1 attached]",parsed.messages.getJSONObject(0).getString("content"))
        assertEquals("image/png",parsed.images.single().mime)
        assertEquals(2, ImagePayload.parse(JSONArray().put(user(JSONArray().put(image(url())).put(image(url()))))).images.size)
        val only=ImagePayload.parse(JSONArray().put(user(JSONArray().put(image(url())))))
        assertEquals("[Image 1 attached]",only.messages.getJSONObject(0).getString("content"))
    }
    @Test fun refusesExternalMalformedHistoricalAndMultipleImages() {
        fun rejected(messages: JSONArray) {
            try { ImagePayload.parse(messages); fail("unsafe image accepted") } catch (_: AutomationException) { }
        }
        rejected(JSONArray().put(user(JSONArray().put(image("https://example.org/a.png")))))
        rejected(JSONArray().put(user(JSONArray().put(image("data:image/png;base64,YQ==")))))
        val tooMany = JSONArray(); repeat(9) { tooMany.put(image(url())) }
        rejected(JSONArray().put(user(tooMany)))
        val history = ImagePayload.parse(JSONArray().put(user(JSONArray().put(image(url())))).put(JSONObject().put("role","user").put("content","next")))
        assertEquals(1, history.images.size)
        assertEquals("[Image 1 attached]", history.messages.getJSONObject(0).getString("content"))
        rejected(JSONArray().put(JSONObject().put("role","assistant").put("content",JSONArray().put(image(url())))))
    }
    @Test fun responsesImageIsMappedToTheSameValidatedPipeline() {
        val parts=JSONArray().put(JSONObject().put("type","input_text").put("text","What color?"))
            .put(JSONObject().put("type","input_image").put("image_url",url()))
        val body=JSONObject().put("input",JSONArray().put(user(parts)))
        val result=ImagePayload.parse(ResponsesCodec.toChat(body).getJSONArray("messages"))
        assertEquals("What color?\n[Image 1 attached]",result.messages.getJSONObject(0).getString("content"))
        assertEquals("image/png",result.images.single().mime)
    }
}
