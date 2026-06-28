package com.starrydream.nanoclick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTimePresetParserTest {
    @Test
    fun parse_filtersDisabledAndSortsBySortOrder() {
        val json = """
            {
              "version": 1,
              "presets": [
                {"id":"b","name":"B","syncUrl":"https://b.example","enabled":true,"sortOrder":20},
                {"id":"disabled","name":"Disabled","syncUrl":"https://disabled.example","enabled":false,"sortOrder":1},
                {"id":"a","name":"A","syncUrl":"https://a.example","enabled":true,"sortOrder":10}
              ]
            }
        """.trimIndent()

        val presets = ServerTimePresetParser.parse(json)

        assertEquals(listOf("a", "b"), presets.map { it.id })
        assertEquals(listOf("https://a.example", "https://b.example"), presets.map { it.syncUrl })
    }

    @Test
    fun parse_readsPresetObjectAndSortsExpectedLocalOrder() {
        val json = """
            {
              "version": 1,
              "presets": [
                {"id":"fans","name":"FANS","syncUrl":"https://fans.example","enabled":true,"sortOrder":4},
                {"id":"google_forms","name":"Google Forms","syncUrl":"https://docs.google.com/","enabled":true,"sortOrder":1},
                {"id":"weverse","name":"위버스","syncUrl":"https://weverse.io/","enabled":true,"sortOrder":3},
                {"id":"naver_form","name":"네이버 폼","syncUrl":"https://form.naver.com/","enabled":true,"sortOrder":2}
              ]
            }
        """.trimIndent()

        val presets = ServerTimePresetParser.parse(json)

        assertEquals(
            listOf("Google Forms", "네이버 폼", "위버스", "FANS"),
            presets.map { it.name }
        )
        assertEquals(4, presets.size)
    }

    @Test
    fun parse_returnsEmptyListForMissingPresets() {
        val presets = ServerTimePresetParser.parse("""{"version":1}""")

        assertTrue(presets.isEmpty())
    }

    @Test
    fun parse_skipsInvalidItemAndKeepsValidItems() {
        val failedIndexes = mutableListOf<Int>()
        val json = """
            {
              "version": 1,
              "presets": [
                {"id":"good","name":"Good","syncUrl":"https://good.example","enabled":true,"sortOrder":1},
                {"id":"bad","name":"Bad","enabled":true,"sortOrder":2},
                {"id":"also_good","name":"Also Good","syncUrl":"https://also-good.example","enabled":true,"sortOrder":3}
              ]
            }
        """.trimIndent()

        val presets = ServerTimePresetParser.parse(json) { index, _ ->
            failedIndexes += index
        }

        assertEquals(listOf("good", "also_good"), presets.map { it.id })
        assertEquals(listOf(1), failedIndexes)
    }

    @Test
    fun normalizeUrl_addsHttpsWhenProtocolMissing() {
        assertEquals("https://example.com", normalizeUrl(" example.com "))
    }

    @Test
    fun normalizeUrl_keepsExistingProtocol() {
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
    }
}
