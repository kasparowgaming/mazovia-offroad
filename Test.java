import java.util.Locale;

public class Test {
    public static void main(String[] args) {
        Locale.setDefault(new Locale("pl", "PL"));
        
        double[] distances = {26.0, 280.0, 910.0, 1200.0, 2400.0, 10800.0};
        for (double meters : distances) {
            String text = (meters >= 1000) ? String.format("%.1f km", meters / 1000) : String.format("%.0f m", meters);
            
            String value = text.replaceAll("[^0-9.]", "");
            String unit = text.replaceAll("[0-9. ]", "").toUpperCase();
            
            System.out.println("METERS: " + meters + " -> TEXT: '" + text + "' -> VALUE: '" + value + "' UNIT: '" + unit + "'");
        }
    }
}
