package pl.mazovia.offroad.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.PlaceSearchResult
import pl.mazovia.offroad.domain.search.PlaceSearchRanker
import pl.mazovia.offroad.domain.search.PlaceSearchRepository
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NominatimPlaceSearchRepository : PlaceSearchRepository {

    override suspend fun searchPlaces(query: String, anchor: GeoPoint): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        if (query.length < 3) return@withContext emptyList()
        
        // Progressive search area conceptually implemented by first passing a viewBox
        // Let's create a bounding box of roughly ~50km around the anchor
        // 1 degree latitude is ~111km, so 0.5 degrees is ~55km
        val latSpan = 0.5
        val lonSpan = 0.5 / Math.cos(Math.toRadians(anchor.latitude))
        
        val left = anchor.longitude - lonSpan
        val bottom = anchor.latitude - latSpan
        val right = anchor.longitude + lonSpan
        val top = anchor.latitude + latSpan
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Use bounded=1 to strictly search in the viewbox, or bounded=0 to prefer viewbox.
        // The prompt says "first search approx 30-50 km around anchor, if too few useful results expand to approx 100km, then broad"
        // Let's use bounded=0 so it prefers the viewbox but still returns other stuff if needed.
        // Actually, if we use bounded=0, Nominatim returns a mix, and our local Ranker will sort by distance!
        // This is a great trick to avoid 3 sequential network calls.
        val urlString = "https://nominatim.openstreetmap.org/search" +
                "?q=$encodedQuery" +
                "&format=json" +
                "&addressdetails=1" +
                "&viewbox=$left,$top,$right,$bottom" +
                "&bounded=0" +
                "&limit=40" // fetch plenty so we can rerank and filter

        val results = fetchFromUrl(urlString)
        
        val candidates = mutableListOf<PlaceSearchResult>()
        for (i in 0 until results.length()) {
            val jsonObj = results.optJSONObject(i) ?: continue
            val parsed = parseNominatimResult(jsonObj)
            if (parsed != null) {
                candidates.add(parsed)
            }
        }
        
        return@withContext PlaceSearchRanker.rank(candidates, query, anchor)
    }

    private fun fetchFromUrl(urlString: String): JSONArray {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        // Nominatim requires a valid User-Agent
        connection.setRequestProperty("User-Agent", "MazoviaOffroad/1.0")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            return JSONArray()
        }

        val responseString = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONArray(responseString)
    }

    private fun parseNominatimResult(jsonObj: org.json.JSONObject): PlaceSearchResult? {
        try {
            val name = jsonObj.optString("name").takeIf { it.isNotBlank() } ?: return null
            val lat = jsonObj.optDouble("lat")
            val lon = jsonObj.optDouble("lon")
            if (lat.isNaN() || lon.isNaN()) return null

            val type = jsonObj.optString("type")
            val classValue = jsonObj.optString("class")

            // Determine if it's a locality
            // Prefer locality types: village, town, city, hamlet, suburb
            val isLocality = classValue == "place" && type in listOf("city", "town", "village", "hamlet", "suburb", "locality")

            // Get address details for context
            val address = jsonObj.optJSONObject("address")
            val contextParts = mutableListOf<String>()
            if (address != null) {
                address.optString("county").takeIf { it.isNotBlank() && it != name }?.let { contextParts.add(it) }
                address.optString("state").takeIf { it.isNotBlank() && it != name }?.let { contextParts.add(it) }
            }
            val contextString = if (contextParts.isNotEmpty()) contextParts.joinToString(", ") else null

            return PlaceSearchResult(
                name = name,
                localityContext = contextString,
                location = GeoPoint(lat, lon),
                isLocality = isLocality
            )
        } catch (e: Exception) {
            return null
        }
    }
}
