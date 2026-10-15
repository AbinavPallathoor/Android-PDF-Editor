package com.pdfeditor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityOpenRequestTest {
    @Test
    fun `cold launch buffers document until navigation starts collecting`() =
        runTest {
            val uri = Uri.parse("content://documents.example/cold")
            val controller = Robolectric.buildActivity(MainActivity::class.java, pdfIntent(uri)).create()
            try {
                val request = withTimeout(1_000) { controller.get().openRequests.first() }

                assertEquals(uri, request.uri)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun `warm requests are retained in order and replace the activity intent`() =
        runTest {
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            try {
                val first = Uri.parse("content://documents.example/first")
                val second = Uri.parse("content://documents.example/second")
                controller.newIntent(pdfIntent(first))
                controller.newIntent(pdfIntent(second))

                val requests = withTimeout(1_000) { controller.get().openRequests.take(2).toList() }

                assertEquals(listOf(first, second), requests.map { it.uri })
                assertEquals(second, controller.get().intent.data)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun `activity recreation does not replay the original document intent`() =
        runTest {
            val controller =
                Robolectric.buildActivity(MainActivity::class.java, pdfIntent(Uri.parse("content://documents.example/already-open")))
                    .create(Bundle())
            try {
                // A fresh warm request must be first; replaying the cold Intent would precede it.
                val nextUri = Uri.parse("content://documents.example/next")
                controller.newIntent(pdfIntent(nextUri))

                val request = withTimeout(1_000) { controller.get().openRequests.first() }

                assertEquals(nextUri, request.uri)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun `recreation preserves cold and warm requests not yet collected`() =
        runTest {
            val coldUri = Uri.parse("content://documents.example/cold-pending")
            val warmUri = Uri.parse("content://documents.example/warm-pending")
            val first = Robolectric.buildActivity(MainActivity::class.java, pdfIntent(coldUri)).create()
            first.newIntent(pdfIntent(warmUri))
            val savedState = Bundle()
            first.saveInstanceState(savedState).destroy()
            val restored = Robolectric.buildActivity(MainActivity::class.java, pdfIntent(warmUri)).create(savedState)
            try {
                val requests = withTimeout(1_000) { restored.get().openRequests.take(2).toList() }

                assertEquals(listOf(coldUri, warmUri), requests.map { it.uri })
            } finally {
                restored.destroy()
            }
        }

    @Test
    fun `recreation does not redeliver requests already collected`() =
        runTest {
            val initialIntent = pdfIntent(Uri.parse("content://documents.example/consumed"))
            val first = Robolectric.buildActivity(MainActivity::class.java, initialIntent).create()
            withTimeout(1_000) { first.get().openRequests.first() }
            val savedState = Bundle()
            first.saveInstanceState(savedState).destroy()
            val restored = Robolectric.buildActivity(MainActivity::class.java, initialIntent).create(savedState)
            try {
                val nextUri = Uri.parse("content://documents.example/new-request")
                restored.newIntent(pdfIntent(nextUri))

                val request = withTimeout(1_000) { restored.get().openRequests.first() }

                assertEquals(nextUri, request.uri)
            } finally {
                restored.destroy()
            }
        }

    private fun pdfIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
}
