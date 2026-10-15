package app.bestnote.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMetadataMergeTest {
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun `no local metadata takes the backup as is`() {
        val backup = """{"version":1,"folders":[{"id":"f","name":"A","colorIndex":1}],"notebooks":{"n":{"favorite":true}}}"""
        assertEquals(backup, LibraryMetadataMerge.merge(null, backup, setOf("n")))
    }

    @Test fun `folders are unioned and restored notebooks take the backup entry`() {
        val local = """{"version":1,"folders":[{"id":"f1","name":"Local","colorIndex":0}],"notebooks":{"a":{"favorite":true},"b":{"coverIndex":3}}}"""
        val backup = """{"version":1,"folders":[{"id":"f1","name":"Old name","colorIndex":9},{"id":"f2","name":"B","colorIndex":2}],"notebooks":{"a":{"favorite":false},"b":{"coverIndex":7},"c":{"folderId":"f2"}}}"""
        val merged = parse(LibraryMetadataMerge.merge(local, backup, restoredIds = setOf("b")))
        val folders = merged["folders"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(mapOf("f1" to "Local", "f2" to "B"), folders)
        val nbs = merged["notebooks"]!!.jsonObject
        assertEquals("true", nbs["a"]!!.jsonObject["favorite"]!!.jsonPrimitive.content) // not restored: keeps the device's
        assertEquals("7", nbs["b"]!!.jsonObject["coverIndex"]!!.jsonPrimitive.content) // restored: backup's
        assertEquals("f2", nbs["c"]!!.jsonObject["folderId"]!!.jsonPrimitive.content) // unknown here: backup's
    }

    @Test fun `a garbled backup file leaves the device metadata alone`() {
        assertEquals("""{"version":1}""", LibraryMetadataMerge.merge("""{"version":1}""", "not json", emptySet()))
    }
}
