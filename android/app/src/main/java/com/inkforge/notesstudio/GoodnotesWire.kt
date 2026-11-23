package com.inkforge.notesstudio

internal typealias GoodnotesMessage = List<GoodnotesWire.Field>

/**
 * Bounded readers for the schema-less protobuf and Apple LZ4 layers observed in
 * Goodnotes archives. The wire layout follows the MIT-licensed
 * Kaih1825/parser-for-goodnotes at baec907c, without depending on its Python code.
 */
internal object GoodnotesWire {
    private const val MAX_FIELDS = 65_536
    private const val MAX_RECORDS = 131_072

    data class Field(
        val number: Int,
        val wire: Int,
        val value: Long = 0,
        val data: ByteArray? = null
    )

    fun read(data: ByteArray): GoodnotesMessage {
        val cursor = Cursor(data)
        val fields = ArrayList<Field>()
        while (!cursor.finished) {
            require(fields.size < MAX_FIELDS) { "Goodnotes protobuf 항목이 너무 많습니다." }
            val key = cursor.varint()
            val number = key ushr 3
            require(number in 1..0x1fff_ffffL) { "Invalid protobuf field number" }
            when (val wire = (key and 7).toInt()) {
                0 -> fields += Field(number.toInt(), wire, value = cursor.varint())
                1 -> fields += Field(number.toInt(), wire, value = cursor.fixed(8))
                2 -> {
                    val length = cursor.varint()
                    require(length in 0..cursor.remaining.toLong()) {
                        "Truncated protobuf length-delimited field"
                    }
                    fields += Field(number.toInt(), wire, data = cursor.bytes(length.toInt()))
                }
                5 -> fields += Field(number.toInt(), wire, value = cursor.fixed(4))
                else -> error("Unsupported protobuf wire type $wire")
            }
        }
        return fields
    }

    /** Goodnotes index and page members are streams of varint-length messages. */
    fun records(data: ByteArray): List<GoodnotesMessage> {
        val cursor = Cursor(data)
        val records = ArrayList<GoodnotesMessage>()
        while (!cursor.finished) {
            require(records.size < MAX_RECORDS) { "Goodnotes 레코드가 너무 많습니다." }
            val length = cursor.varint()
            require(length in 0..cursor.remaining.toLong()) { "Truncated Goodnotes record" }
            records += read(cursor.bytes(length.toInt()))
        }
        return records
    }

    /**
     * Decodes Apple's bv41 compressed and bv4- stored blocks through bv4$.
     * Bytes following the terminator belong to the enclosing Goodnotes record.
     */
    data class Frame(val bytes: ByteArray, val endOffset: Int)

    fun decompress(data: ByteArray, limit: Int = 16 * 1024 * 1024): ByteArray = decompressFrame(data, limit).bytes

    fun decompressFrame(data: ByteArray, limit: Int = 16 * 1024 * 1024): Frame {
        require(limit >= 0) { "Negative Goodnotes decompression limit" }
        val cursor = Cursor(data)
        val output = ByteAccumulator(limit)
        while (true) {
            require(cursor.remaining >= 4) { "Missing Apple LZ4 end marker" }
            val magic = cursor.bytes(4)
            if (magic.contentEquals(END)) return Frame(output.toByteArray(), cursor.position)
            val stored = magic.contentEquals(STORED)
            require(stored || magic.contentEquals(COMPRESSED)) {
                "Unexpected Apple LZ4 block marker"
            }
            val unpacked = cursor.unsignedInt()
            val packed = if (stored) unpacked else cursor.unsignedInt()
            require(unpacked <= limit.toLong() - output.size) {
                "Goodnotes decompressed data exceeds limit"
            }
            require(packed <= cursor.remaining.toLong()) { "Truncated Apple LZ4 block" }
            val block = cursor.bytes(packed.toInt())
            if (stored) {
                output.append(block)
            } else {
                decodeLz4Block(block, unpacked.toInt(), output)
            }
        }
    }

