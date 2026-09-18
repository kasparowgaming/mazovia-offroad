import java.util.Locale

fun main() {
    Locale.setDefault(Locale("pl", "PL"))
    
    val distances = listOf(26.0, 280.0, 910.0, 1200.0, 2400.0, 10800.0)
    for (meters in distances) {
        val text = if (meters >= 1000) String.format("%.1f km", meters / 1000) else String.format("%.0f m", meters)
        
        val value = text.replace(Regex("[^0-9.]"), "")
        val unit = text.replace(Regex("[0-9. ]"), "").uppercase()
        
        println("METERS: $meters -> TEXT: "" -> VALUE: "" UNIT: """)
    }
}
