package pl.mazovia.offroad.routing.engine.weights

import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState

/**
 * WŁASNA implementacja Weighting — NIE reużywa
 * com.graphhopper.routing.weighting.custom.CustomWeighting.
 *
 * Powód (potwierdzone realnym stack trace'em z crasha na telefonie):
 * CustomWeighting.calcEdgeWeight() odwołuje się do statycznego pola
 * EdgeIteratorState.UNFAVORED_EDGE. Samo PIERWSZE odwołanie do tego pola wyzwala
 * statyczny inicjalizator EdgeIteratorState, który konstruuje
 * SimpleBooleanEncodedValue -> IntEncodedValueImpl, którego konstruktor waliduje nazwę
 * przez javax.lang.model.SourceVersion — klasę z Java Compiler API, KTÓREJ NIE MA NA
 * ANDROIDZIE:
 *   NoClassDefFoundError: Ljavax/lang/model/SourceVersion;
 *     at IntEncodedValueImpl.isValidEncodedValue
 *     at IntEncodedValueImpl.<init>
 *     at SimpleBooleanEncodedValue.<init>
 *     at EdgeIteratorState.<clinit>
 *     at CustomWeighting.calcEdgeWeight(CustomWeighting.java:121)
 *
 * To jest DRUGI, całkowicie niezależny od Janino, powód niekompatybilności GraphHoppera
 * 9.1 z ART. Rozwiązanie: nie reużywać CustomWeighting w ogóle — poniższa klasa
 * odtwarza tę samą, potwierdzoną wcześniej dekompilacją formułę
 * (calcEdgeWeight/calcSeconds/calcMinWeightPerDistance), ale BEZ odwołania do
 * UNFAVORED_EDGE. Nasza aplikacja nigdy nie ustawia tej flagi na krawędziach, więc jej
 * pominięcie w żaden sposób nie zmienia wyniku routingu.
 *
 * Turn costs: świadomie zawsze wyłączone (hasTurnCosts()=false, calcTurnWeight/Millis
 * zwracają 0) — nasze profile nigdy nie ustawiają TurnCostsConfig, a nie mamy
 * potwierdzonego javap-em ciała Profile.hasTurnCosts()/API TurnCostProvider, więc nie
 * zgadujemy tej ścieżki (patrz ROUTING.md).
 */
class AndroidCustomWeighting(
    private val name: String,
    private val priorityMapping: EdgeDoubleMapping,
    private val speedMapping: EdgeDoubleMapping,
    private val priorityMax: Double,
    private val speedMax: Double,
    private val distanceInfluence: Double
) : Weighting {

    override fun calcMinWeightPerDistance(): Double {
        return 1.0 / (speedMax / 3.6) / priorityMax + distanceInfluence
    }

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val priority = priorityMapping.get(edgeState, reverse)
        if (priority == 0.0) return Double.POSITIVE_INFINITY

        val distance = edgeState.distance
        val seconds = calcSeconds(distance, edgeState, reverse)
        if (seconds.isInfinite()) return Double.POSITIVE_INFINITY

        // Świadomie pominięte: sprawdzenie UNFAVORED_EDGE (patrz komentarz klasy powyżej)
        // — nasza aplikacja nigdy nie ustawia tej flagi, więc headingPenalty nigdy by
        // się tu i tak nie dodał.

        val distanceCost = distance * distanceInfluence
        if (distanceCost.isInfinite()) return Double.POSITIVE_INFINITY

        return seconds / priority + distanceCost
    }

    private fun calcSeconds(distance: Double, edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val speed = speedMapping.get(edgeState, reverse)
        if (speed == 0.0) return Double.POSITIVE_INFINITY
        if (speed < 0) throw IllegalArgumentException("Speed cannot be negative: $speed")
        return distance / speed * 3.6
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long {
        val seconds = calcSeconds(edgeState.distance, edgeState, reverse)
        if (seconds.isInfinite()) return Long.MAX_VALUE
        return (seconds * 1000).toLong()
    }

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double = 0.0
    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long = 0L
    override fun hasTurnCosts(): Boolean = false
    override fun getName(): String = name
}

/** Minimalny, własny odpowiednik CustomWeighting.EdgeToDoubleMapping — bez zależności od GraphHoppera. */
interface EdgeDoubleMapping {
    fun get(edge: EdgeIteratorState, reverse: Boolean): Double
}
