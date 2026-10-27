package com.inkforge.notesstudio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent native-format fixtures; invoked in a separate 96 MiB JVM. */
public final class PdfPageAssemblerChecks {
    private static final int MIB = 1024 * 1024;
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void text(RandomAccessFile file, String value) throws IOException {
        file.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }
    private static void fixture(File file, int page, int payloadMiB, String damage) throws IOException {
        byte[] content = ("% preserve stream reference 3 0 R\nq 1 0 0 rg 20 20 60 20 re f Q\n" +
            "BT /F1 12 Tf 20 80 Td (Page " + page + " keeps 3 0 R) Tj ET\n").getBytes(StandardCharsets.ISO_8859_1);
        long[] offsets = new long[9];
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(0); text(out, "%PDF-1.4\n%fixture\n");
            // Deliberately put the stream-length object after the stream, and physical IDs out of order.
            for (int number : new int[] {5, 1, 7, 2, 3, 4, 6, 8}) {
                offsets[number] = out.getFilePointer(); text(out, number + " 0 obj\n");
                switch (number) {
                    case 1: text(out, "<< /Type /Catalog /Pages 2 0 R >>"); break;
                    case 2: text(out, "<< /Type /Pages /Count 1 /Kids [3 0 R] >>"); break;
                    case 3:
                        text(out, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (page == 1 ? "240 320" : "320 240") +
                            "] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R " +
                            "/Opaque 8 0 R /Proof (Keep (3 0 R) \\(4 0 R\\)) /Hex <3320302052> /OddName /3#200#20R >>"); break;
                    case 4:
                        text(out, "<< /Length 6 0 R >>\nstream\n"); out.write(content); text(out, "\nendstream"); break;
                    case 5: text(out, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"); break;
                    case 6: text(out, Integer.toString(content.length)); break;
                    case 7: text(out, "<< /Producer (fixture contains 5 0 R) >>"); break;
                    case 8:
                        text(out, "<< /OpaquePayload true /Length " + ((long) payloadMiB * MIB) + " >>\nstream\n");
                        byte[] block = new byte[65536]; new Random(349).nextBytes(block);
                        // Include token-like bytes and all byte values in a binary stream.
                        byte[] marker = "endstream 3 0 R (not a token)".getBytes(StandardCharsets.ISO_8859_1);
                        System.arraycopy(marker, 0, block, 0, marker.length);
                        for (int i = 0; i < payloadMiB * 16; i++) out.write(block);
                        text(out, "\nendstream"); break;
                }
                text(out, "\nendobj\n");
            }
            long xref = out.getFilePointer();
            text(out, "xref\n0 9\n0000000000 65535 f \n");
            for (int i = 1; i < offsets.length; i++)
                text(out, String.format(Locale.US, "%010d %s n \n", offsets[i], damage.equals("generation") && i == 3 ? "00001" : "00000"));
            text(out, "trailer\n<< /Size 9 /Root " + (damage.equals("root") ? "3" : "1") + " 0 R " +
                (damage.equals("incremental") ? "/Prev 12 " : "") + ">>\nstartxref\n" +
                (damage.equals("xref") ? 12 : xref) + "\n%%EOF\n");
            if (damage.equals("truncated")) out.setLength(out.length() - 30);
        }
    }
    private static void failed(File output, File work, PdfPageAssembler.PageWriter writer,
                               PdfPageAssembler.Cancellation cancellation) throws Exception {
        boolean failed = false;
        try { PdfPageAssembler.write(output, work, 1, writer, cancellation); }
        catch (IOException expected) { failed = true; }
        check(failed, "Failure was swallowed"); check(!output.exists(), "Partial result leaked");
        check(Objects.requireNonNull(work.list()).length == 0, "Page spool leaked"); checks++;
    }
    private static byte[] expectedDigest(int countMiB) throws Exception {
        byte[] block = new byte[65536]; new Random(349).nextBytes(block);
        byte[] marker = "endstream 3 0 R (not a token)".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(marker, 0, block, 0, marker.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < countMiB * 16; i++) digest.update(block);
        return digest.digest();
    }
    private static void binaryDigests(File output, int countMiB) throws Exception {
        // Verify payloads by lengths, not by scanning binary bytes for endstream.
        try (RandomAccessFile input = new RandomAccessFile(output, "r")) {
            String line; int streams = 0;
            byte[] block = new byte[65536];
            while ((line = input.readLine()) != null) {
                if (!line.contains("/OpaquePayload true")) continue;
                check("stream".equals(input.readLine()), "Opaque stream delimiter");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long remaining = (long) countMiB * MIB;
                while (remaining > 0) {
                    int n = (int) Math.min(remaining, block.length); input.readFully(block, 0, n);
                    digest.update(block, 0, n); remaining -= n;
                }
                check(Arrays.equals(expectedDigest(countMiB), digest.digest()), "Binary SHA-256 changed"); streams++;
            }
            check(streams == 2, "Two binary streams expected");
        }
    }
    public static void main(String[] args) throws Exception {
        check(Runtime.getRuntime().maxMemory() <= 96L * MIB, "Stress run requires -Xmx96m");
        File root = new File(args[0]); check(root.mkdirs() || root.isDirectory(), "Fixture directory");
        File work = new File(root, "work"); check(work.mkdir() || work.isDirectory(), "Work directory");
        File small = new File(root, "two-pages.pdf");
        PdfPageAssembler.write(small, work, 2, (index, spool) -> fixture(spool, index + 1, 0, ""), () -> false);
        String data = new String(java.nio.file.Files.readAllBytes(small.toPath()), StandardCharsets.ISO_8859_1);
        check(data.contains("/Count 2"), "Page tree count");
        check(data.contains("/Proof (Keep (3 0 R) \\(4 0 R\\))"), "Literal strings were rewritten");
        check(data.contains("/Hex <3320302052>"), "Hex strings were rewritten");
        check(data.contains("% preserve stream reference 3 0 R"), "Content stream changed");
        check(data.contains("/OddName /3#200#20R"), "Names changed"); checks++;
        File large = new File(root, "large.pdf");
        long started = System.nanoTime();
        PdfPageAssembler.write(large, work, 2, (index, spool) -> fixture(spool, index + 1, 100, ""), () -> false);
        check(large.length() > 200L * MIB, "Stress PDF size"); binaryDigests(large, 100);
        check(large.delete(), "Stress output cleanup"); checks++;
        for (String damage : new String[] {"root", "generation", "incremental", "xref", "truncated"})
            failed(new File(root, "bad.pdf"), work, (index, spool) -> fixture(spool, 1, 0, damage), () -> false);
        failed(new File(root, "cancelled.pdf"), work, (index, spool) -> { throw new AssertionError("Cancelled writer started"); }, () -> true);
        File duringCopy = new File(root, "cancel-copy.pdf");
        failed(duringCopy, work, (index, spool) -> fixture(spool, 1, 100, ""), () -> duringCopy.length() > MIB);
        failed(new File(root, "failed-writer.pdf"), work, (index, spool) -> {
            try (FileOutputStream out = new FileOutputStream(spool)) { out.write(1); }
            throw new IOException("Injected page draw/write failure");
        }, () -> false);
        Thread.currentThread().interrupt();
        try { failed(new File(root, "interrupted.pdf"), work, (index, spool) -> fixture(spool, 1, 0, ""), () -> false); }
        finally { Thread.interrupted(); }
        check(Objects.requireNonNull(work.list()).length == 0, "Temporary page survived success");
        System.out.println("PDF_ASSEMBLER " + checks + " checks passed; 200 MiB stream SHA-256 preserved in " +
            (System.nanoTime() - started) / 1_000_000 + " ms; heap=" + Runtime.getRuntime().maxMemory() / MIB + " MiB");
    }
}
