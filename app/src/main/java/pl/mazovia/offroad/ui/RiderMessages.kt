package pl.mazovia.offroad.ui

import pl.mazovia.offroad.domain.routing.RoutingError

object RiderMessages {
    const val GPS = "Brak pozycji GPS. Nie można ustalić miejsca startu. Włącz lokalizację i spróbuj ponownie na otwartej przestrzeni."
    const val PERMISSION = "Brak dostępu do lokalizacji. Nawigacja nie może wystartować. Zezwól aplikacji na lokalizację w ustawieniach telefonu."
    const val NAVIGATION = "Nie udało się rozpocząć jazdy. Nawigacja nie jest aktywna. Spróbuj ponownie."
    const val SAVED_ROUTE = "Nie można otworzyć zapisanej trasy. Dane są niedostępne lub niepełne. Wróć do listy i wybierz inną trasę."
    const val DELETE = "Nie udało się usunąć trasy. Pozostaje na liście. Spróbuj ponownie."
    const val FEEDBACK = "Nie udało się zapisać odpowiedzi. Pytanie pozostaje do sprawdzenia. Spróbuj ponownie."
    const val RIDE = "Nie można odczytać zakończonej jazdy. Podsumowanie jest niedostępne. Spróbuj ponownie."
    const val SAVE_RIDE = "Nie udało się zapisać jazdy. Zapis nie został zakończony. Spróbuj ponownie przed zamknięciem aplikacji."
    const val EXPORT = "Nie udało się zapisać pliku GPX. Eksport nie został ukończony. Spróbuj ponownie i wybierz dostępny folder."
    fun routing(error: RoutingError): String = when (error) {
        RoutingError.GRAPH_NOT_LOADED, RoutingError.GRAPH_CORRUPTED ->
            "Brak dostępnych danych tras. Wyznaczanie trasy jest niedostępne. Wczytaj dane offline."
        RoutingError.DESTINATION_NOT_FOUND, RoutingError.POINT_NOT_FOUND ->
            "Cel jest poza dostępną siecią dróg. Nie można poprowadzić do tego punktu. Wybierz pobliską drogę lub wczytaj dane tego obszaru."
        RoutingError.ORIGIN_NOT_FOUND ->
            "Nie znaleziono drogi przy miejscu startu. Nie można rozpocząć tej trasy. Sprawdź pozycję GPS i dostępność danych obszaru."
        else -> "Nie udało się wyznaczyć trasy. Nie ma gotowej trasy do jazdy. Spróbuj ponownie lub wybierz inny cel."
    }
}
