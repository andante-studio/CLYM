package com.starrydream.nanoclick

internal data class ServerTimePreset(
    val id: String,
    val name: String,
    val syncUrl: String,
    val enabled: Boolean,
    val sortOrder: Int
)

internal object ServerTimePresetParser {
    fun parse(
        json: String,
        onItemError: (index: Int, throwable: Throwable) -> Unit = { _, _ -> }
    ): List<ServerTimePreset> {
        val root = SimpleJsonParser(json).parseObject()
        val presets = root["presets"] as? List<*> ?: return emptyList()

        return presets.mapIndexedNotNull { index, value ->
            runCatching {
                val item = value as? Map<*, *> ?: error("preset item is not an object")
                val enabled = item["enabled"] as? Boolean ?: false
                if (!enabled) return@mapIndexedNotNull null

                val id = item["id"] as? String ?: error("id is missing")
                val name = item["name"] as? String ?: error("name is missing")
                val syncUrl = item["syncUrl"] as? String ?: error("syncUrl is missing")
                if (id.isBlank() || name.isBlank() || syncUrl.isBlank()) {
                    error("id/name/syncUrl must not be blank")
                }

                ServerTimePreset(
                    id = id.trim(),
                    name = name.trim(),
                    syncUrl = syncUrl.trim(),
                    enabled = true,
                    sortOrder = (item["sortOrder"] as? Number)?.toInt() ?: Int.MAX_VALUE
                )
            }.onFailure { throwable ->
                onItemError(index, throwable)
            }.getOrNull()
        }.sortedWith(compareBy<ServerTimePreset> { it.sortOrder }.thenBy { it.name })
    }
}

private class SimpleJsonParser(
    private val source: String
) {
    private var index = 0

    @Suppress("UNCHECKED_CAST")
    fun parseObject(): Map<String, Any?> {
        val value = parseValue()
        skipWhitespace()
        if (index != source.length) error("unexpected trailing content")
        return value as? Map<String, Any?> ?: error("root is not an object")
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObjectValue()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObjectValue(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consumeIf('}')) return map

        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            map[key] = parseValue()
            skipWhitespace()
            if (consumeIf('}')) return map
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = mutableListOf<Any?>()
        skipWhitespace()
        if (consumeIf(']')) return list

        while (true) {
            list += parseValue()
            skipWhitespace()
            if (consumeIf(']')) return list
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(char)
            }
        }
        error("unterminated string")
    }

    private fun parseEscape(): Char {
        val escaped = next()
        return when (escaped) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = source.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }
            else -> error("invalid escape: $escaped")
        }
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek() == '-') index++
        while (peekOrNull()?.isDigit() == true) index++
        val hasFraction = consumeIf('.')
        if (hasFraction) {
            while (peekOrNull()?.isDigit() == true) index++
        }
        val text = source.substring(start, index)
        if (text.isEmpty() || text == "-") error("invalid number")
        return if (hasFraction) text.toDouble() else text.toLong()
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!source.startsWith(literal, index)) error("expected $literal")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peekOrNull()?.isWhitespace() == true) index++
    }

    private fun expect(expected: Char) {
        val actual = next()
        if (actual != expected) error("expected '$expected' but was '$actual'")
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peekOrNull() != expected) return false
        index++
        return true
    }

    private fun peek(): Char = peekOrNull() ?: error("unexpected end of json")

    private fun peekOrNull(): Char? = source.getOrNull(index)

    private fun next(): Char = source.getOrNull(index++) ?: error("unexpected end of json")
}
