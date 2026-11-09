package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeImageProviderTest {
    @Test fun stagesPrivateReadOnlyImageAndRemovesIt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val image = BridgeImageProvider.stage(ctx, byteArrayOf(1,2,3), "image/png")
        try {
            assertTrue(image.file.canonicalPath.startsWith(ctx.cacheDir.canonicalPath + "/"))
            val uri = DocumentsContract.buildDocumentUri(ctx.packageName + ".images", image.id)
            val resolver = ctx.contentResolver
            resolver.query(uri, null, null, null, null).use { c ->
                assertNotNull(c); assertTrue(c!!.moveToFirst())
                assertEquals("image/png", c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)))
            }
            assertArrayEquals(byteArrayOf(1,2,3), resolver.openInputStream(uri)!!.use { it.readBytes() })
            BridgeImageProvider.hide(ctx, image)
            val children = DocumentsContract.buildChildDocumentsUri(ctx.packageName + ".images", "images")
            resolver.query(children, null, null, null, null).use { c -> assertEquals(0, c!!.count) }
            assertArrayEquals(byteArrayOf(1,2,3), resolver.openInputStream(uri)!!.use { it.readBytes() })
            try { resolver.openFileDescriptor(uri,"w"); fail("write allowed") } catch (_: java.io.FileNotFoundException) { }
        } finally { BridgeImageProvider.remove(ctx,image) }
        assertFalse(image.file.exists())
    }
}
