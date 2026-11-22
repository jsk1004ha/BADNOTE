package com.inkforge.notesstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object S5cOcrChecks {
    fun run(context: Context) {
        OcrScopeChecks.run(context)
        OcrWordDuplicationChecks.run(context)
        val name = "ocr-s5c-${UUID.randomUUID()}.db"
        NoteRepository(context, name).use { repository ->
            val doc = repository.create("S5c OCR boundary", "root", "grid")
            val page = requireNotNull(repository.page(repository.pageIds(doc.id).single()))
            page.objects += json("id" to "invalid", "type" to "stroke", "points" to JSONArray())
            repository.putPage(doc.id, page, 0)
            val recognition = RecognitionService()
            val coordinator = PageOcrCoordinator(repository, recognition)
            try {
                val completed = CountDownLatch(1)
                val receipt = AtomicReference<PageOcrCoordinator.Receipt>()
                coordinator.request(doc.id, page.id, "ko-primary", false,
                    progress = { stage, _, _ -> if (stage == "saving") coordinator.cancel(page.id) },
                    complete = { receipt.set(it); completed.countDown() })
                check(completed.await(20, TimeUnit.SECONDS)) { "OCR cancel-at-saving timed out" }
                check(receipt.get()?.errorCode == "cancelled" && receipt.get()?.applied == false)
                check(repository.ocrResult(page.id) == null) { "Cancel-at-saving persisted an OCR sidecar" }
            } finally { coordinator.close(); recognition.close() }

            fun stroke(x: Float) = json("id" to "original", "type" to "stroke", "brush" to "fountain",
                "points" to JSONArray(listOf(InkPoint(x, 100f, .4f, 10).json(),
                    InkPoint(x + 12f, 108f, .8f, 20).json())))
            fun region(): PageOcrLayout.Region {
                val stored = requireNotNull(repository.page(page.id))
                return PageOcrLayout.analyze(stored.objects, stored.width, stored.height).regions.single()
            }
            fun input(region: PageOcrLayout.Region) = OcrInput.regionInputDigest(region, "ko", "",
                region.bounds.width.coerceAtLeast(1f), region.bounds.height.coerceAtLeast(1f), region.timeMode)
            fun result(region: PageOcrLayout.Region, digest: String, generation: String): JSONObject =
                json("pageDigest" to digest, "modelGeneration" to generation, "status" to "complete",
                    "regions" to JSONArray(listOf(json("id" to region.id,
                        "strokeIds" to JSONArray(region.strokes.map { it.id }),
                        "sourceIdentityDigest" to OcrInput.sourceIdentityDigest(region),
                        "inputDigest" to input(region), "status" to "complete",
                        "rawText" to "raw", "selectedText" to "raw"))))
            page.objects.clear(); page.objects += stroke(100f)
            repository.putPage(doc.id, page, 0)
            val original = region()
            val originalDigest = input(original)
            val pageDigest = OcrInput.pageDigest(requireNotNull(repository.page(page.id)), "ko-primary")
            val modelBefore = repository.modelGeneration("ko")
            val cacheBefore = OcrInput.regionKey(original, "ko", "", original.bounds.width.coerceAtLeast(1f),
                original.bounds.height.coerceAtLeast(1f), original.timeMode, modelBefore)
            check(repository.putOcrResult(doc.id, page.id, repository.generation(page.id), pageDigest,
                "ko-primary", result(original, pageDigest, "ko:$modelBefore")))
            check(repository.ocrText(page.id) == "raw")
            check(repository.searchDocumentPages(doc.id, "raw").single().index == 0)
            check(doc.id in repository.searchOcrDocuments("raw"))
            check(repository.correctOcrRegion(page.id, original.id, "user correction", originalDigest, pageDigest))
            check(repository.ocrText(page.id) == "user correction")
            check(repository.searchDocumentPages(doc.id, "user correction").single().snippet.contains("user correction"))
            check(doc.id in repository.searchOcrDocuments("user correction"))
            check(doc.id !in repository.searchOcrDocuments("raw"))
            repository.invalidateModelGeneration("ko")
            val modelAfter = repository.modelGeneration("ko")
            val cacheAfter = OcrInput.regionKey(original, "ko", "", original.bounds.width.coerceAtLeast(1f),
                original.bounds.height.coerceAtLeast(1f), original.timeMode, modelAfter)
            check(modelAfter == modelBefore + 1 && cacheAfter != cacheBefore) {
                "Known model lifecycle change must invalidate the disposable OCR cache key"
            }
            check(repository.putOcrResult(doc.id, page.id, repository.generation(page.id), pageDigest,
                "ko-primary", result(original, pageDigest, "ko:$modelAfter")))
            check(repository.ocrResult(page.id)!!.array("regions").getJSONObject(0).optString("correctedText") == "user correction") {
                "Unchanged original input retains the user correction across model generation"
            }
            val differentEngine = result(original,pageDigest,"ko:$modelAfter")
            differentEngine.array("regions").getJSONObject(0).put("inputDigest","different-engine-input")
            check(repository.putOcrResult(doc.id,page.id,repository.generation(page.id),pageDigest,
                "ko-primary",differentEngine))
            check(repository.ocrResult(page.id)!!.array("regions").getJSONObject(0).optString("correctedText") == "user correction") {
                "Identical source must retain correction when only engine input changes"
            }
            val copy = repository.duplicatePage(doc.id,page.id,1)
            check(repository.ocrResult(copy.id)!!.array("regions").getJSONObject(0).optString("correctedText") == "user correction") {
                "Attached correction must follow the duplicated original, with new source IDs"
            }
            // Keep the duplicate valid but distinct so the stale-original search assertion is isolated.
            val copiedRegion = repository.ocrResult(copy.id)!!.array("regions").getJSONObject(0).getString("id")
            check(repository.correctOcrRegion(copy.id,copiedRegion,"copied correction"))
            check(repository.ocrText(copy.id).contains("copied correction"))
            val snapshot = requireNotNull(repository.readOcrSnapshot(doc.id,page.id))
            check(repository.readOcrSnapshot("wrong-document",page.id) == null)
            val beforeUndo = requireNotNull(repository.page(page.id))
            val changedOnce = beforeUndo.json().let(NotePage::from)
            changedOnce.objects[0].array("points").getJSONObject(0).put("x",444)
            repository.putPage(doc.id,changedOnce,0)
            check(repository.ocrText(page.id).isEmpty())
            check(doc.id !in repository.searchOcrDocuments("user correction"))
            repository.putPage(doc.id,beforeUndo,0)
            check(OcrInput.pageDigest(requireNotNull(repository.page(page.id)),"ko-primary") == pageDigest)
            check(!repository.putOcrResult(doc.id,page.id,snapshot.generation,pageDigest,"ko-primary",differentEngine)) {
                "Undo restoring identical bytes must not accept a result from an earlier snapshot"
            }
            val changed = requireNotNull(repository.page(page.id))
            changed.objects.clear(); changed.objects += stroke(220f)
            repository.putPage(doc.id, changed, 0)
            val newRegion = region()
            val newDigest = OcrInput.pageDigest(requireNotNull(repository.page(page.id)), "ko-primary")
            check(input(newRegion) != originalDigest)
            check(repository.putOcrResult(doc.id, page.id, repository.generation(page.id), newDigest,
                "ko-primary", result(newRegion, newDigest, "ko:$modelAfter")))
            check(!repository.correctOcrRegion(page.id, original.id, "stale dialog", originalDigest, pageDigest)) {
                "A dialog opened for old ink must not correct a new region"
            }
            check(repository.ocrResult(page.id)!!.array("regions").getJSONObject(0).optString("correctedText").isEmpty()) {
                "Geometry change must not reattach the old user correction"
            }
            val detachedCopy = repository.duplicatePage(doc.id,page.id,1)
            check(repository.ocrResult(detachedCopy.id)!!.array("regions").getJSONObject(0).optString("correctedText").isEmpty()) {
                "Duplicating newer ink must not resurrect a detached correction from old ink"
            }
            repository.deleteDocument(doc.id)
        }
        context.deleteDatabase(name)
    }
}
