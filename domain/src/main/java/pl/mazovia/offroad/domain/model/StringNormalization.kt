package pl.mazovia.offroad.domain.model

import java.text.Normalizer
import java.util.Locale

object StringNormalization {
    fun normalizeForSearch(input: String): String {
        // 1. Explicitly replace Polish 'ł' and 'Ł' with 'l'
        val replacedL = input.replace("ł", "l", ignoreCase = true)
            .replace("Ł", "l", ignoreCase = true)
        
        // 2. Lowercase using ROOT locale
        val lowercased = replacedL.lowercase(Locale.ROOT)
        
        // 3. Remove diacritics using Unicode decomposition
        val normalized = Normalizer.normalize(lowercased, Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
    }
}
