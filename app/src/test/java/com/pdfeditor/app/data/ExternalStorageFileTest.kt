package com.pdfeditor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ExternalStorageFileTest {
    private val primary = File("/storage/emulated/0")

    @Test
    fun `primary volume paths map under the primary root`() {
        assertEquals(File("/storage/emulated/0/Download/Report.pdf"), externalStorageFile("primary:Download/Report.pdf", primary))
    }

    @Test
    fun `other volumes map under storage`() {
        assertEquals(File("/storage/1A2B-3C4D/Docs/a.pdf"), externalStorageFile("1A2B-3C4D:Docs/a.pdf", primary))
    }

    @Test
    fun `folders and malformed ids are not files`() {
        assertNull(externalStorageFile("primary:", primary))
        assertNull(externalStorageFile("no-volume", primary))
    }

    @Test
    fun `only user-visible shared storage counts as a lasting file`() {
        val shared = com.pdfeditor.app.ui.nav.isSharedStorageFile(File("/storage/emulated/0/Download/a.pdf"), primary)
        val sdCard = com.pdfeditor.app.ui.nav.isSharedStorageFile(File("/storage/1A2B-3C4D/Docs/a.pdf"), primary)
        val appCache = com.pdfeditor.app.ui.nav.isSharedStorageFile(File("/data/user/0/com.example/cache/a.pdf"), primary)
        val otherAppData = com.pdfeditor.app.ui.nav.isSharedStorageFile(File("/storage/emulated/0/Android/data/com.example/a.pdf"), primary)
        org.junit.Assert.assertTrue(shared && sdCard)
        org.junit.Assert.assertFalse(appCache || otherAppData)
    }
}
