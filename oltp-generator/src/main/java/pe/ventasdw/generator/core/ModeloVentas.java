package pe.ventasdw.generator.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Random;
 
public final class ModeloVentas {
 
    private static final double[] FACTOR_MES = {0.95, 0.90, 0.95, 1.00, 1.10, 1.00, 1.12, 1.00, 0.98, 1.02, 1.15, 1.45};

    private static final String[] ENTREGADO = {
        "ENTREGADO", "ENTREGADO", "ENTREGADO", "ENTREGADO", "ENTREGADO", "ENTREGADO", "ENTREGADO", "ENTREGADO",
        "ENT", "E", "Entregado"};

    private ModeloVentas() {
    }
 
    public static double factorEstacional(LocalDate fecha) {
        double mes = FACTOR_MES[fecha.getMonthValue() - 1];
        double dia = switch (fecha.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> 1.25;
            case FRIDAY -> 1.10;
            case MONDAY -> 0.95;
            default -> 1.00;
        };
        return mes * dia;
    }

    /** Numero de pedidos del dia: base por estacionalidad, con ruido normal de 8%. Al menos 1. */
    public static int pedidosDelDia(Random r, int base, LocalDate fecha) {
        double n = base * factorEstacional(fecha) * (1 + 0.08 * r.nextGaussian());
        return Math.max(1, (int) Math.round(n));
    }

    /** Lineas por pedido: la mayoria tiene 1 a 3. */
    public static int lineasPorPedido(Random r) {
        return elegir(r, new int[] {1, 2, 3, 4, 5, 6}, new int[] {35, 28, 18, 10, 6, 3});
    }

    public static int cantidad(Random r) {
        return elegir(r, new int[] {1, 2, 3, 4, 6, 12}, new int[] {55, 25, 10, 5, 3, 2});
    }

    public static double descuento(Random r) {
        int c = elegir(r, new int[] {0, 5, 10, 15, 20}, new int[] {60, 15, 12, 8, 5});
        return c / 100.0;
    }

    /** Un estado "entregado" con las variantes de codificacion que existen en los sistemas fuente. */
    public static String estadoEntregado(Random r) {
        return ENTREGADO[r.nextInt(ENTREGADO.length)];
    }

    public static boolean esFinDeSemana(LocalDate fecha) {
        DayOfWeek d = fecha.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** Elige un valor segun pesos enteros. */
    static int elegir(Random r, int[] valores, int[] pesos) {
        int total = 0;
        for (int p : pesos) {
            total += p;
        }
        int x = r.nextInt(total);
        for (int i = 0; i < valores.length; i++) {
            x -= pesos[i];
            if (x < 0) {
                return valores[i];
            }
        }
        return valores[valores.length - 1];
    }
}
