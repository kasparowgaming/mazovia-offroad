package javax.lang.model;

import java.util.HashSet;
import java.util.Set;

/**
 * MINIMALNY SHIM — nie prawdziwa klasa JDK.
 *
 * Android nie dostarcza modułu java.compiler, więc javax.lang.model.SourceVersion
 * (część Java Compiler API) nie istnieje w runtime ART. GraphHopper 9.1
 * (com.graphhopper.routing.ev.IntEncodedValueImpl.isValidEncodedValue(), potwierdzone
 * realnym źródłem) wywołuje DOKŁADNIE JEDNĄ metodę tej klasy:
 *
 *     SourceVersion.isKeyword(name)
 *
 * — wyłącznie do sprawdzenia, czy nazwa encoded value (np. "road_class",
 * "car_average_speed") nie koliduje ze słowem kluczowym Javy (bo Janino generuje z niej
 * pole Javy). Ta klasa dostarcza WYŁĄCZNIE tę jedną metodę, z prawdziwą listą słów
 * kluczowych Javy — nie jest pełnym zamiennikiem prawdziwego javax.lang.model.SourceVersion
 * (m.in. nie jest enumem, nie ma innych metod tej klasy) i nie powinna być używana do
 * niczego poza tym jednym, potwierdzonym wywołaniem.
 *
 * DEX (w przeciwieństwie do standardowego JVM) nie rezerwuje przestrzeni nazw javax.*,
 * więc ta klasa zostanie znaleziona przez classloader aplikacji zamiast kończyć się
 * NoClassDefFoundError/ClassNotFoundException.
 */
public final class SourceVersion {

    private SourceVersion() {
    }

    private static final Set<String> KEYWORDS = new HashSet<>();

    static {
        // Słowa kluczowe Javy (JLS 3.9) + literały zarezerwowane (true/false/null) +
        // słowa kluczowe kontekstowe traktowane przez realne isKeyword() jako keywords
        // od Javy 9+ (module-related) i Javy 10+ (var) — kompletna, znana lista.
        String[] words = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            "true", "false", "null",
            "var", "yield", "record", "sealed", "permits", "non-sealed",
            "open", "module", "requires", "exports", "opens", "uses", "provides",
            "to", "with", "transitive"
        };
        for (String w : words) {
            KEYWORDS.add(w);
        }
    }

    public static boolean isKeyword(CharSequence s) {
        if (s == null) return false;
        return KEYWORDS.contains(s.toString());
    }
}
