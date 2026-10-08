package pe.ventasdw.etl.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Compara los errores que el generador inyectó a propósito (tabla qa_error_inyectado del OLTP) con los
 * que el ETL detectó (etl.excepcion del DWH). Es la prueba de que las reglas de calidad funcionan.
 *
 * <p>Un pedido con fecha futura se rechaza completo en la primera regla, así que los otros errores inyectados
 * en sus líneas no llegan a evaluarse. Esos errores "enmascarados" no se cuentan como esperados.
 */
public final class QaComparador {

    public record Fila(String fuente, String tipo, long inyectados, long detectados) {
        public boolean coincide() {
            return inyectados == detectados;
        }
    }

    private QaComparador() {
    }

    public static List<Fila> comparar(DataSource oltp, DataSource dwh) {
        Map<String, long[]> filas = new TreeMap<>();
        try (Connection c = oltp.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT q.fuente, q.tipo, count(*) FROM qa_error_inyectado q "
                             + "WHERE q.tipo = 'FECHA_FUTURA' OR NOT EXISTS (SELECT 1 FROM qa_error_inyectado f "
                             + "WHERE f.fuente = q.fuente AND f.pedido_id = q.pedido_id AND f.tipo = 'FECHA_FUTURA') "
                             + "GROUP BY 1, 2")) {
            while (rs.next()) {
                filas.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new long[2])[0] = rs.getLong(3);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer qa_error_inyectado", e);
        }
        try (Connection c = dwh.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT split_part(clave_natural, ':', 1), regla, "
                             + "count(DISTINCT CASE WHEN regla = 'FECHA_FUTURA' THEN split_part(clave_natural, ':', 1) || ':' || split_part(clave_natural, ':', 2) "
                             + "ELSE clave_natural END) FROM etl.excepcion WHERE tabla = 'venta_linea' GROUP BY 1, 2")) {
            while (rs.next()) {
                filas.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new long[2])[1] = rs.getLong(3);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer etl.excepcion", e);
        }
        List<Fila> resultado = new ArrayList<>();
        for (Map.Entry<String, long[]> e : filas.entrySet()) {
            String[] k = e.getKey().split("\\|");
            // Reglas propias del ETL que el generador no inyecta (p. ej. ESTADO_DESCONOCIDO) no se comparan
            if (e.getValue()[0] == 0 && !List.of("PRECIO_NULO", "PRODUCTO_NULO").contains(k[1]) && e.getValue()[1] > 0
                    && !inyectables.contains(k[1])) {
                continue;
            }
            resultado.add(new Fila(k[0], k[1], e.getValue()[0], e.getValue()[1]));
        }
        return resultado;
    }

    private static final List<String> inyectables = List.of("PRECIO_NULO", "CANTIDAD_NO_POSITIVA", "PRECIO_ATIPICO",
            "PRODUCTO_NULO", "PRODUCTO_INEXISTENTE", "FECHA_FUTURA", "LINEA_DUPLICADA");
}
