package pl.mazovia.offroad.routing.engine

import com.graphhopper.GraphHopper
import com.graphhopper.routing.WeightingFactory

/**
 * Podklasa GraphHoppera. Jedyny potwierdzony (javap: `protected WeightingFactory
 * GraphHopper.createWeightingFactory()`) sposób podania własnej fabryki Weighting,
 * omijający CustomModelParser/Janino (potwierdzone niedziałające na Android/ART —
 * "can't load this type of class file", potwierdzone przez maintainera GraphHoppera).
 *
 * UWAGA — NIEPOTWIERDZONE JAVAP-em WPROST: dostęp do `encodingManager` poniżej zakłada
 * istnienie publicznego/protected gettera `getEncodingManager()` na `GraphHopper`. Nie
 * mam tego potwierdzonego bajtkodem w tej rozmowie. Jeśli kompilacja padnie właśnie na
 * tej linii, sprawdź dokładną nazwę:
 *   javap -classpath $jar -p com.graphhopper.GraphHopper | Select-String -i "encodingmanager"
 * i podmień nazwę gettera zgodnie z rzeczywistym wynikiem — to jedyny realny punkt
 * ryzyka w tym pliku.
 */
class AndroidGraphHopper : GraphHopper() {
    fun supportsBooleanDetail(name: String): Boolean = runCatching {
        encodingManager.getBooleanEncodedValue(name)
        true
    }.getOrDefault(false)

    override fun createWeightingFactory(): WeightingFactory {
        return pl.mazovia.offroad.routing.engine.weights.AndroidWeightingFactory(encodingManager)
    }
}