    private fun decodeLz4Block(source: ByteArray, expected: Int, output: ByteAccumulator) {
        val start = output.size
        var position = 0
        while (position < source.size) {
            val token = source[position++].toInt() and 255
            var literalLength = token ushr 4
            if (literalLength == 15) {
                val extended = extendedLength(source, position, literalLength)
                literalLength = extended.first
                position = extended.second
            }
            require(literalLength <= source.size - position) { "Truncated LZ4 literals" }
            require(literalLength <= expected - (output.size - start)) { "LZ4 block exceeds declared size" }
            output.append(source, position, literalLength)
            position += literalLength
            if (position == source.size) break

            require(source.size - position >= 2) { "Truncated LZ4 match offset" }
            val offset = (source[position].toInt() and 255) or
                ((source[position + 1].toInt() and 255) shl 8)
            position += 2
            require(offset in 1..output.size) { "Invalid LZ4 match offset" }

            var matchLength = token and 15
            if (matchLength == 15) {
                val extended = extendedLength(source, position, matchLength)
                matchLength = extended.first
                position = extended.second
            }
            require(matchLength <= Int.MAX_VALUE - 4) { "LZ4 match length overflow" }
            matchLength += 4
            require(matchLength <= expected - (output.size - start)) { "LZ4 block exceeds declared size" }
            repeat(matchLength) { output.append(output[output.size - offset]) }
        }
        require(output.size - start == expected) { "LZ4 block size mismatch" }
    }

    private fun extendedLength(source: ByteArray, initialPosition: Int, base: Int): Pair<Int, Int> {
        var length = base
        var position = initialPosition
        while (true) {
            require(position < source.size) { "Truncated LZ4 extended length" }
            val value = source[position++].toInt() and 255
            require(length <= Int.MAX_VALUE - value) { "LZ4 length overflow" }
            length += value
            if (value != 255) return length to position
        }
    }

    private class Cursor(private val source: ByteArray) {
        var position = 0
            private set
        val finished get() = position == source.size
        val remaining get() = source.size - position

        fun varint(): Long {
            var value = 0L
            repeat(10) { index ->
                require(position < source.size) { "Truncated protobuf varint" }
                val byte = source[position++].toInt() and 255
                if (index == 9) require(byte <= 1) { "Protobuf varint exceeds 64 bits" }
                value = value or ((byte and 127).toLong() shl (index * 7))
                if (byte and 128 == 0) return value
            }
            error("Protobuf varint exceeds 10 bytes")
        }

        fun fixed(size: Int): Long {
            require(remaining >= size) { "Truncated protobuf fixed-width field" }
            var value = 0L
            repeat(size) { index ->
                value = value or ((source[position++].toInt() and 255).toLong() shl (index * 8))
            }
            return value
        }

        fun unsignedInt(): Long = fixed(4) and 0xffff_ffffL

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && remaining >= count) { "Truncated binary data" }
            return source.copyOfRange(position, position + count).also { position += count }
        }
    }

    private class ByteAccumulator(private val limit: Int) {
        private var bytes = ByteArray(minOf(limit, 4096))
        var size = 0
            private set

        operator fun get(index: Int): Byte {
            require(index in 0 until size) { "Invalid LZ4 history reference" }
            return bytes[index]
        }

        fun append(value: Byte) {
            ensure(1)
            bytes[size++] = value
        }

        fun append(source: ByteArray, offset: Int = 0, count: Int = source.size) {
            require(offset >= 0 && count >= 0 && offset <= source.size - count) {
                "Invalid binary slice"
            }
            ensure(count)
            source.copyInto(bytes, size, offset, offset + count)
            size += count
        }

        private fun ensure(additional: Int) {
            require(additional <= limit - size) { "Goodnotes decompressed data exceeds limit" }
            val required = size + additional
            if (required <= bytes.size) return
            var capacity = maxOf(1, bytes.size)
            while (capacity < required) {
                val doubled = if (capacity > limit / 2) limit else capacity * 2
                capacity = maxOf(required, doubled)
            }
            bytes = bytes.copyOf(capacity)
        }

        fun toByteArray() = bytes.copyOf(size)
    }

    private val COMPRESSED = byteArrayOf('b'.code.toByte(), 'v'.code.toByte(), '4'.code.toByte(), '1'.code.toByte())
    private val STORED = byteArrayOf('b'.code.toByte(), 'v'.code.toByte(), '4'.code.toByte(), '-'.code.toByte())
    private val END = byteArrayOf('b'.code.toByte(), 'v'.code.toByte(), '4'.code.toByte(), '$'.code.toByte())
}
