package com.inkforge.notesstudio

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

/** Stable correctness and timing fixture for the library-wide OCR search ABI. */
internal object LibraryOcrSearchChecks {
    private const val DOCUMENTS = 128
    private const val PAGES_PER_DOCUMENT = 10
    private const val POLICY = "ko-primary"
    private const val QUERY = "жар птица"

    fun run(context: Context, output: Bundle, verifyCancellation: Boolean) {
        val database = "library-ocr-search-${UUID.randomUUID()}.db"
        try {
            NoteRepository(context, database).use { repository ->
                val expected = ArrayList<String>()
                repository.transaction {
                    repeat(DOCUMENTS) { documentIndex ->
                        val pages = List(PAGES_PER_DOCUMENT) { NotePage.blank("blank") }
                        val document = repository.createFromPages("Search fixture $documentIndex", "root") { append ->
                            pages.forEach(append)
                        }
                        if (documentIndex % 17 == 0) expected += document.id
                        pages.forEachIndexed { pageIndex, page ->
                            val match = documentIndex % 17 == 0 && pageIndex in setOf(2, 7)
                            store(repository, document.id, page,
                                if (match) listOf("ЖАР", "ПТИЦА") else listOf("fixture-$documentIndex-$pageIndex", "ordinary"))
                        }
                    }
                }

                repeat(3) { checkResult(repository, expected) }
                val millis = List(9) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    checkResult(repository, expected)
                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }.sorted()

                if (verifyCancellation) {
                    var checks = 0
                    val stopped = runCatching {
                        repository.searchOcrDocuments(QUERY) { ++checks == 64 }
                    }.exceptionOrNull()
                    check(stopped is CancellationException && checks == 64) { "Superseded OCR scan continued" }
                    checkResult(repository, expected)
                    output.putString("libraryOcrSearchCancellation", "cancelled_mid_scan_and_restarted")
                }
                verifyBoundaries(repository)
                output.putInt("libraryOcrSearchDocuments", DOCUMENTS)
                output.putInt("libraryOcrSearchPages", DOCUMENTS * PAGES_PER_DOCUMENT)
                output.putInt("libraryOcrSearchMatches", expected.size)
                output.putString("libraryOcrSearchMs", millis.joinToString(",") { String.format(Locale.US, "%.3f", it) })
            }
        } finally {
            check(context.deleteDatabase(database) || !context.getDatabasePath(database).exists()) {
                "libraryOcrSearchFixtureNotDeleted"
            }
        }
    }

    private fun checkResult(repository: NoteRepository, expected: List<String>) {
        val actual = repository.searchOcrDocuments(QUERY).toList()
        check(actual == expected) { "libraryOcrSearchOrderOrDedup: expected=$expected actual=$actual" }
    }

    private fun verifyBoundaries(repository: NoteRepository) {
        val literal = pageFixture(repository, "literal %_ token")
        check(repository.searchOcrDocuments("%_") == setOf(literal.first.id)) { "wildcardsWereNotLiteral" }

        val stale = pageFixture(repository, "stale generation token")
        repository.putPage(stale.first.id, stale.second, 0)
        check(stale.first.id !in repository.searchOcrDocuments("stale generation")) { "staleGenerationMatched" }

        val corrected = pageFixture(repository, "raw override token")
        check(repository.correctOcrText(corrected.second.id, "corrected replacement token", corrected.third))
        check(repository.searchOcrDocuments("corrected replacement") == setOf(corrected.first.id))
        check(corrected.first.id !in repository.searchOcrDocuments("raw override")) { "correctionDidNotOverrideRaw" }
        check(repository.correctOcrText(corrected.second.id, "", corrected.third))
        check(corrected.first.id !in repository.searchOcrDocuments("corrected replacement")) { "emptyCorrectionWasIgnored" }

        val largeCorrections = buildSet {
            repeat(4) {
                val fixture = pageFixture(repository, "raw large correction")
                check(repository.correctOcrText(fixture.second.id,
                    "x".repeat(48 * 1024) + " batched replacement token", fixture.third))
                add(fixture.first.id)
            }
        }
        check(repository.searchOcrDocuments("batched replacement token") == largeCorrections)

        val unbound = pageFixture(repository, "bound raw token", "unbound poison token")
        check(unbound.first.id !in repository.searchOcrDocuments("unbound poison")) { "unboundTopLevelCorrectionMatched" }
        check(repository.searchOcrDocuments("bound raw") == setOf(unbound.first.id))
    }

    private fun pageFixture(repository: NoteRepository, text: String, unboundCorrection: String? = null): Triple<DocumentInfo, NotePage, String> {
        val page = NotePage.blank("blank")
        val document = repository.createFromPages("Boundary fixture", "root") { it(page) }
        val digest = store(repository, document.id, page, listOf(text), unboundCorrection)
        return Triple(document, page, digest)
    }

    private fun store(repository: NoteRepository, documentId: String, page: NotePage,
                      texts: List<String>, unboundCorrection: String? = null): String {
        val digest = OcrInput.pageDigest(requireNotNull(repository.page(page.id)), POLICY)
        val regions = JSONArray(texts.mapIndexed { index, text ->
            json("id" to "region-$index", "status" to "complete", "order" to 0,
                "selectedText" to text, "inputDigest" to "input-$index", "strokeIds" to JSONArray())
        })
        val result = json("pageDigest" to digest, "status" to "complete", "regions" to regions)
        unboundCorrection?.let { result.put("correctedText", it) }
        check(repository.putOcrResult(documentId, page.id, repository.generation(page.id), digest, POLICY, result)) {
            "libraryOcrFixtureRejected"
        }
        return digest
    }
}
