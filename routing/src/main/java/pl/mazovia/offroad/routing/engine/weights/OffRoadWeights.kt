package pl.mazovia.offroad.routing.engine.weights

import pl.mazovia.offroad.domain.model.RoutingProfile

/**
 * Mnożniki priorytetu używane WYŁĄCZNIE w czasie działania aplikacji, przez
 * AndroidWeightingFactory/AndroidPriorityMapping.
 *
 * ZMIANA KONTRAKTU (patrz ZMIANY.md, sekcja 1):
 * Wcześniej część tych wartości była czytana także przez buildCustomModel() w
 * GraphHopperRoutingService, przez co ich zmiana zmieniała treść CustomModelu,
 * a więc hash profilu — i graf przestawał się ładować ("Profiles do not match").
 * Ta pułapka została usunięta: buildCustomModel() używa teraz WYŁĄCZNIE zamrożonych
 * literałów (*ForGraphProfile), które opisują graf, jaki faktycznie zbudowano.
 *
 * W efekcie KAŻDĄ wartość w tym pliku można dowolnie stroić bez przebudowy grafu.
 *
 * Formuła kosztu (AndroidCustomWeighting):
 *     waga = dystans / prędkość * 3.6 / priorytet  +  dystans * distanceInfluence
 * Priorytet > 1 czyni krawędź tańszą niż wynika z czasu przejazdu, priorytet < 1 —
 * droższą. distanceInfluence NIE jest dzielony przez priorytet, więc jest jedynym
 * realnym hamulcem długości objazdu.
 */
internal object OffRoadWeights {
    fun majorRoadMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.70
        RoutingProfile.TERENOWY -> 0.14
        RoutingProfile.ODKRYWCZY -> 0.07
    }

    fun pavedSurfaceMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.90
        RoutingProfile.TERENOWY -> 0.28
        RoutingProfile.ODKRYWCZY -> 0.18
    }

    /**
     * NOWE — premia za POTWIERDZONĄ nawierzchnię miękką/gruntową.
     *
     * Wcześniej istniała wyłącznie kara za asfalt, bez żadnej nagrody za teren.
     * Powodowało to inwersję: droga z `surface=sand` dostawała priorytet 1.0, podczas
     * gdy droga o NIEZNANEJ nawierzchni z `road_class=TRACK` dostawała 1.25 — profil
     * aktywnie preferował niewiedzę nad potwierdzonym terenem.
     */
    fun softUnpavedSurfaceMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 1.0
        RoutingProfile.TERENOWY -> 1.25
        RoutingProfile.ODKRYWCZY -> 1.45
    }

    /**
     * NOWE — premia za nawierzchnie nieutwardzone, ale twarde (szuter, tłuczeń,
     * gruntowa utwardzona). To wciąż teren, ale znacznie łatwiejszy niż piach czy błoto,
     * więc premia jest wyraźnie mniejsza niż softUnpavedSurfaceMultiplier.
     */
    fun firmUnpavedSurfaceMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 1.0
        RoutingProfile.TERENOWY -> 1.10
        RoutingProfile.ODKRYWCZY -> 1.18
    }

    /**
     * Sekundy doliczane za każdy metr wyłącznie z tytułu dystansu — jedyny hamulec
     * przed spektakularnymi objazdami, bo jako jedyny człon nie jest dzielony przez
     * priorytet. Obniżenie tej wartości jest najmocniejszą pojedynczą dźwignią
     * "więcej terenu"; zbyt niska prowadzi do absurdalnie długich tras.
     */
    fun distanceInfluence(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.12
        RoutingProfile.TERENOWY -> 0.042
        RoutingProfile.ODKRYWCZY -> 0.025
    }

    fun unknownTrackMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.90
        RoutingProfile.TERENOWY -> 1.25
        RoutingProfile.ODKRYWCZY -> 1.45
    }

    fun unknownPathMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.80
        RoutingProfile.TERENOWY -> 1.18
        RoutingProfile.ODKRYWCZY -> 1.35
    }

    fun unknownServiceMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 1.0
        RoutingProfile.TERENOWY -> 1.03
        RoutingProfile.ODKRYWCZY -> 1.05
    }

    /**
     * `highway=unclassified` bez tagu `surface` to w polskiej wsi bardzo często droga
     * gruntowa lub szutrowa. Wcześniej dostawała neutralne 1.0 we wszystkich trybach,
     * czyli mniej niż nieznany TRACK — teraz w trybach terenowych jest premiowana.
     */
    fun unknownUnclassifiedMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 1.0
        RoutingProfile.TERENOWY -> 1.10
        RoutingProfile.ODKRYWCZY -> 1.22
    }

    /**
     * OSM często pomija `surface` na zwykłych ulicach publicznych, bo ich utwardzony
     * charakter jest domyślny. Traktowanie takich krawędzi jako neutralnych sprawiało,
     * że korytarz miejski wychodził taniej niż pobliskie tracki. Te mnożniki są celowo
     * nieco łagodniejsze niż potwierdzony asfalt: road_class to przesłanka, nie dowód.
     */
    fun unknownLikelyPavedArterialMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.68
        RoutingProfile.TERENOWY -> 0.26
        RoutingProfile.ODKRYWCZY -> 0.14
    }

    fun unknownLikelyPavedLocalMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.82
        RoutingProfile.TERENOWY -> 0.38
        RoutingProfile.ODKRYWCZY -> 0.22
    }

    /**
     * BDOT10k jest autorytatywnym źródłem geometrii. Nie karzemy realnej drogi tylko
     * za to, że OSM ją pominął — profile terenowe mogą ją aktywnie preferować.
     */
    fun bdotOnlyMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.92
        RoutingProfile.TERENOWY -> 1.14
        RoutingProfile.ODKRYWCZY -> 1.28
    }

    /**
     * Syntetyczne łączniki (sztuczne zszycia sieci BDOT z OSM) mają pozostać drogie,
     * żeby nie stawały się skrótami. Kara 0.18 była jednak tak wysoka, że mogła
     * odcinać całe podsieci leśne dostępne WYŁĄCZNIE przez taki łącznik — droga
     * istniała w grafie, ale router nigdy jej nie wybierał.
     *
     * W trybach terenowych kara jest teraz wyraźnie łagodniejsza. Jeżeli diagnostyka
     * (patrz log "Diagnostyka pokrycia") pokaże, że łączniki nadal nie pojawiają się
     * w trasach, problem leży w danych, nie w wagach.
     */
    fun syntheticConnectorMultiplier(mode: RoutingProfile): Double = when (mode) {
        RoutingProfile.BEZPIECZNY -> 0.18
        RoutingProfile.TERENOWY -> 0.40
        RoutingProfile.ODKRYWCZY -> 0.55
    }
}
