package com.inkforge.notesstudio

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.fdf.FDFDocument
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.StringWriter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** A separate PDF implementation imports our XFDF appearance and renders the saved PDF. */
class XfdfStampAppearanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun standardXfdfStampRoundTripsColorAlphaOrientationAndPlacement() {
        for (multiply in listOf(false, true)) {
            val folder = temporary.newFolder()
            val xml = StringWriter()
            XfdfStampAppearance.create(8, 6, folder, multiply = multiply) { y, row ->
                row.indices.forEach { x ->
                    row[x] = when {
                        y < 3 && x < 4 -> 0xffff0000.toInt()
                        y < 3 -> 0x8000ff00.toInt()
                        x < 4 -> 0x400000ff
                        else -> 0x00c0ffee // Hidden RGB must not travel with transparent pixels.
                    }
                }
            }.use { it.writeXml(xml) }
            assertTrue(folder.listFiles().isNullOrEmpty())
            val encoded = Base64.getEncoder().encodeToString(xml.toString().toByteArray(Charsets.UTF_8))
            val xfdf = """<?xml version="1.0" encoding="UTF-8"?>
                <xfdf xmlns="http://ns.adobe.com/xfdf/"><annots>
                <stamp page="0" name="masked" rect="16,20,48,44" opacity="1" flags="print">
                <appearance>$encoded</appearance></stamp></annots></xfdf>""".trimIndent()
            val dom = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xfdf.byteInputStream())
            val output = ByteArrayOutputStream()
            FDFDocument(dom).use { fdf ->
                val imported = fdf.catalog.fdf.annotations.single()
                PDDocument().use { document ->
                    val page = PDPage(PDRectangle(64f, 64f))
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.setNonStrokingColor(1f, 0f, 1f)
                        content.addRect(0f, 0f, 64f, 64f)
                        content.fill()
                    }
                    page.annotations.add(PDAnnotation.createAnnotation(imported.cosObject))
                    document.save(output)
                }
            }
            Loader.loadPDF(output.toByteArray()).use { document ->
                val annotation = document.getPage(0).annotations.single()
                assertNotNull(annotation.normalAppearanceStream)
                // Android's unit-test compilation uses android.jar, which does not
                // contain java.desktop. Invoke only the host-JDK rendering boundary
                // reflectively; import/save and the pixel assertions remain ordinary code.
                val rendered = PDFRenderer::class.java.getMethod("renderImage", Int::class.javaPrimitiveType)
                    .invoke(PDFRenderer(document), 0)
                val getRgb = rendered.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                fun color(x: Int, y: Int) = getRgb.invoke(rendered, x, y) as Int
                fun red(pixel: Int) = (pixel ushr 16) and 255
                fun green(pixel: Int) = (pixel ushr 8) and 255
                fun blue(pixel: Int) = pixel and 255
                val magenta = 0xffff00ff.toInt()
                assertEquals(0xffff0000.toInt(), color(24, 26))
                // Partial alpha is applied once, over the original page's magenta.
                val half = color(40, 26)
                assertTrue(red(half) in 126..128 && blue(half) in 126..128)
                assertTrue(if (multiply) green(half) == 0 else green(half) in 127..129)
                val quarter = color(24, 38)
                assertTrue(red(quarter) in 190..192 && green(quarter) == 0 && blue(quarter) == 255)
                assertEquals("The cutout must reveal existing content", magenta, color(40, 38))
                assertEquals("Stamp escaped its rectangle", magenta, color(8, 8))
                assertEquals("Stamp escaped its rectangle", magenta, color(50, 38))
            }
        }
    }

    @Test fun cancellationDeletesBothCompressedSpools() {
        val folder = temporary.newFolder()
        var rows = 0
        val failure = runCatching {
            XfdfStampAppearance.create(32, 32, folder,
                checkCancelled = { if (rows == 2) throw InterruptedIOException("cancelled") }) { _, row ->
                rows++
                row.fill(0xff00ff00.toInt())
            }
        }.exceptionOrNull()
        assertTrue(failure is InterruptedIOException)
        assertEquals(2, rows)
        assertTrue(folder.listFiles().isNullOrEmpty())
    }

    @Test fun rasterAllocationRejectsOversizedOrEmptyImagesBeforeCreatingSpools() {
        val folder = temporary.newFolder()
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 10, 4001 to 1000,
            Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertTrue(runCatching {
                XfdfStampAppearance.create(width, height, folder) { _, _ -> fail("row reader called") }
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(folder.listFiles().isNullOrEmpty())
    }
}
