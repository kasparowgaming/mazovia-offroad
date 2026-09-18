package pl.mazovia.offroad.routing.engine.weights

import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.TrackType
import com.graphhopper.routing.ev.Smoothness
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PMap
import pl.mazovia.offroad.domain.model.RoutingProfile

/**
 * Zastępuje GraphHopperowy CustomModelParser (Janino → bytecode JVM → ART:
 * "can't load this type of class file") ORAZ com.graphhopper.routing.weighting.custom.
 * CustomWeighting (NoClassDefFoundError: javax.lang.model.SourceVersion, przez
 * UNFAVORED_EDGE — patrz komentarz w AndroidCustomWeighting.kt) własną, w pełni
 * skompilowaną AOT implementacją.
 *
 * TO JEST JEDYNE MIEJSCE, KTÓRE REALNIE DECYDUJE O TRASACH.
 *
 * buildCustomModel() w GraphHopperRoutingService.kt służy wyłącznie do zgodności hasha
 * profilu z grafem i zawiera zamrożone literały. Te dwa zbiory liczb CELOWO się różnią —
 * wcześniejsze zalecenie "muszą pozostać zgodne" opisywało intencję, nie mechanizm
 * (patrz ZMIANY.md, sekcja 1). Wagi poniżej można stroić bez przebudowy grafu.
 */
class AndroidWeightingFactory(
    private val lookup: EncodedValueLookup
) : WeightingFactory {

    override fun createWeighting(profile: Profile, pMap: PMap, disableTurnCosts: Boolean): Weighting {
        val mode = RoutingProfile.entries.firstOrNull { profileNameFor(it) == profile.name }
            ?: throw IllegalArgumentException("Nieznany profil: ${profile.name}")

        val speedEnc = lookup.getDecimalEncodedValue("enduro_average_speed")
        val accessEnc = lookup.getBooleanEncodedValue("enduro_access")
        val roadClassEnc = lookup.getEnumEncodedValue("road_class", RoadClass::class.java)
        val surfaceEnc = lookup.getEnumEncodedValue("surface", Surface::class.java)
        val trackTypeEnc = lookup.getEnumEncodedValue("track_type", TrackType::class.java)
        val smoothnessEnc = lookup.getEnumEncodedValue("smoothness", Smoothness::class.java)
        val bdotSourceEnc = runCatching { lookup.getBooleanEncodedValue("bdot_source") }.getOrNull()
        val syntheticConnectorEnc = runCatching { lookup.getBooleanEncodedValue("synthetic_connector") }.getOrNull()

        val priorityMapping = AndroidPriorityMapping(mode, accessEnc, roadClassEnc, surfaceEnc, trackTypeEnc, smoothnessEnc, bdotSourceEnc, syntheticConnectorEnc)
        val speedMapping = AndroidSpeedMapping(speedEnc)

        return AndroidCustomWeighting(
            name = profile.name,
            priorityMapping = priorityMapping,
            speedMapping = speedMapping,
            priorityMax = priorityMapping.safeMax(),
            speedMax = speedMapping.safeMax(),
            distanceInfluence = OffRoadWeights.distanceInfluence(mode)
        )
    }

    companion object {
        fun profileNameFor(mode: RoutingProfile): String = when (mode) {
            RoutingProfile.BEZPIECZNY -> "enduro_normal"
            RoutingProfile.TERENOWY -> "enduro_max"
            RoutingProfile.ODKRYWCZY -> "enduro_extreme"
        }
    }
}

/**
 * Klasyfikacja nawierzchni po NAZWIE stałej enuma, a nie po samej stałej.
 *
 * Powód: zestaw wartości `com.graphhopper.routing.ev.Surface` różni się między wersjami
 * GraphHoppera, a ten projekt nie był jeszcze skompilowany z prawdziwym SDK. Odwołanie
 * do nieistniejącej stałej to błąd kompilacji; porównanie nazw to najwyżej brak
 * dopasowania, czyli neutralny mnożnik 1.0. Nazwy spoza faktycznego enuma są tu
 * nieszkodliwym zapasem na inne wersje danych.
 */
internal val PAVED_SURFACE_NAMES = setOf(
    "PAVED", "ASPHALT", "CONCRETE", "CONCRETE_LANES", "CONCRETE_PLATES",
    "PAVING_STONES", "COBBLESTONE", "SETT", "METAL", "WOOD"
)

/** Nawierzchnie miękkie/gruntowe — właściwy teren enduro. */
internal val SOFT_UNPAVED_SURFACE_NAMES = setOf(
    "UNPAVED", "GROUND", "DIRT", "EARTH", "MUD", "SAND", "GRASS",
    "GRASS_PAVER", "WOODCHIPS", "CLAY", "SNOW", "ICE"
)

/** Nawierzchnie nieutwardzone, ale twarde — szuter, tłuczeń, gruntowa utwardzona. */
internal val FIRM_UNPAVED_SURFACE_NAMES = setOf(
    "COMPACTED", "FINE_GRAVEL", "GRAVEL", "PEBBLESTONE", "SALT"
)

