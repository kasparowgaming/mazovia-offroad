package pl.mazovia.offroad.domain.search

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mazovia.offroad.domain.model.StringNormalization

class StringNormalizationTest {

    @Test
    fun testPolishDiacritics() {
        assertEquals("lodz", StringNormalization.normalizeForSearch("Łódź"))
        assertEquals("zabokliki", StringNormalization.normalizeForSearch("Żabokliki"))
        assertEquals("siedlce", StringNormalization.normalizeForSearch("Siedlce"))
        assertEquals("miedzyrzec", StringNormalization.normalizeForSearch("Międzyrzec"))
        assertEquals("zablotne", StringNormalization.normalizeForSearch("zabłotne"))
    }
}
