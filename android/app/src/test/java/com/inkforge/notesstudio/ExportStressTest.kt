package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/** These tests run in a 96 MB JVM: allocating a 100/300 MB payload necessarily fails. */
class ExportStressTest{
    private fun digest(file:File):ByteArray=MessageDigest.getInstance("SHA-256").let{digest->
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};digest.digest()}
    @Test fun hundredAndThreeHundredMegabyteRoundTripsInSmallHeap(){
        val directory=Files.createTempDirectory("badnote-export-stress-").toFile()
        try{
            for(megabytes in listOf(100,300)){
                val source=File(directory,"source.bin");val random=Random(349);val block=ByteArray(65536)
                source.outputStream().buffered().use{out->repeat(megabytes*16){random.nextBytes(block);out.write(block)}}
                val before=digest(source);val archive=File(directory,"large.ifnote")
                var lastProgress=0L;val started=System.nanoTime()
                archive.outputStream().use{ArchiveCodec.write(it,sequenceOf(
                    ArchiveCodec.Entry("manifest.json"){ByteArrayInputStream("{\"version\":5}".toByteArray())},
                    ArchiveCodec.Entry("assets/source.bin"){source.inputStream()}
                ),progress={_,n->assertTrue(n>=lastProgress);lastProgress=n})}
                assertTrue(lastProgress>=source.length())
                source.delete() // Keep temporary disk use below 2x payload size.
                val restored=File(directory,"restored-$megabytes")
                archive.inputStream().use{ArchiveCodec.extract(it,restored)}
                assertEquals(megabytes*1024L*1024L,File(restored,"assets/source.bin").length())
                assertArrayEquals(before,digest(File(restored,"assets/source.bin")))
                println("STREAMING_EXPORT ${megabytes}MiB: ${(System.nanoTime()-started)/1000000} ms, heap limit ${Runtime.getRuntime().maxMemory()/1024/1024} MiB, SHA-256 verified")
                restored.deleteRecursively();source.delete();archive.delete()
            }
        }finally{directory.deleteRecursively()}
    }
    @Test fun legacyHundredMegabyteBase64ImportInSmallHeap(){
        val directory=Files.createTempDirectory("badnote-legacy-stress-").toFile()
        try{
            val input=File(directory,"legacy.ifnote");val block=ByteArray(65536).also{Random(947).nextBytes(it)}
            val expected=MessageDigest.getInstance("SHA-256")
            input.outputStream().buffered().use{out->
                out.write("{\"title\":\"legacy\",\"pages\":[{\"backgroundImage\":\"data:image/png;base64,".toByteArray())
                val shield=object:FilterOutputStream(out){override fun close(){flush()};override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len)}}
                Base64.getEncoder().wrap(shield).use{encoder->repeat(100*16){encoder.write(block);expected.update(block)}}
                out.write("\",\"objects\":[]}]}".toByteArray())
            }
            val restored=File(directory,"asset.png");var pages=0
            input.reader().use{LegacyJsonReader(it,{_->"asset:asset.png" to restored.outputStream()},{page->assertEquals("asset:asset.png",page.getString("backgroundImage"));pages++}).read()}
            assertEquals(1,pages);assertEquals(100*1024L*1024L,restored.length());assertArrayEquals(expected.digest(),digest(restored))
            println("LEGACY_IMPORT 100MiB: base64 decoded directly to disk, SHA-256 verified")
        }finally{directory.deleteRecursively()}
    }
}
