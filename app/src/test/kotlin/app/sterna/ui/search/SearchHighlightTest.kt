package app.sterna.ui.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHighlightTest {

    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    /** The highlighted pieces of [text], read back from the original text by their ranges. */
    private fun marked(text: String, vararg terms: String): List<String> =
        highlightRanges(text, terms.toList()).map { text.substring(it.first, it.last + 1) }

    @Test fun `case is folded both ways`() {
        assertEquals(listOf("Facture"), marked("Facture réglée", "facture"))
        assertEquals(listOf("facture"), marked("la facture", "FACTURE"))
    }

    @Test fun `accents are folded in both directions`() {
        assertEquals(listOf("réglée"), marked("Facture réglée", "reglee"))
        assertEquals(listOf("reglee"), marked("facture reglee", "réglée"))
    }

    @Test fun `cyrillic folds its case too`() {
        assertEquals(listOf("Письмо"), marked("Письмо от Саши", "письмо"))
    }

    @Test fun `two terms are both highlighted, in text order`() {
        assertEquals(listOf("Facture", "réglée"), marked("Facture réglée", "reglee", "facture"))
    }

    @Test fun `every occurrence is highlighted`() {
        assertEquals(listOf(0..2, 7..9), highlightRanges("Ana et ana", listOf("ana")))
        assertEquals("overlapping occurrences of one term merge", listOf(5..9), highlightRanges("Ab, banana", listOf("ana")))
    }

    @Test fun `overlapping matches merge into one range`() {
        assertEquals(listOf(0..10), highlightRanges("abonnements", listOf("abonne", "nnements")))
        assertEquals(listOf("invoice"), marked("invoice", "invo", "voice"))
    }

    @Test fun `an absent term highlights nothing`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("Facture réglée", listOf("devis")))
    }

    @Test fun `a blank or empty term highlights nothing`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("Facture réglée", listOf("", " ")))
    }

    @Test fun `ranges index the original text, accents before the match included`() {
        // NFD makes "é" two chars, so a range taken in the folded text would land two chars late.
        val text = "Été déjà réglé : facture"
        assertEquals(listOf(17..23), highlightRanges(text, listOf("facture")))
        assertEquals("facture", text.substring(17, 24))
    }

    @Test fun `a decomposed accent stays with its letter`() {
        val text = "réglée ok"
        assertEquals(listOf("réglée"), marked(text, "reglee"))
    }

    @Test fun `the annotated string carries the style over exactly those ranges`() {
        val shown = highlightTerms("Facture réglée", listOf("facture", "reglee"), bold)
        assertEquals("Facture réglée", shown.text)
        assertEquals(
            listOf(AnnotatedString.Range(bold, 0, 7), AnnotatedString.Range(bold, 8, 14)),
            shown.spanStyles,
        )
    }

    @Test fun `no terms, no style`() {
        assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), highlightTerms("Facture", emptyList(), bold).spanStyles)
    }

    @Test fun `the bar's words are cut like the local index cuts them`() {
        assertEquals(listOf("facture", "réglée", "2026"), searchTerms("  facture, réglée-2026 "))
        assertEquals(emptyList<String>(), searchTerms(" ,; "))
    }

    @Test fun `greek final sigma folds like any sigma, typed in capitals or in lowercase`() {
        assertEquals(listOf("ΟΔΟΣ"), marked("ΟΔΟΣ Αθηνών", "ΟΔΟΣ"))
        assertEquals(listOf("ΟΔΟΣ"), marked("ΟΔΟΣ Αθηνών", "οδος"))
        assertEquals(listOf("οδος"), marked("η οδος", "ΟΔΟΣ"))
    }

    @Test fun `a combining accent at the very end of the match is part of it`() {
        val text = "cafe\u0301"
        assertEquals(listOf(0..4), highlightRanges(text, listOf("cafe")))
        assertEquals(listOf(0..4), highlightRanges("cafe\u0301 noir", listOf("cafe")))
    }

    @Test fun `folding ignores the phone's locale, a Turkish one included`() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale("tr"))
        try {
            assertEquals(listOf("INVOICE"), marked("INVOICE 42", "invoice"))
            assertEquals(listOf("invoice"), marked("invoice 42", "INVOICE"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
