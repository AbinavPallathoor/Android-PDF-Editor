package com.pdfeditor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageFolderLabelTest {
    private val external = "com.android.externalstorage.documents"

    @Test
    fun `internal storage paths name each folder`() {
        assertEquals("Internal storage › Download", storageFolderLabel(external, "primary:Download/Report-edited.pdf"))
        assertEquals("Internal storage › Documents › Work", storageFolderLabel(external, "primary:Documents/Work/a.pdf"))
    }

    @Test
    fun `a file at the volume root names only the volume`() {
        assertEquals("Internal storage", storageFolderLabel(external, "primary:a.pdf"))
        assertEquals("SD card", storageFolderLabel(external, "1A2B-3C4D:a.pdf"))
    }

    @Test
    fun `downloads provider and opaque ids`() {
        assertEquals("Downloads", storageFolderLabel("com.android.providers.downloads.documents", "msf:42"))
        assertNull(storageFolderLabel(external, "no-volume"))
        assertNull(storageFolderLabel("com.example.cloud", "opaque-id"))
    }

    @Test
    fun `file paths in shared storage name their folder`() {
        assertEquals(
            "Internal storage › Download",
            sharedStorageFolderLabel("/storage/emulated/0/Download/Report.pdf", "/storage/emulated/0"),
        )
        assertNull(sharedStorageFolderLabel("/data/user/0/com.pdfeditor.app/files/a.pdf", "/storage/emulated/0"))
    }
}
