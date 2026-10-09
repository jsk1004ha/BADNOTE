package com.inkforge.notesstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real MediaPlayer decoder and lifecycle checks on named synthetic containers. */
internal object MediaCompatibilityChecks {
    fun run(context: Context) {
        val fixtures = File(context.filesDir, "media-fixtures")
        val manifest = JSONObject(File(fixtures, "manifest.json").readText(Charsets.UTF_8)).getJSONObject("files")
        val names = listOf("aac.m4a", "vorbis.ogg", "opus.ogg", "vorbis.webm", "opus.webm",
            "legacy-aac.bin", "malformed.bin")
        fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        names.forEach { name ->
            val file = File(fixtures, name)
            check(file.isFile && sha(file) == manifest.getJSONObject(name).getString("sha256")) { "Media fixture changed: $name" }
        }
        val database = "media-s5c-${UUID.randomUUID()}.db"
        NoteRepository(context, database).use { repository ->
            names.forEach { name -> File(fixtures, name).copyTo(repository.asset("s5c-$name"), overwrite = true) }
            val recordedId="recording-fixture-${UUID.randomUUID()}.m4a"
            val recordedPending=repository.asset("$recordedId.pending")
            File(fixtures,"aac.m4a").copyTo(recordedPending)
            val recordingDoc=repository.create("Detached recording", "root", "blank")
            recordingDoc.data.remove("audio") // Older documents may have no audio array yet.
            recordingDoc.data.put("unrelatedMetadata", "preserved")
            repository.putDocument(recordingDoc)
            val detached=AudioController(context,repository)
            detached.close() // The UI owner can end before its queued file work completes.
            val recording=AudioController.PendingRecording(recordedId,"page-before-navigation",0,1000,3000)
            val latestBeforeRecording=requireNotNull(repository.document(recordingDoc.id))
            latestBeforeRecording.data.put("lateEdit", "kept")
            repository.putDocument(latestBeforeRecording)
            val committed=requireNotNull(repository.executor.submit<JSONObject?>{
                detached.persistRecording(recording,recordingDoc.id)
            }.get(10,TimeUnit.SECONDS))
            check(!recordedPending.exists() && sha(repository.asset(recordedId))==sha(File(fixtures,"aac.m4a"))) {
                "Recorded AAC bytes were not finalized intact"
            }
            val stored=requireNotNull(repository.document(recordingDoc.id))
            check(stored.data.optString("unrelatedMetadata")=="preserved" && stored.data.optString("lateEdit")=="kept" &&
                stored.data.array("audio").objects().single().getString("src")=="asset:$recordedId" &&
                committed.getString("pageId")=="page-before-navigation") { "Detached recording lost document metadata or page binding" }
            repository.deleteDocument(recordingDoc.id)
            repository.asset(recordedId).delete()
            val queuedDoc=repository.create("Recording save order", "root", "blank")
            queuedDoc.data.put("unknownMetadata", "retained")
            queuedDoc.data.put("variables",json("prior" to 3))
            queuedDoc.data.put("audio",JSONArray(listOf(json("id" to "old-clip", "src" to "asset:s5c-aac.m4a"),"opaque-audio-item")))
            repository.putDocument(queuedDoc)
            val staleBody=requireNotNull(repository.document(queuedDoc.id)).data.copyJson()
            val queuedId="recording-queued-${UUID.randomUUID()}.m4a"
            File(fixtures,"aac.m4a").copyTo(repository.asset("$queuedId.pending"))
            val held=CountDownLatch(1)
            val release=CountDownLatch(1)
            repository.executor.execute { held.countDown();release.await(10,TimeUnit.SECONDS) }
            check(held.await(5,TimeUnit.SECONDS)) { "Recording save executor was not held" }
            val commit=repository.executor.submit<JSONObject?>{
                detached.persistRecording(AudioController.PendingRecording(queuedId,"queued-page",1,1000,3000),queuedDoc.id)
            }
            val variable=repository.executor.submit { MainActivity.saveCalculatedVariable(repository,queuedDoc.id,"calculated",17.0) }
            val delete=repository.executor.submit { MainActivity.removeAudioClip(repository,queuedDoc.id,"old-clip") }
            try {
                check(staleBody.array("audio").objects().none { it.optString("src")=="asset:$queuedId" })
                release.countDown()
                check(commit.get(10,TimeUnit.SECONDS)!=null)
                variable.get(10,TimeUnit.SECONDS);delete.get(10,TimeUnit.SECONDS)
                val current=requireNotNull(repository.document(queuedDoc.id)).data
                val clips=current.array("audio")
                check(current.optString("unknownMetadata")=="retained" &&
                    current.getJSONObject("variables").optInt("prior")==3 &&
                    current.getJSONObject("variables").optDouble("calculated")==17.0 &&
                    clips.objects().none{it.optString("id")=="old-clip"} &&
                    clips.objects().single().optString("src")=="asset:$queuedId" &&
                    (0 until clips.length()).any{clips.opt(it)=="opaque-audio-item"}) {
                    "Queued variable or clip deletion overwrote a newer recording or unrelated metadata"
                }
            } finally { release.countDown();repository.deleteDocument(queuedDoc.id)
                repository.asset("$queuedId.pending").delete();repository.asset(queuedId).delete() }
            for((label,bytes) in listOf("empty" to ByteArray(0), "corrupt" to File(fixtures,"malformed.bin").readBytes())) {
                val id="recording-$label-${UUID.randomUUID()}.m4a"
                repository.asset("$id.pending").writeBytes(bytes)
                val invalid=AudioController.PendingRecording(id,"",0,1000,1000)
                check(runCatching { detached.finalizeRecording(invalid) }.isFailure) {
                    "$label recording reached finalization"
                }
                check(!repository.asset(id).exists()&&!repository.asset("$id.pending").exists()) {
                    "$label recording left a final or pending file"
                }
            }
            val missingId="recording-missing-${UUID.randomUUID()}.m4a"
            File(fixtures,"aac.m4a").copyTo(repository.asset("$missingId.pending"))
            check(detached.persistRecording(AudioController.PendingRecording(missingId,"",0,1000,3000),"missing-document")==null)
            check(!repository.asset(missingId).exists()&&!repository.asset("$missingId.pending").exists()) {
                "Deleted document retained an unfinished recording"
            }
            val closeId="recording-close-${UUID.randomUUID()}.m4a"
            val oldFinal=byteArrayOf(8,4,2)
            repository.asset(closeId).writeBytes(oldFinal)
            repository.asset("$closeId.pending").writeBytes(byteArrayOf(1,2,3))
            try {
                val abandoned=AudioController(context,repository)
                AudioController::class.java.getDeclaredField("assetId").apply{isAccessible=true}.set(abandoned,closeId)
                abandoned.close()
                check(!repository.asset("$closeId.pending").exists() && repository.asset(closeId).readBytes().contentEquals(oldFinal)) {
                    "Close removed an existing asset or retained an unfinished recording"
                }
            } finally { repository.asset("$closeId.pending").delete();repository.asset(closeId).delete() }
            val clip: (String) -> JSONObject = { name -> json("src" to "asset:s5c-$name", "mime" to manifest.getJSONObject(name).getString("mime")) }
            AudioController(context, repository).usePlayback { audio ->
                fun playOne(name: String, required: Boolean) {
                    val done = CountDownLatch(1)
                    val outcome = AtomicReference<String>()
                    val error = AtomicReference<String>()
                    audio.onPlaybackState = { state ->
                        if (state == "completed" || state == "failed") { outcome.set(state); done.countDown() }
                    }
                    audio.onPlaybackError = { error.set(it); outcome.set("failed"); done.countDown() }
                    audio.play(clip(name))
                    check(done.await(8, TimeUnit.SECONDS)) { "Media playback timed out: $name" }
                    if (required) check(outcome.get() == "completed") { "$name failed: ${error.get()}" }
                    else check(outcome.get() == "completed" ||
                        outcome.get() == "failed" && !error.get().isNullOrBlank()) {
                        "Unsupported $name must report an explicit error"
                    }
                }
                playOne("aac.m4a", true)
                playOne("vorbis.ogg", true)
                playOne("opus.ogg", false)
                playOne("vorbis.webm", false)
                playOne("opus.webm", false)
                playOne("legacy-aac.bin", true) // Decoder inspects bytes, not a filename gate.
                playOne("malformed.bin", false)
                playOne("aac.m4a", true) // Corrupt input cannot poison the next player.

                val replacementDone = CountDownLatch(1)
                val completions = AtomicInteger()
                val replacementError = AtomicReference<String>()
                audio.onPlaybackState = { if (it == "completed") { completions.incrementAndGet(); replacementDone.countDown() } }
                audio.onPlaybackError = { replacementError.set(it); replacementDone.countDown() }
                audio.play(clip("aac.m4a")); audio.play(clip("vorbis.ogg"))
                check(replacementDone.await(8, TimeUnit.SECONDS) && completions.get() == 1 && replacementError.get() == null) {
                    "Rapid clip replacement must complete only the latest player: ${replacementError.get()}"
                }
            }
            fun closeDuringPlayback(waitUntilPlaying: Boolean) {
                val audio = AudioController(context, repository)
                val playing = CountDownLatch(1)
                val terminal = AtomicInteger()
                audio.onPlaybackState = { state ->
                    if (state == "playing") playing.countDown()
                    if (state == "completed" || state == "failed") terminal.incrementAndGet()
                }
                audio.onPlaybackError = { terminal.incrementAndGet() }
                audio.play(clip("aac.m4a"))
                if (waitUntilPlaying) check(playing.await(5, TimeUnit.SECONDS)) { "Media never prepared" }
                audio.close()
                Thread.sleep(1800)
                check(terminal.get() == 0) { "Closed player delivered a stale callback" }
            }
            closeDuringPlayback(false)
            closeDuringPlayback(true)

            val doc = repository.create("Media MIME roundtrip", "root", "grid")
            val audio = JSONArray(listOf(clip("vorbis.ogg"), clip("opus.webm")))
            doc.data.put("audio", audio)
            repository.putDocument(doc)
            val legacy = File(context.cacheDir, "media-s5c-legacy.json")
            try {
                repository.exportLegacy(doc.id, legacy)
                val text = legacy.readText(Charsets.UTF_8)
                check("data:audio/ogg;base64," in text && "data:audio/webm;base64," in text) {
                    "Legacy JSON must preserve Ogg/WebM container MIME"
                }
                val copy = legacy.inputStream().use { repository.importNote(it, "root") }
                val imported = requireNotNull(repository.document(copy.id)).data.getJSONArray("audio")
                check(imported.getJSONObject(0).getString("src").endsWith(".ogg") &&
                    imported.getJSONObject(1).getString("src").endsWith(".webm")) {
                    "Legacy import must retain audio container extensions"
                }
                repository.deleteDocument(copy.id)
            } finally { legacy.delete(); repository.deleteDocument(doc.id) }
            names.forEach { repository.asset("s5c-$it").delete() }
        }
        context.deleteDatabase(database)
    }

    private inline fun AudioController.usePlayback(block: (AudioController) -> Unit) {
        try { block(this) } finally { close() }
    }
}
