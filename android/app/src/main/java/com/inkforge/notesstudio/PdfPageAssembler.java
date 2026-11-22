package com.inkforge.notesstudio;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins single-page PDFs emitted by Android PdfDocument, without retaining their
 * streams or rewriting binary/font/image data. This is deliberately NOT a general
 * PDF importer: classic xrefs, generation zero and one flat page tree are required.
 * Imported user PDFs are rendered by PdfRenderer before reaching this class.
 */
public final class PdfPageAssembler {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_OBJECTS_PER_PAGE = 100_000;
    private static final Pattern ROOT = Pattern.compile("/Root\\s+(\\d+)\\s+0\\s+R");

    @FunctionalInterface public interface PageWriter {
        void write(int index, File spool) throws IOException;
    }
    @FunctionalInterface public interface Cancellation {
        boolean isCancelled();
    }

    private final OutputStream out;
    private final Cancellation cancellation;
    private final List<Long> offsets = new ArrayList<>();
    private final List<Integer> pages = new ArrayList<>();
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private long position;

    private PdfPageAssembler(OutputStream out, Cancellation cancellation) throws IOException {
        this.out = out;
        this.cancellation = cancellation;
        offsets.add(0L); offsets.add(0L); offsets.add(0L); // free, catalog, page tree
        text("%PDF-1.4\n%");
        bytes(new byte[] {(byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3}, 4);
        text("\n");
    }

    public static void write(File destination, File workDirectory, int pageCount,
                             PageWriter pageWriter, Cancellation cancellation) throws IOException {
        if (pageCount < 1) throw new IOException("PDF에 내보낼 페이지가 없습니다.");
        if (!workDirectory.isDirectory() && !workDirectory.mkdirs())
            throw new IOException("PDF 임시 폴더를 만들 수 없습니다.");
        boolean complete = false;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), BUFFER_SIZE)) {
                PdfPageAssembler assembler = new PdfPageAssembler(out, cancellation);
                for (int index = 0; index < pageCount; index++) {
                    assembler.checkCancelled();
                    File spool = File.createTempFile("pdf-vector-page-", ".pdf", workDirectory);
                    try {
                        pageWriter.write(index, spool);
                        assembler.checkCancelled();
                        assembler.append(spool);
                    } finally { spool.delete(); }
                }
                assembler.finish();
                assembler.checkCancelled();
            }
            complete = true;
        } finally { if (!complete) destination.delete(); }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("내보내기를 취소했습니다.");
    }
    private void bytes(byte[] value, int count) throws IOException {
        checkCancelled(); out.write(value, 0, count); position += count;
    }
    private void text(String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.ISO_8859_1);
        bytes(encoded, encoded.length);
    }
    private void start(int number) throws IOException {
        offsets.set(number, position); text(number + " 0 obj\n");
    }

    private static final class Entry {
        final int number;
        final long offset;
        long end;
        String type;
        long streamLength = -1;
        Entry(int number, long offset) { this.number = number; this.offset = offset; }
    }

    private void append(File spool) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(spool, "r")) {
            long length = input.length();
            if (length < 32) throw new IOException("잘린 PDF 페이지입니다.");
            input.seek(0);
            String header = input.readLine();
            if (header == null || !header.startsWith("%PDF-1.")) throw new IOException("지원하지 않는 PDF 페이지입니다.");
            input.seek(Math.max(0, length - 4096));
            byte[] tail = new byte[(int) (length - input.getFilePointer())]; input.readFully(tail);
            String ending = new String(tail, StandardCharsets.ISO_8859_1);
            Matcher marker = Pattern.compile("startxref\\s+(\\d+)\\s+%%EOF\\s*$").matcher(ending);
            if (!marker.find()) throw new IOException("PDF 페이지의 끝을 찾지 못했습니다.");
            long xref = Long.parseLong(marker.group(1));
            if (xref <= 0 || xref >= length) throw new IOException("잘못된 PDF xref 위치입니다.");
            input.seek(xref);
            if (!"xref".equals(line(input))) throw new IOException("PDF 페이지의 xref 형식을 지원하지 않습니다.");
            Map<Integer, Entry> entries = new HashMap<>();
            int total = 0;
            String section;
            while (!"trailer".equals(section = line(input))) {
                checkCancelled();
                String[] range = section.split("\\s+");
                if (range.length != 2) throw new IOException("잘못된 PDF xref 구간입니다.");
                int first = integer(range[0]); int count = integer(range[1]);
                if (count < 1 || first > MAX_OBJECTS_PER_PAGE - count ||
                        (total += count) > MAX_OBJECTS_PER_PAGE) throw new IOException("PDF 페이지 객체 한도를 초과했습니다.");
                for (int i = 0; i < count; i++) {
                    String[] fields = line(input).split("\\s+");
                    if (fields.length != 3) throw new IOException("잘못된 PDF xref 항목입니다.");
                    if ("f".equals(fields[2])) continue;
                    if (!"n".equals(fields[2]) || integer(fields[1]) != 0)
                        throw new IOException("PDF 객체 세대를 지원하지 않습니다.");
                    long offset = Long.parseLong(fields[0]);
                    int number = first + i;
                    if (number == 0 || offset < 9 || offset >= xref || entries.put(number, new Entry(number, offset)) != null)
                        throw new IOException("잘못된 PDF 객체 위치입니다.");
                }
            }
            StringBuilder trailer = new StringBuilder();
            while (!(section = line(input)).equals("startxref")) {
                trailer.append(section).append(' ');
                if (trailer.length() > BUFFER_SIZE) throw new IOException("PDF trailer 한도를 초과했습니다.");
            }
            String trailerText = trailer.toString();
            if (trailerText.contains("/Encrypt") || trailerText.contains("/Prev") || trailerText.contains("/XRefStm"))
                throw new IOException("암호화 또는 증분 PDF 페이지는 지원하지 않습니다.");
            Matcher root = ROOT.matcher(trailerText);
            if (!root.find()) throw new IOException("PDF 페이지의 루트가 없습니다.");
            int rootNumber = integer(root.group(1));
            List<Entry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparingLong(entry -> entry.offset));
            for (int i = 0; i < ordered.size(); i++) {
                Entry entry = ordered.get(i);
                entry.end = i + 1 < ordered.size() ? ordered.get(i + 1).offset : xref;
                if (entry.end <= entry.offset) throw new IOException("중복된 PDF 객체 위치입니다.");
            }
            for (Entry entry : ordered) inspect(input, entry, entries);
            Map<Integer, Integer> mapping = new HashMap<>();
            Entry page = null;
            int catalogs = 0, trees = 0;
            for (Entry entry : ordered) {
                if ("/Catalog".equals(entry.type)) {
                    if (entry.number != rootNumber) throw new IOException("PDF 루트가 일치하지 않습니다.");
                    catalogs++; mapping.put(entry.number, 1);
                } else if ("/Pages".equals(entry.type)) {
                    trees++; mapping.put(entry.number, 2);
                } else {
                    mapping.put(entry.number, offsets.size()); offsets.add(0L);
                    if ("/Page".equals(entry.type)) {
                        if (page != null) throw new IOException("한 번에 한 PDF 페이지만 합칠 수 있습니다.");
                        page = entry;
                    }
                }
            }
            if (catalogs != 1 || trees != 1 || page == null)
                throw new IOException("PDF 페이지 트리가 올바르지 않습니다.");
            pages.add(mapping.get(page.number));
            for (Entry entry : ordered) {
                checkCancelled();
                int number = mapping.get(entry.number);
                if (number <= 2) continue;
                start(number);
                Tokens tokens = body(input, entry);
                boolean ended = false;
                while (tokens.peek(0) != null) {
                    String token = tokens.take();
                    if ("endobj".equals(token)) {
                        text("\nendobj\n");
                        if (tokens.peek(0) != null) throw new IOException("PDF 객체 뒤에 추가 데이터가 있습니다.");
                        ended = true;
                        break;
                    }
                    if ("stream".equals(token)) {
                        if (entry.streamLength < 0 || !tokens.pending.isEmpty()) throw new IOException("PDF 스트림 길이가 없습니다.");
                        int newline = input.read();
                        if (newline == '\r') { if (input.read() != '\n') throw new IOException("PDF stream 줄바꿈 오류입니다."); }
                        else if (newline != '\n') throw new IOException("PDF stream 줄바꿈 오류입니다.");
                        if (entry.streamLength > entry.end - input.getFilePointer()) throw new EOFException("잘린 PDF 스트림입니다.");
                        text("\nstream\n");
                        long remaining = entry.streamLength;
                        while (remaining > 0) {
                            int n = (int) Math.min(buffer.length, remaining);
                            input.readFully(buffer, 0, n); bytes(buffer, n); remaining -= n;
                        }
                        if (!"endstream".equals(tokens.take())) throw new IOException("PDF 스트림의 끝이 없습니다.");
                        text("\nendstream\n");
                    } else if (unsigned(token) && "0".equals(tokens.peek(0)) && "R".equals(tokens.peek(1))) {
                        Integer reference = mapping.get(integer(token));
                        if (reference == null) throw new IOException("PDF 객체 참조가 없습니다.");
                        tokens.take(); tokens.take(); text(reference + " 0 R ");
                    } else text(token + " ");
                }
                if (!ended) throw new EOFException("PDF 객체가 끝나지 않았습니다.");
            }
        } catch (NumberFormatException error) { throw new IOException("잘못된 PDF 숫자입니다.", error); }
    }

    private static String line(RandomAccessFile input) throws IOException {
        String value = input.readLine();
        if (value == null) throw new EOFException("잘린 PDF xref입니다.");
        if (value.length() > BUFFER_SIZE) throw new IOException("PDF xref 줄 한도를 초과했습니다.");
        return value.trim();
    }
    private static boolean unsigned(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }
    private static int integer(String value) throws IOException {
        if (!unsigned(value)) throw new IOException("잘못된 PDF 정수입니다.");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IOException("PDF 정수 범위를 초과했습니다.", error); }
    }
    private static Tokens body(RandomAccessFile input, Entry entry) throws IOException {
        input.seek(entry.offset);
        Tokens tokens = new Tokens(input, entry.end);
        if (integer(tokens.take()) != entry.number || !"0".equals(tokens.take()) || !"obj".equals(tokens.take()))
            throw new IOException("PDF 객체 헤더가 일치하지 않습니다.");
        return tokens;
    }
    private void inspect(RandomAccessFile input, Entry entry, Map<Integer, Entry> entries) throws IOException {
        Tokens tokens = body(input, entry);
        int depth = 0;
        String token;
        while ((token = tokens.take()) != null) {
            checkCancelled();
            if ("stream".equals(token) || "endobj".equals(token)) return;
            if ("<<".equals(token) || "[".equals(token)) depth++;
            if (">>".equals(token) || "]".equals(token)) depth--;
            if (depth == 1 && "/Type".equals(token)) entry.type = tokens.take();
            if (depth == 1 && "/Length".equals(token)) {
                String value = tokens.take();
                if (!unsigned(value)) throw new IOException("PDF 스트림 길이를 읽지 못했습니다.");
                if ("0".equals(tokens.peek(0)) && "R".equals(tokens.peek(1))) {
                    tokens.take(); tokens.take();
                    Entry target = entries.get(integer(value));
                    if (target == null) throw new IOException("PDF 스트림 길이 객체가 없습니다.");
                    long saved = input.getFilePointer();
                    Tokens scalar = body(input, target);
                    value = scalar.take();
                    if (!unsigned(value) || !"endobj".equals(scalar.take())) throw new IOException("잘못된 PDF 스트림 길이 객체입니다.");
                    input.seek(saved);
                }
                entry.streamLength = Long.parseLong(value);
            }
        }
        throw new EOFException("PDF 객체가 끝나지 않았습니다.");
    }

    /** PDF lexical tokens: literal/hex strings are opaque; stream payloads bypass this lexer. */
    private static final class Tokens {
        final RandomAccessFile input;
        final long end;
        final ArrayDeque<String> pending = new ArrayDeque<>();
        Tokens(RandomAccessFile input, long end) { this.input = input; this.end = end; }
        String peek(int index) throws IOException {
            while (pending.size() <= index) { String token = next(); if (token == null) return null; pending.addLast(token); }
            int i = 0; for (String token : pending) if (i++ == index) return token;
            return null;
        }
        String take() throws IOException { return pending.isEmpty() ? next() : pending.removeFirst(); }
        int read() throws IOException { return input.getFilePointer() >= end ? -1 : input.read(); }
        void back() throws IOException { input.seek(input.getFilePointer() - 1); }
        static boolean space(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }
        static boolean delimiter(int c) { return c < 0 || space(c) || "()<>[]{}/%".indexOf(c) >= 0; }
        String next() throws IOException {
            int c;
            do {
                c = read();
                if (c == '%') { do { c = read(); } while (c >= 0 && c != '\n' && c != '\r'); }
            } while (space(c));
            if (c < 0) return null;
            StringBuilder token = new StringBuilder(); token.append((char) c);
            if (c == '(') {
                int depth = 1;
                while (depth > 0) {
                    c = read(); if (c < 0) throw new EOFException("잘린 PDF 문자열입니다.");
                    token.append((char) c);
                    if (c == '\\') { c = read(); if (c < 0) throw new EOFException(); token.append((char) c); }
                    else if (c == '(') depth++; else if (c == ')') depth--;
                    bounded(token);
                }
            } else if (c == '<' || c == '>') {
                int following = read();
                if (following == c) token.append((char) following);
                else if (c == '<') {
                    while (following != '>') {
                        if (following < 0) throw new EOFException("잘린 PDF hex 문자열입니다.");
                        token.append((char) following); bounded(token); following = read();
                    }
                    token.append('>');
                } else if (following >= 0) back();
            } else if (c == '/' || !delimiter(c)) {
                while ((c = read()) >= 0 && !delimiter(c)) { token.append((char) c); bounded(token); }
                if (c >= 0) back();
            }
            return token.toString();
        }
        static void bounded(StringBuilder value) throws IOException {
            if (value.length() > BUFFER_SIZE) throw new IOException("PDF 토큰 한도를 초과했습니다.");
        }
    }

    private void finish() throws IOException {
        start(1); text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        start(2); text("<< /Type /Pages /Count " + pages.size() + " /Kids [");
        for (int page : pages) text(page + " 0 R ");
        text("] >>\nendobj\n");
        long xref = position;
        text("xref\n0 " + offsets.size() + "\n0000000000 65535 f \n");
        for (int i = 1; i < offsets.size(); i++) {
            if (offsets.get(i) <= 0 || offsets.get(i) > 9_999_999_999L) throw new IOException("PDF xref 범위를 초과했습니다.");
            text(String.format(Locale.US, "%010d 00000 n \n", offsets.get(i)));
        }
        text("trailer\n<< /Size " + offsets.size() + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
    }
}
