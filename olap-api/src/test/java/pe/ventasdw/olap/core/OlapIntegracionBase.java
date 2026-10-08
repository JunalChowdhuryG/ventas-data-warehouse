package pe.ventasdw.olap.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Pruebas del motor OLAP contra PostgreSQL real y un cubo pequeño cuyos totales se pueden verificar a mano.
 *
 * <pre>
 *  fecha       canal   prod cli ciudad pedido-línea cant precio desc  importe
 *  2025-02-10  Tienda  P1   C1  Lima   1-1   2   10.00 0     20.00
 *  2025-02-10  Tienda  P2   C1  Lima   1-2   1   20.00 0     20.00
 *  2025-05-03  Online  P3   C2  Cusco  1-1   3    5.00 0.10  13.50   (mismo número de pedido que el de Tienda)
 *  2026-01-15  Tienda  P1   C2  Cusco  2-1   1   10.00 0     10.00
 *  2026-04-20  Online  P2   C1  Lima   3-1   4   20.00 0.25  60.00
 *  2026-04-20  Online  P3   C1  Lima   3-2   2    5.00 0     10.00
 * </pre>
 * P1 y P2 son de la categoría A; P3, de la B. Totales: ventas 133.50, unidades 13, pedidos 4, líneas 6.
 */
public abstract class OlapIntegracionBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    protected abstract String urlServidor();

    protected abstract String usuario();

    protected abstract String clave();

    protected abstract String baseAdministrativa();

    protected abstract void migrarDwh(PGSimpleDataSource dwh) throws Exception;

    protected PGSimpleDataSource dwh;
    protected OlapService olap;

    @BeforeEach
    void prepararCubo() throws Exception {
        String nombre = "olap_t" + SECUENCIA.incrementAndGet() + "_" + System.nanoTime() % 100000;
        try (Connection c = fuente(baseAdministrativa()).getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + nombre);
        }
        dwh = fuente(nombre);
        migrarDwh(dwh);
        sql("INSERT INTO dwh.dim_geografia (ciudad_id, pais, departamento, provincia, ciudad) VALUES "
                + "(1, 'Perú', 'Lima', 'Lima', 'Lima'), (2, 'Perú', 'Cusco', 'Cusco', 'Cusco');"
                + "INSERT INTO dwh.dim_empleado (empleado_id, empleado, cargo) VALUES (1, 'Eva', 'Vendedora');"
                + "INSERT INTO dwh.dim_cliente (cliente_id, cliente, hash_atributos, vigente_desde) VALUES "
                + "(1, 'C1', md5('C1|'), DATE '1900-01-01'), (2, 'C2', md5('C2|'), DATE '1900-01-01');"
                + "INSERT INTO dwh.dim_producto (producto_id, producto, categoria, hash_atributos, vigente_desde) VALUES "
                + "(101, 'P1', 'A', md5('P1|A'), DATE '1900-01-01'), (102, 'P2', 'A', md5('P2|A'), DATE '1900-01-01'), "
                + "(103, 'P3', 'B', md5('P3|B'), DATE '1900-01-01');");
        hecho(20250210, 1, 101, 1, 1, 1, 1, 2, "10.00", "0");
        hecho(20250210, 1, 102, 1, 1, 1, 2, 1, "20.00", "0");
        hecho(20250503, 2, 103, 2, 2, 1, 1, 3, "5.00", "0.10");
        hecho(20260115, 1, 101, 2, 2, 2, 1, 1, "10.00", "0");
        hecho(20260420, 2, 102, 1, 1, 3, 1, 4, "20.00", "0.25");
        hecho(20260420, 2, 103, 1, 1, 3, 2, 2, "5.00", "0");
        sql("SELECT dwh.refrescar_agregados()");
        olap = new OlapService(dwh);
    }

    private void hecho(int fecha, int canal, int producto, int cliente, int ciudad, long pedido, int linea, int cantidad,
                       String precio, String descuento) throws SQLException {
        sql("INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key, "
                + "pedido_id, linea, cantidad, precio_unitario, descuento) "
                + "SELECT " + fecha + ", " + canal + ", p.producto_key, c.cliente_key, g.geografia_key, e.empleado_key, "
                + pedido + ", " + linea + ", " + cantidad + ", " + precio + ", " + descuento
                + " FROM dwh.dim_producto p, dwh.dim_cliente c, dwh.dim_geografia g, dwh.dim_empleado e "
                + "WHERE p.producto_id = " + producto + " AND c.cliente_id = " + (cliente == 1 ? 1 : 2)
                + " AND g.ciudad_id = " + ciudad + " AND e.empleado_id = 1");
    }

    // ----------------------------------------------------------------------------------- consulta

    @Test
    void totalesGeneralesDelCubo() {
        Resultado r = olap.consultar(ctx(List.of(), List.of("ventas", "unidades", "pedidos", "lineas", "ticket_promedio")));
        Map<String, Object> t = r.datos().get(0);
        assertThat(num(t, "ventas")).isEqualByComparingTo("133.50");
        assertThat(num(t, "unidades")).isEqualByComparingTo("13");
        assertThat(num(t, "pedidos")).isEqualByComparingTo("4");        // pedido 1 existe en dos canales: son dos pedidos
        assertThat(num(t, "lineas")).isEqualByComparingTo("6");
        assertThat(num(t, "ticket_promedio")).isEqualByComparingTo("33.38");
    }

    @Test
    void ventasPorAnioYCategoriaYAgregadoEquivalente() {
        Contexto base = ctx(List.of("fecha.anio", "producto.categoria"), List.of("ventas", "unidades"));
        Resultado conAgregado = olap.consultar(base);
        Resultado sinAgregado = olap.consultar(new Contexto(base.filas(), null, base.medidas(), null, null, null, null, false));

        assertThat(conAgregado.origen()).isEqualTo("mv_ventas_mensual_categoria");
        assertThat(sinAgregado.origen()).isEqualTo("fact_ventas");
        assertThat(conAgregado.datos()).isEqualTo(sinAgregado.datos());
        assertThat(conAgregado.datos()).hasSize(4);
        assertThat(num(fila(conAgregado, "fecha.anio", 2025, "producto.categoria", "A"), "ventas")).isEqualByComparingTo("40.00");
        assertThat(num(fila(conAgregado, "fecha.anio", 2026, "producto.categoria", "A"), "ventas")).isEqualByComparingTo("70.00");
        assertThat(num(fila(conAgregado, "fecha.anio", 2025, "producto.categoria", "B"), "ventas")).isEqualByComparingTo("13.50");
        assertThat(num(fila(conAgregado, "fecha.anio", 2026, "producto.categoria", "B"), "ventas")).isEqualByComparingTo("10.00");
    }

    @Test
    void filtrosPorValorYPorRango() {
        Resultado porValor = olap.consultar(new Contexto(List.of("canal.canal"), null, List.of("ventas"),
                List.of(new Contexto.Filtro("fecha.anio", List.of("2026"), null, null)), null, null, null, false));
        assertThat(porValor.datos()).hasSize(2);
        assertThat(num(fila(porValor, "canal.canal", "Tienda física"), "ventas")).isEqualByComparingTo("10.00");
        assertThat(num(fila(porValor, "canal.canal", "Online"), "ventas")).isEqualByComparingTo("70.00");

        Resultado porRango = olap.consultar(new Contexto(List.of(), null, List.of("ventas", "lineas"),
                List.of(new Contexto.Filtro("fecha.dia", null, "2025-02-01", "2025-05-31")), null, null, null, null));
        assertThat(num(porRango.datos().get(0), "ventas")).isEqualByComparingTo("53.50");
        assertThat(num(porRango.datos().get(0), "lineas")).isEqualByComparingTo("3");
    }

    @Test
    void ordenaPorUnaMedidaYLimitaMarcandoElTruncamiento() {
        Resultado r = olap.consultar(new Contexto(List.of("producto.producto"), null, List.of("ventas"), null,
                List.of(new Contexto.Orden("ventas", "desc")), 2, null, false));
        assertThat(r.datos()).hasSize(2);
        assertThat(r.truncado()).isTrue();
        assertThat(r.datos().get(0).get("producto.producto")).isEqualTo("P2");     // 80.00, el mayor
        assertThat(r.datos().get(1).get("producto.producto")).isEqualTo("P1");     // 30.00
    }

    // ----------------------------------------------------------------------------------- operaciones

    @Test
    void drillDownYDrillUpRecorrenLaJerarquiaDeFecha() {
        Resultado porAnio = olap.consultar(ctx(List.of("fecha.anio"), List.of("ventas")));
        Resultado abajo = olap.drillDown(porAnio.contexto(), "fecha", Map.of("fecha.anio", "2025"));

        assertThat(abajo.contexto().filas()).containsExactly("fecha.anio", "fecha.trimestre");
        assertThat(abajo.datos()).hasSize(2);                                       // trimestres 1 y 2 de 2025
        assertThat(num(fila(abajo, "fecha.trimestre", 1), "ventas")).isEqualByComparingTo("40.00");
        assertThat(num(fila(abajo, "fecha.trimestre", 2), "ventas")).isEqualByComparingTo("13.50");

        Resultado arriba = olap.drillUp(abajo.contexto(), "fecha");
        assertThat(arriba.contexto().filas()).containsExactly("fecha.anio");
        assertThat(arriba.operacionesDisponibles().get("drillDown")).contains("fecha");

        assertThatThrownBy(() -> olap.drillUp(porAnio.contexto(), "fecha")).isInstanceOf(OlapException.class).hasMessageContaining("nivel superior");
        assertThatThrownBy(() -> olap.drillDown(ctx(List.of("canal.canal"), List.of("ventas")), "canal", null))
                .isInstanceOf(OlapException.class).hasMessageContaining("jerarquía");
    }

    @Test
    void drillDownLlegaHastaElNivelMasBajoYNoMasAllaDeEl() {
        Contexto c = ctx(List.of("fecha.anio", "fecha.trimestre", "fecha.mes", "fecha.dia"), List.of("ventas"));
        assertThatThrownBy(() -> olap.drillDown(c, "fecha", null)).isInstanceOf(OlapException.class).hasMessageContaining("más bajo");
    }

    @Test
    void drillDownDeUnaDimensionAusenteAgregaSuNivelSuperior() {
        Resultado r = olap.drillDown(ctx(List.of("fecha.anio"), List.of("ventas")), "geografia", null);
        assertThat(r.contexto().filas()).containsExactly("fecha.anio", "geografia.pais");
    }

    @Test
    void drillAcrossAgregaUnCriterioYRollAcrossLoQuitaRecuperandoLaConsultaOriginal() {
        Resultado original = olap.consultar(ctx(List.of("fecha.anio"), List.of("ventas")));
        Resultado across = olap.drillAcross(original.contexto(), "canal.canal");
        assertThat(across.datos()).hasSize(4);                                      // 2 años x 2 canales

        Resultado roll = olap.rollAcross(across.contexto(), "canal.canal");
        assertThat(roll.datos()).isEqualTo(original.datos());

        assertThatThrownBy(() -> olap.drillAcross(original.contexto(), "fecha.anio")).isInstanceOf(OlapException.class);
        assertThatThrownBy(() -> olap.rollAcross(original.contexto(), "canal.canal")).isInstanceOf(OlapException.class);
    }

    @Test
    void pivotMueveAtributosAColumnasYConservaLosTotales() {
        Contexto plano = ctx(List.of("producto.categoria", "fecha.anio"), List.of("ventas"));
        Resultado p = olap.pivot(plano, List.of("producto.categoria"), List.of("fecha.anio"));

        assertThat(p.pivot().columnas()).containsExactly(List.of(2025), List.of(2026));
        assertThat(p.pivot().filas()).hasSize(2);
        var filaA = p.pivot().filas().get(0);
        assertThat(filaA.clave()).containsExactly("A");
        assertThat(num(filaA.celdas().get(0), "ventas")).isEqualByComparingTo("40.00");
        assertThat(num(filaA.celdas().get(1), "ventas")).isEqualByComparingTo("70.00");

        assertThatThrownBy(() -> olap.pivot(plano, List.of("producto.categoria"), List.of("canal.canal")))
                .isInstanceOf(OlapException.class).hasMessageContaining("reordena");
    }

    @Test
    void pivotDejaCeldasNulasDondeNoHayDatos() {
        // Online solo vende en 2025 la categoría B y en 2026 las dos; Tienda no vende B
        Resultado p = olap.pivot(ctx(List.of("canal.canal", "producto.categoria"), List.of("ventas")),
                List.of("producto.categoria"), List.of("canal.canal"));
        var filaB = p.pivot().filas().stream().filter(f -> f.clave().equals(List.of("B"))).findFirst().orElseThrow();
        int iTienda = p.pivot().columnas().indexOf(List.of("Tienda física"));
        assertThat(filaB.celdas().get(iTienda)).isNull();
    }

    @Test
    void pageDivideElCuboPorLosValoresDeUnAtributo() {
        Contexto c = ctx(List.of("producto.categoria"), List.of("ventas"));
        Resultado online = olap.page(c, "canal.canal", "Online");
        Resultado tienda = olap.page(c, "canal.canal", "Tienda física");

        assertThat(online.paginas().valores()).containsExactly("Online", "Tienda física");
        assertThat(online.paginas().actual()).isEqualTo("Online");
        assertThat(num(fila(online, "producto.categoria", "A"), "ventas")).isEqualByComparingTo("60.00");
        assertThat(num(fila(tienda, "producto.categoria", "A"), "ventas")).isEqualByComparingTo("50.00");
        assertThat(tienda.datos()).hasSize(1);                                      // Tienda no vendió categoría B
        // Página por defecto = la primera
        assertThat(olap.page(c, "canal.canal", null).paginas().actual()).isEqualTo("Online");
        assertThatThrownBy(() -> olap.page(c, "canal.canal", "Telefónico")).isInstanceOf(OlapException.class).hasMessageContaining("no existe");
    }

    @Test
    void subtotalesYTotalGeneralConRollup() {
        Resultado r = olap.consultar(new Contexto(List.of("fecha.anio", "canal.canal"), null, List.of("ventas"), null, null, null, true, false));
        List<Map<String, Object>> subtotales = r.datos().stream().filter(f -> Boolean.TRUE.equals(f.get("_subtotal"))).toList();
        List<Map<String, Object>> totales = r.datos().stream().filter(f -> Boolean.TRUE.equals(f.get("_total"))).toList();

        assertThat(subtotales).hasSize(2);
        assertThat(num(subtotales.get(0), "ventas")).isEqualByComparingTo("53.50");  // 2025
        assertThat(num(subtotales.get(1), "ventas")).isEqualByComparingTo("80.00");  // 2026
        assertThat(totales).hasSize(1);
        assertThat(num(totales.get(0), "ventas")).isEqualByComparingTo("133.50");
        assertThat(r.datos()).hasSize(4 + 2 + 1);
    }

    // ----------------------------------------------------------------------------------- metadatos y seguridad

    @Test
    void listaLosValoresDeUnAtributoConBusquedaOpcional() {
        assertThat(olap.valoresDe("producto.categoria", null, 50)).contains("A", "B");
        assertThat(olap.valoresDe("producto.producto", "p2", 50)).containsExactly("P2");
        assertThat(olap.valoresDe("fecha.anio", null, 50)).contains(2025, 2026);
    }

    @Test
    void rechazaAtributosMaliciososYTrataLosValoresComoParametros() {
        assertThatThrownBy(() -> olap.consultar(ctx(List.of("fecha.anio; DROP TABLE dwh.fact_ventas"), List.of("ventas"))))
                .isInstanceOf(OlapException.class);
        Resultado r = olap.consultar(new Contexto(List.of("producto.categoria"), null, List.of("ventas"),
                List.of(new Contexto.Filtro("producto.categoria", List.of("A' OR '1'='1"), null, null)), null, null, null, false));
        assertThat(r.datos()).isEmpty();
    }

    @Test
    void elJsonDeLaRespuestaTieneLaEstructuraEsperada() {
        String json = olap.consultar(ctx(List.of("fecha.anio"), List.of("ventas"))).aJson(false);
        assertThat(json).startsWith("{\"contexto\":{\"filas\":[\"fecha.anio\"]").contains("\"datos\":[{\"fecha.anio\":2025,\"ventas\":53.50}")
                .contains("\"origen\":\"mv_ventas_mensual_categoria\"").contains("\"operacionesDisponibles\":{\"drillDown\":").doesNotContain("\"sql\"");
        assertThat(olap.consultar(ctx(List.of("fecha.anio"), List.of("ventas"))).aJson(true)).contains("\"sql\":\"SELECT");
    }

    // ----------------------------------------------------------------------------------- utilidades

    private static Contexto ctx(List<String> filas, List<String> medidas) {
        return new Contexto(filas, null, medidas, null, null, null, null, null);
    }

    private static Map<String, Object> fila(Resultado r, Object... clavesYValores) {
        return r.datos().stream().filter(f -> {
            for (int i = 0; i < clavesYValores.length; i += 2) {
                if (!clavesYValores[i + 1].equals(f.get((String) clavesYValores[i]))) {
                    return false;
                }
            }
            return true;
        }).findFirst().orElseThrow(() -> new AssertionError("No hay fila con " + List.of(clavesYValores)));
    }

    private static BigDecimal num(Map<String, Object> fila, String medida) {
        return new BigDecimal(fila.get(medida).toString());
    }

    private PGSimpleDataSource fuente(String base) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(urlServidor() + base);
        ds.setUser(usuario());
        ds.setPassword(clave());
        return ds;
    }

    private void sql(String sentencias) throws SQLException {
        try (Connection c = dwh.getConnection(); Statement s = c.createStatement()) {
            s.execute(sentencias);
        }
    }
}
