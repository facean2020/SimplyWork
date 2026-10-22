package com.yangz3720.simplywork.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload → text factory tests (v1.8). Everything here runs on the real providers -- no mock: the whole point
 * of the factory is that a format is decided by name/MIME and the text comes out of the real bytes.
 */
class DocumentExtractorFactoryTest {

    private val factory = DocumentExtractorFactory()

    private fun document(name: String, mimeType: String = "", text: String) =
        UploadDocument(name = name, mimeType = mimeType, bytes = text.toByteArray(Charsets.UTF_8))

    @Test
    fun `markdown and txt are extracted as plain text`() {
        val markdown = factory.extract(document("notes.md", "text/markdown", "# 标题\n正文")) as DocumentExtraction.Success
        val plain = factory.extract(document("todo.txt", "text/plain", "买牛奶")) as DocumentExtraction.Success

        assertEquals("# 标题\n正文", markdown.text)
        assertFalse(markdown.truncated)
        assertEquals("买牛奶", plain.text)
        assertEquals("notes.md", markdown.name)
    }

    @Test
    fun `a file whose mime type is missing still dispatches by extension`() {
        // The document picker reports application/octet-stream for some providers; the extension has to win there.
        val extraction = factory.extract(document("设计.md", "application/octet-stream", "内容"))
        assertTrue(extraction is DocumentExtraction.Success)
        assertEquals("内容", (extraction as DocumentExtraction.Success).text)
    }

    @Test
    fun `pdf and word are reported as unsupported rather than failed`() {
        // "not supported yet" and "this file is broken" are different answers for the user; pdf/word land in
        // the first bucket until their providers exist.
        assertEquals(
            DocumentExtraction.Unsupported("a.pdf"),
            factory.extract(document("a.pdf", "application/pdf", "x"))
        )
        assertTrue(
            factory.extract(document("b.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"))
                is DocumentExtraction.Unsupported
        )
    }

    @Test
    fun `adding a format is one provider and first match wins`() {
        // The extension point the factory exists for: a pdf provider changes nothing about callers.
        val pdf = object : DocumentTextExtractor {
            override val id = "pdf"
            override fun supports(document: UploadDocument) = document.mimeType == "application/pdf"
            override fun extract(document: UploadDocument) = DocumentExtraction.Success(document.name, "[pdf text]")
        }
        val withPdf = DocumentExtractorFactory(DocumentExtractorFactory.defaults() + pdf)

        assertEquals(DocumentExtraction.Success("a.pdf", "[pdf text]"), withPdf.extract(document("a.pdf", "application/pdf", "x")))
        assertEquals("pdf", withPdf.extractorFor(document("a.pdf", "application/pdf", "x"))?.id)
        // The providers that were already registered keep working.
        assertEquals("plain-text", withPdf.extractorFor(document("a.md", "text/markdown", "x"))?.id)
    }

    @Test
    fun `a provider that claims the format wins over the plain text provider`() {
        // Order is priority: a specialised provider registered first must not be shadowed by the generic one.
        val specialised = object : DocumentTextExtractor {
            override val id = "specialised"
            override fun supports(document: UploadDocument) = document.name.endsWith(".md")
            override fun extract(document: UploadDocument) = DocumentExtraction.Success(document.name, "special")
        }
        val ordered = DocumentExtractorFactory(listOf(specialised) + DocumentExtractorFactory.defaults())

        assertEquals(DocumentExtraction.Success("a.md", "special"), ordered.extract(document("a.md", "text/markdown", "x")))
    }

    @Test
    fun `long documents are capped and the truncation is declared`() {
        val cap = 10
        val extractor = PlainTextDocumentExtractor(maxChars = cap)
        val extraction = extractor.extract(UploadDocument("big.txt", "text/plain", "0123456789ABCDEF".toByteArray())) as DocumentExtraction.Success

        assertEquals("0123456789", extraction.text)
        assertTrue(extraction.truncated)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // A half-written emoji renders as a replacement glyph in the prompt; the head is cut one char earlier.
        val extractor = PlainTextDocumentExtractor(maxChars = 3)
        val extraction = extractor.extract(
            UploadDocument("emoji.txt", "text/plain", "ab\uD83D\uDE00".toByteArray(Charsets.UTF_8))
        ) as DocumentExtraction.Success

        assertEquals("ab", extraction.text)
        assertTrue(extraction.truncated)
    }

    @Test
    fun `binary and empty content fail with a reason`() {
        val binary = factory.extract(UploadDocument("a.txt", "text/plain", byteArrayOf(0x41, 0x00, 0x42)))
        val empty = factory.extract(UploadDocument("b.txt", "text/plain", ByteArray(0)))

        assertTrue(binary is DocumentExtraction.Failed && binary.reason.contains("二进制"))
        assertTrue(empty is DocumentExtraction.Failed && empty.reason == "文件为空")
    }

    @Test
    fun `a BOM is stripped so it does not leak into the prompt`() {
        val extraction = factory.extract(
            UploadDocument("bom.md", "text/markdown", "\uFEFF# 标题".toByteArray(Charsets.UTF_8))
        ) as DocumentExtraction.Success
        assertEquals("# 标题", extraction.text)
    }

    @Test
    fun `upload kinds split images from documents and reject the rest`() {
        assertEquals(UploadKind.Image, UploadKinds.of("photo.JPG", "image/jpeg"))
        assertEquals(UploadKind.Image, UploadKinds.of("photo.heic", "application/octet-stream"))
        assertEquals(UploadKind.Document, UploadKinds.of("notes.md", ""))
        assertEquals(UploadKind.Document, UploadKinds.of("notes", "text/markdown"))
        assertNull(UploadKinds.of("report.pdf", "application/pdf"))
        assertNull(UploadKinds.of("archive.zip", "application/zip"))
    }
}