/**
 * Odpowiednik edgeToPriorityMapping: start = GLOBAL_PRIORITY (1.0), każdy niezależny
 * blok mnoży kumulatywnie, brak dopasowania = ×1.
 */
private class AndroidPriorityMapping(
    private val mode: RoutingProfile,
    private val accessEnc: com.graphhopper.routing.ev.BooleanEncodedValue,
    private val roadClassEnc: com.graphhopper.routing.ev.EnumEncodedValue<RoadClass>,
    private val surfaceEnc: com.graphhopper.routing.ev.EnumEncodedValue<Surface>,
    private val trackTypeEnc: com.graphhopper.routing.ev.EnumEncodedValue<TrackType>,
    private val smoothnessEnc: com.graphhopper.routing.ev.EnumEncodedValue<Smoothness>,
    private val bdotSourceEnc: com.graphhopper.routing.ev.BooleanEncodedValue?,
    private val syntheticConnectorEnc: com.graphhopper.routing.ev.BooleanEncodedValue?
) : EdgeDoubleMapping {

    override fun get(edge: EdgeIteratorState, reverse: Boolean): Double {
        var value = 1.0 // GLOBAL_PRIORITY, potwierdzone (CustomWeightingHelper.java)

        val accessible = if (reverse) edge.getReverse(accessEnc) else edge.get(accessEnc)
        val roadClass = if (reverse) edge.getReverse(roadClassEnc) else edge.get(roadClassEnc)
        val surface = if (reverse) edge.getReverse(surfaceEnc) else edge.get(surfaceEnc)
        val trackType = if (reverse) edge.getReverse(trackTypeEnc) else edge.get(trackTypeEnc)
        val smoothness = if (reverse) edge.getReverse(smoothnessEnc) else edge.get(smoothnessEnc)
        val bdotOnly = bdotSourceEnc?.let { if (reverse) edge.getReverse(it) else edge.get(it) } == true
        val syntheticConnector = syntheticConnectorEnc?.let { if (reverse) edge.getReverse(it) else edge.get(it) } == true

        if (!accessible) value *= 0.0
        if (roadClass == RoadClass.MOTORWAY || roadClass == RoadClass.TRUNK || roadClass == RoadClass.PRIMARY) {
            value *= OffRoadWeights.majorRoadMultiplier(mode)
        }

        value *= surfaceMultiplier(surface)
        value *= trackTypeMultiplier(trackType)
        value *= smoothnessMultiplier(smoothness)

        if (surface == Surface.MISSING && trackType == TrackType.MISSING) {
            value *= missingDetailsRoadClassMultiplier(roadClass)
        }
        if (bdotOnly) value *= OffRoadWeights.bdotOnlyMultiplier(mode)
        if (syntheticConnector) value *= OffRoadWeights.syntheticConnectorMultiplier(mode)

        return value
    }

    /**
     * Symetryczna ocena nawierzchni: kara za utwardzoną, PREMIA za potwierdzoną
     * nieutwardzoną. Wcześniej istniała wyłącznie kara, przez co potwierdzony piach
     * (1.0) wypadał gorzej niż droga o nieznanej nawierzchni z road_class=TRACK (1.25) —
     * profil premiował niewiedzę zamiast terenu.
     */
    private fun surfaceMultiplier(surface: Surface): Double {
        val name = surface.name
        return when {
            name in PAVED_SURFACE_NAMES -> OffRoadWeights.pavedSurfaceMultiplier(mode)
            name in SOFT_UNPAVED_SURFACE_NAMES -> OffRoadWeights.softUnpavedSurfaceMultiplier(mode)
            name in FIRM_UNPAVED_SURFACE_NAMES -> OffRoadWeights.firmUnpavedSurfaceMultiplier(mode)
            else -> 1.0
        }
    }

    private fun missingDetailsRoadClassMultiplier(roadClass: RoadClass): Double = when (roadClass) {
        RoadClass.TRACK -> OffRoadWeights.unknownTrackMultiplier(mode)
        RoadClass.PATH -> OffRoadWeights.unknownPathMultiplier(mode)
        RoadClass.SERVICE -> OffRoadWeights.unknownServiceMultiplier(mode)
        RoadClass.UNCLASSIFIED -> OffRoadWeights.unknownUnclassifiedMultiplier(mode)
        RoadClass.SECONDARY, RoadClass.TERTIARY ->
            OffRoadWeights.unknownLikelyPavedArterialMultiplier(mode)
        RoadClass.RESIDENTIAL, RoadClass.LIVING_STREET ->
            OffRoadWeights.unknownLikelyPavedLocalMultiplier(mode)
        else -> 1.0
    }

    private fun trackTypeMultiplier(t: TrackType): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> when (t) {
            TrackType.GRADE1 -> 0.8
            TrackType.GRADE2, TrackType.GRADE3 -> 1.1
            TrackType.GRADE4, TrackType.GRADE5 -> 0.9
            else -> 1.0
        }
        RoutingProfile.TERENOWY -> when (t) {
            TrackType.GRADE1 -> 0.55
            TrackType.GRADE2 -> 1.05
            TrackType.GRADE3, TrackType.GRADE4 -> 1.40
            TrackType.GRADE5 -> 1.30
            else -> 1.0
        }
        RoutingProfile.ODKRYWCZY -> when (t) {
            TrackType.GRADE1, TrackType.GRADE2 -> 0.45
            TrackType.GRADE3 -> 1.30
            TrackType.GRADE4, TrackType.GRADE5 -> 1.75
            else -> 1.0
        }
    }

    private fun smoothnessMultiplier(s: Smoothness): Double {
        if (s == Smoothness.IMPASSABLE) return 0.0
        return when (mode) {
            RoutingProfile.BEZPIECZNY -> when (s) {
                Smoothness.VERY_HORRIBLE, Smoothness.HORRIBLE -> 0.5
                Smoothness.VERY_BAD -> 0.8
                else -> 1.0
            }
            RoutingProfile.TERENOWY -> when (s) {
                Smoothness.HORRIBLE, Smoothness.VERY_HORRIBLE -> 1.40
                Smoothness.VERY_BAD -> 1.18
                else -> 1.0
            }
            RoutingProfile.ODKRYWCZY -> when (s) {
                Smoothness.VERY_HORRIBLE -> 1.75
                Smoothness.HORRIBLE -> 1.55
                Smoothness.VERY_BAD -> 1.30
                else -> 1.0
            }
        }
    }

    /**
     * Górne ograniczenie priorytetu dla heurystyki A*.
     *
     * MUSI być większe lub równe realnemu maksimum — calcMinWeightPerDistance() dzieli
     * przez tę wartość, więc jej ZANIŻENIE czyni heurystykę niedopuszczalną i A* może po
     * cichu zwracać trasy nieoptymalne. Przeszacowanie jest bezpieczne (kosztuje tylko
     * szerszą eksplorację), dlatego świadomie mnożymy tu składniki, które w praktyce
     * nigdy nie występują jednocześnie (premia za nawierzchnię wyklucza się z fallbackiem
     * road_class, bo ten wymaga surface == MISSING).
     */
    fun safeMax(): Double {
        val maxTrackType = when (mode) {
            RoutingProfile.BEZPIECZNY -> 1.1
            RoutingProfile.TERENOWY -> 1.40
            RoutingProfile.ODKRYWCZY -> 1.75
        }
        val maxSmoothness = when (mode) {
            RoutingProfile.BEZPIECZNY -> 1.0
            RoutingProfile.TERENOWY -> 1.40
            RoutingProfile.ODKRYWCZY -> 1.75
        }
        val maxSurface = maxOf(
            OffRoadWeights.pavedSurfaceMultiplier(mode),
            OffRoadWeights.softUnpavedSurfaceMultiplier(mode),
            OffRoadWeights.firmUnpavedSurfaceMultiplier(mode)
        )
        val maxRoadClassFallback = maxOf(
            OffRoadWeights.unknownTrackMultiplier(mode),
            OffRoadWeights.unknownPathMultiplier(mode),
            OffRoadWeights.unknownServiceMultiplier(mode),
            OffRoadWeights.unknownUnclassifiedMultiplier(mode),
            OffRoadWeights.unknownLikelyPavedArterialMultiplier(mode),
            OffRoadWeights.unknownLikelyPavedLocalMultiplier(mode)
        )
        return maxOf(1.0, maxSurface) *
            maxOf(1.0, maxTrackType) *
            maxOf(1.0, maxSmoothness) *
            maxOf(1.0, maxRoadClassFallback) *
            maxOf(1.0, OffRoadWeights.bdotOnlyMultiplier(mode)) *
            maxOf(1.0, OffRoadWeights.syntheticConnectorMultiplier(mode))
    }
}

