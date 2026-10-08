package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.*

/** Reads version 4 JSON one page at a time and decodes data URLs directly to files. */
class LegacyJsonReader(reader: Reader, private val dataSink: (String) -> Pair<String, OutputStream>,
                       private val onPage: (JSONObject) -> Unit) {
    private val input = if (reader is BufferedReader) reader else reader.buffered(64 * 1024)
    private var next = input.read()
    private var depth = 0
    private fun advance(): Int { val old = next; next = input.read(); return old }
    private fun spaces() { while (next >= 0 && next.toChar().isWhitespace()) advance() }
    private fun expect(c: Char) { spaces(); require(next == c.code) { "노트 JSON 형식 오류: $c" }; advance() }

    fun read(): JSONObject {
        expect('{')
        val result = JSONObject()
        spaces()
        var pages = false
        while (next != '}'.code) {
            val key = string(false)
            expect(':')
            if (key == "pages") {
                require(!pages) { "페이지 목록이 중복되었습니다." }; pages = true
                expect('['); spaces()
                var count = 0
                while (next != ']'.code) {
                    require(++count <= 100_000) { "페이지가 너무 많습니다." }
                    onPage(value() as? JSONObject ?: error("페이지 형식 오류"))
                    spaces(); if (next == ']'.code) break
                    expect(',')
                }
                expect(']')
            } else result.put(key, value())
            spaces(); if (next == '}'.code) break
            expect(',')
        }
        expect('}'); spaces()
        require(next == -1 && pages) { "지원하는 노트 파일이 아닙니다." }
        return result
    }
    private fun value(): Any {
        spaces(); require(++depth < 100) { "노트 구조가 너무 깊습니다." }
        try {
            return when (next) {
                '"'.code -> string(true)
                '{'.code -> {
                    expect('{'); val obj = JSONObject(); spaces()
                    while (next != '}'.code) {
                        val key = string(false); expect(':'); obj.put(key, value())
                        spaces(); if (next == '}'.code) break; expect(',')
                    }
                    expect('}'); obj
                }
                '['.code -> {
                    expect('['); val array = JSONArray(); spaces()
                    while (next != ']'.code) {
                        array.put(value()); spaces(); if (next == ']'.code) break; expect(',')
                    }
                    expect(']'); array
                }
                else -> {
                    val token = StringBuilder()
                    while (next >= 0 && next.toChar() !in ",]} \n\r\t") {
                        require(token.length < 128); token.append(advance().toChar())
                    }
                    when (val text = token.toString()) {
                        "true" -> true; "false" -> false; "null" -> JSONObject.NULL
                        else -> text.toLongOrNull() ?: text.toDoubleOrNull()?.also { require(it.isFinite()) }
                            ?: error("유효하지 않은 JSON 값")
                    }
                }
            }
        } finally { depth-- }
    }
    private fun character(): Int {
        require(next >= 0) { "잘린 문자열" }
        if (next == '"'.code) { advance(); return -1 }
        val char = advance()
        if (char != '\\'.code) { require(char >= 32); return char }
        return when (val escape = advance().toChar()) {
            '"', '\\', '/' -> escape.code
            'b' -> 8; 'f' -> 12; 'n' -> 10; 'r' -> 13; 't' -> 9
            'u' -> {
                val text = CharArray(4) { require(next >= 0); advance().toChar() }.concatToString()
                text.toInt(16)
            }
            else -> error("문자열 이스케이프 오류")
        }
    }
    private fun string(assets: Boolean): String {
        expect('"'); val text = StringBuilder()
        while (true) {
            val c = character(); if (c < 0) return text.toString()
            text.append(c.toChar())
            if (assets && text.length == 5 && text.toString() == "data:") {
                while (true) {
                    val h = character(); require(h >= 0 && text.length < 1024) { "데이터 URL 헤더 오류" }
                    if (h == ','.code) break
                    text.append(h.toChar())
                }
                require(text.endsWith(";base64")) { "지원하지 않는 데이터 URL" }
                val mime = text.toString().removePrefix("data:").substringBefore(';')
                val (reference, output) = dataSink(mime)
                output.buffered(64 * 1024).use { out ->
                    var accumulator = 0; var bits = 0; var padding = false; var encoded = 0L; var pads = 0
                    while (true) {
                        val ch = character(); if (ch < 0) break
                        if (ch.toChar().isWhitespace()) continue
                        encoded++
                        if (ch == '='.code) { padding = true; pads++; require(pads <= 2); continue }
                        require(!padding) { "base64 패딩 오류" }
                        val v = when (ch) {
                            in 65..90 -> ch - 65; in 97..122 -> ch - 71; in 48..57 -> ch + 4
                            43 -> 62; 47 -> 63; else -> error("base64 문자 오류")
                        }
                        accumulator = (accumulator shl 6) or v; bits += 6
                        if (bits >= 8) { bits -= 8; out.write((accumulator shr bits) and 255) }
                    }
                    require(encoded % 4 == 0L && bits in listOf(0, 2, 4)) { "잘린 base64 자산" }
                }
                return reference
            }
            require(text.length <= if (assets) 8 * 1024 * 1024 else 4096) { "노트 문자열이 너무 큽니다." }
        }
    }
}
