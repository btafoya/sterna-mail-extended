package app.sterna.core.data.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The import gate, enumerated from the serial descriptor rather than from a list written here: a
 * list of field names in a test is the very defect this file exists to catch, since it goes stale
 * on the same commit as the one it is meant to watch.
 *
 * `SettingsBackupCodec.decode` has `ignoreUnknownKeys`, so any syntactically valid JSON object
 * decodes; `isPlausible()` is the only thing standing between "this is a Sterna backup" and "this
 * is some other file". Both directions are load-bearing, hence the two negatives below: too strict
 * refuses a real backup at the picker, too loose announces a successful import that restored
 * nothing.
 */
@OptIn(ExperimentalSerializationApi::class) // SerialDescriptor's element accessors; test-only.
class SettingsBackupPlausibilityTest {

    private val descriptor = SettingsBackup.serializer().descriptor

    /** Every serialized name of [SettingsBackup] but `version`, paired with its kind. */
    private val settableFields: List<Pair<String, SerialKind>> =
        (0 until descriptor.elementsCount)
            .map { descriptor.getElementName(it) to descriptor.getElementDescriptor(it).kind }
            .filter { (name, _) -> name != "version" }

    @Test fun `this file knows how to write a value for every kind a backup field has`() {
        assertEquals(
            "a settings backup gained a field of a kind this file cannot build a minimal JSON " +
                "value for, so the test below would have skipped it and the new setting would " +
                "enter unguarded. Add the kind to smallestJsonValue, then check the new field is " +
                "enough on its own to make a backup plausible. A field that is not a setting but " +
                "a note about the file belongs beside `version` in settableFields instead: a file " +
                "carrying only that would restore nothing, and the gate must keep refusing it.",
            emptyList<String>(),
            settableFields.filter { (_, kind) -> smallestJsonValue(kind) == null }.map { it.first },
        )
    }

    @Test fun `a backup that carries one single setting is recognised, whichever setting it is`() {
        val refused = settableFields.mapNotNull { (name, kind) ->
            val value = smallestJsonValue(kind) ?: return@mapNotNull null
            val file = """{"$name": $value}"""
            if (SettingsBackupCodec.decode(file)?.isPlausible() == true) null else file
        }
        assertEquals(
            "isPlausible() does not recognise these backups, so a reader whose export happens to " +
                "carry only these settings is told at the picker that her file is not a valid " +
                "Sterna backup and nothing is imported. Do not add the missing names to a list: " +
                "that list is what went stale. Derive the answer from the whole record, so that a " +
                "field added later is covered without anyone remembering to.",
            emptyList<String>(),
            refused,
        )
    }

    @Test fun `a file that carries no setting at all is not a backup`() {
        // ignoreUnknownKeys means any JSON object decodes, including one from another app.
        assertFalse(SettingsBackupCodec.decode("{}")!!.isPlausible())
    }

    @Test fun `a file that carries a version and nothing else is not a backup`() {
        // The border between the two failures. A predicate written as `this != SettingsBackup()`
        // answers true here only because the version differs, and importing it would report a
        // success having restored not one setting.
        assertFalse(SettingsBackupCodec.decode("""{"version":7}""")!!.isPlausible())
    }

    /** The shortest JSON value that sets a field of [kind]; null when this file has no idea. */
    private fun smallestJsonValue(kind: SerialKind): String? = when (kind) {
        PrimitiveKind.BOOLEAN -> "true"
        PrimitiveKind.INT -> "1"
        PrimitiveKind.STRING -> "\"x\""
        StructureKind.LIST -> "[]"
        else -> null
    }
}