/** Odpowiednik edgeToSpeedMapping — neutralne tempo Enduro, bez OSM maxspeed. */
private class AndroidSpeedMapping(
    private val speedEnc: com.graphhopper.routing.ev.DecimalEncodedValue
) : EdgeDoubleMapping {

    /**
     * Poprzednio: get() przepuszczał prędkość aż do 999 km/h, a safeMax() deklarowało
     * 40 km/h. Każda krawędź szybsza niż 40 km/h łamała więc założenie heurystyki A*
     * (calcMinWeightPerDistance zakłada, że nic nie jest szybsze niż speedMax) i mogła
     * skutkować cichą utratą optymalności — objawiającą się jako "dziwne, zbyt
     * zachowawcze trasy". Teraz obie wartości pochodzą z jednej stałej.
     */
    override fun get(edge: EdgeIteratorState, reverse: Boolean): Double {
        val enduroSpeed = if (reverse) edge.getReverse(speedEnc) else edge.get(speedEnc)
        if (enduroSpeed <= 0.0) return 0.0
        return minOf(MAX_ENDURO_SPEED_KMH, enduroSpeed)
    }

    fun safeMax(): Double = MAX_ENDURO_SPEED_KMH

    companion object {
        /** Górny limit tempa enduro w km/h — jednocześnie clamp i speedMax dla A*. */
        const val MAX_ENDURO_SPEED_KMH = 90.0
    }
}
