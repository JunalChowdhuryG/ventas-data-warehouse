package pe.ventasdw.etl.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Pruebas de integración del ETL contra PostgreSQL real, con un conjunto de datos pequeño y conocido
 * donde cada regla de calidad se dispara exactamente una vez. Cada prueba usa bases nuevas (OLTP y DWH).
 * Las subclases dicen dónde está el servidor y cómo se migra el DWH.
 */
public abstract class EtlIntegracionBase {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /** URL del servidor terminada en barra, por ejemplo jdbc:postgresql://localhost:5432/ */
    protected abstract String urlServidor();

    protected abstract String usuario();

    protected abstract String clave();

    protected abstract String baseAdministrativa();

    /** Aplica las migraciones del DWH (Flyway en Testcontainers). */
    protected abstract void migrarDwh(PGSimpleDataSource dwh) throws Exception;

    protected PGSimpleDataSource oltp;
    protected PGSimpleDataSource dwh;
    protected Path csv;
    protected EtlService etl;

    @BeforeEach
    void prepararBases(@TempDir Path directorio) throws Exception {
        int n = SECUENCIA.incrementAndGet();
        String nombreOltp = "oltp_t" + n + "_" + System.nanoTime() % 100000;
        String nombreDwh = "dwh_t" + n + "_" + System.nanoTime() % 100000;
        try (Connection c = fuente(baseAdministrativa()).getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + nombreOltp);
            s.execute("CREATE DATABASE " + nombreDwh);
        }
        oltp = fuente(nombreOltp);
        dwh = fuente(nombreDwh);
        script(oltp, "/oltp/oltp_esquema.sql");
        migrarDwh(dwh);
        csv = directorio.resolve("csv");
        Files.createDirectories(csv);
        etl = new EtlService(dwh, oltp, new EtlConfig(csv, new BigDecimal("3.70"), 5, 1000));
    }

    private PGSimpleDataSource fuente(String base) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(urlServidor() + base);
        ds.setUser(usuario());
        ds.setPassword(clave());
        return ds;
    }

    // ----------------------------------------------------------------------------- datos de prueba

    /** Catálogo mínimo y cuatro pedidos del OLTP; el CSV online trae otras cuatro filas (una duplicada). */
    private void datosBase() throws Exception {
        LocalDate futura = LocalDate.now(LIMA).plusDays(10);
        sql(oltp, "INSERT INTO ciudad (ciudad, provincia, departamento) VALUES ('Lima', 'Lima', 'Lima');"
                + "INSERT INTO categoria (nombre) VALUES ('A'), ('B');"
                + "INSERT INTO producto (nombre, categoria_id, precio_lista) VALUES ('P1', 1, 10.00), ('P2', 1, 20.00), ('P3', 2, 5.00);"
                + "INSERT INTO cliente (nombre, email, ciudad_id) VALUES ('Ana', 'ana@x.pe', 1), ('Beto', 'beto@x.pe', 1);"
                + "INSERT INTO empleado (nombre, cargo) VALUES ('Eva', 'Vendedora');"
                + "INSERT INTO pedido (pedido_id, cliente_id, empleado_id, fecha_pedido, estado) VALUES "
                + "(1, 1, 1, DATE '2026-03-10', 'ENTREGADO'), (2, 2, 1, DATE '" + futura + "', 'ENTREGADO'), "
                + "(3, 1, 1, DATE '2026-03-11', 'CANCELADO'), (4, 2, 1, DATE '2026-03-12', 'E');"
                + "INSERT INTO detalle_pedido (pedido_id, linea, producto_id, cantidad, precio_unitario, descuento) VALUES "
                + "(1, 1, 1, 2, 10.00, 0), "          // normal: 20.00
                + "(1, 2, 2, 1, NULL, 0), "           // precio nulo: se imputa con el de lista (20.00)
                + "(1, 3, 1, 0, 10.00, 0), "          // cantidad 0: se rechaza
                + "(1, 4, 2, 1, 1000.00, 0), "        // precio atípico (50 veces el de lista): se carga con advertencia
                + "(1, 5, NULL, 1, 5.00, 0), "        // producto nulo: miembro Desconocido
                + "(2, 1, 1, 1, 10.00, 0), "          // fecha futura: se rechaza
                + "(3, 1, 1, 1, 10.00, 0), "          // pedido cancelado: no cuenta como venta
                + "(4, 1, 3, 3, 5.00, 0.10);");       // estado 'E' = ENTREGADO: 3 x 5.00 x 0.90 = 13.50
        Files.writeString(csv.resolve("ventas_online_20260310.csv"), String.join("\n",
                "pedido_online_id,linea,fecha_pedido,estado,cliente_id,razon_social,email,ciudad_id,producto_id,descripcion,categoria,cantidad,precio_unitario,descuento,moneda,updated_at",
                "1,1,2026-03-10,Entregado,1,ANA,,1,3,Chocolate,B,2,2.00,0.0,USD,2026-03-10T15:00:00-05:00",   // 2 x 2.00 USD x 3.70 = 14.80
                "1,1,2026-03-10,Entregado,1,ANA,,1,3,Chocolate,B,2,2.00,0.0,USD,2026-03-10T15:00:00-05:00",   // duplicada
                "1,2,2026-03-10,ENT,1,ANA,,1,99999,\"Producto, desconocido\",B,1,3.00,0.0,PEN,2026-03-10T15:00:00-05:00",  // producto inexistente: 3.00
                "2,1,2026-03-10,E,2,BETO,,1,1,P1,A,1,,0.0,PEN,2026-03-10T16:00:00-05:00",                     // precio vacío: se imputa 10.00
                ""), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------------------- pruebas

    @Test
    void cargaInicialAplicaCadaReglaDeCalidadYCargaLosHechosEsperados() throws Exception {
        datosBase();

        EtlResultado r = etl.ejecutar(EtlService.Modo.AUTO);

        assertThat(r.estado()).isEqualTo("EXITOSA");
        assertThat(r.tipoCarga()).isEqualTo("INICIAL");
        assertThat(r.archivosCsv()).containsExactly("ventas_online_20260310.csv");
        // 5 filas del OLTP + 3 del CSV (sin la duplicada)
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas")).isEqualTo(8L);
        assertThat(decimal("SELECT sum(importe_total) FROM dwh.fact_ventas")).isEqualByComparingTo("1086.30");
        assertThat(r.filasCargadas()).isEqualTo(8L);
        assertThat(r.filasExcluidas()).isEqualTo(1L);        // pedido cancelado
        assertThat(r.filasRechazadas()).isEqualTo(3L);       // cantidad 0, fecha futura, duplicada

        Map<String, Long> reglas = new HashMap<>();
        try (Connection c = dwh.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT regla, count(*) FROM etl.excepcion GROUP BY regla")) {
            while (rs.next()) {
                reglas.put(rs.getString(1), rs.getLong(2));
            }
        }
        assertThat(reglas).containsEntry("CANTIDAD_NO_POSITIVA", 1L).containsEntry("FECHA_FUTURA", 1L)
                .containsEntry("PRECIO_NULO", 2L).containsEntry("PRECIO_ATIPICO", 1L)
                .containsEntry("PRODUCTO_NULO", 1L).containsEntry("PRODUCTO_INEXISTENTE", 1L)
                .containsEntry("LINEA_DUPLICADA", 1L).hasSize(7);

        // Producto desconocido: dos hechos apuntan al miembro -1 (nulo en OLTP, inexistente en CSV)
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas WHERE producto_key = -1")).isEqualTo(2L);
        // Los dos canales conviven aunque el id de pedido coincida (1 en OLTP y 1 en CSV)
        assertThat(num("SELECT count(DISTINCT canal_key) FROM dwh.fact_ventas WHERE pedido_id = 1")).isEqualTo(2L);
        // El control quedó listo para cargas incrementales
        assertThat(num("SELECT count(*) FROM etl.control WHERE fuente = 'OLTP' AND carga_inicial_completa")).isEqualTo(6L);
    }

    @Test
    void repetirLaCargaNoCambiaNadaYLasIncrementalesSoloMuevenLoNuevo() throws Exception {
        datosBase();
        etl.ejecutar(EtlService.Modo.AUTO);
        BigDecimal antes = decimal("SELECT sum(importe_total) FROM dwh.fact_ventas");

        EtlResultado repetida = etl.ejecutar(EtlService.Modo.AUTO);
        assertThat(repetida.tipoCarga()).isEqualTo("INCREMENTAL");
        assertThat(repetida.filasCargadas()).isZero();       // la ventana de solape relee, pero no hay nada distinto
        assertThat(repetida.archivosCsv()).isEmpty();        // el CSV ya procesado no se vuelve a leer
        assertThat(decimal("SELECT sum(importe_total) FROM dwh.fact_ventas")).isEqualByComparingTo(antes);

        // Un pedido nuevo y un CSV nuevo llegan después
        LocalDate hoy = LocalDate.now(LIMA);
        sql(oltp, "INSERT INTO pedido (pedido_id, cliente_id, empleado_id, fecha_pedido, estado) VALUES (10, 1, 1, DATE '" + hoy + "', 'PENDIENTE');"
                + "INSERT INTO detalle_pedido (pedido_id, linea, producto_id, cantidad, precio_unitario, descuento) VALUES (10, 1, 2, 1, 20.00, 0);");
        Files.writeString(csv.resolve("ventas_online_20260311.csv"), String.join("\n",
                "pedido_online_id,linea,fecha_pedido,estado,cliente_id,razon_social,email,ciudad_id,producto_id,descripcion,categoria,cantidad,precio_unitario,descuento,moneda,updated_at",
                "5,1,2026-03-11,ENT,2,BETO,,1,2,P2,A,1,20.00,0.0,PEN,2026-03-11T10:00:00-05:00", ""), StandardCharsets.UTF_8);

        EtlResultado nueva = etl.ejecutar(EtlService.Modo.AUTO);
        assertThat(nueva.filasCargadas()).isEqualTo(2L);
        assertThat(nueva.archivosCsv()).containsExactly("ventas_online_20260311.csv");
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas")).isEqualTo(10L);
    }

    @Test
    void scd2ConservaLaHistoriaYLasVentasNuevasUsanLaVersionVigente() throws Exception {
        datosBase();
        etl.ejecutar(EtlService.Modo.AUTO);
        assertThat(num("SELECT count(*) FROM dwh.dim_producto WHERE producto_id = 1")).isEqualTo(1L);

        // P1 cambia de categoría A a B; además entra una venta nueva de hoy
        LocalDate hoy = LocalDate.now(LIMA);
        sql(oltp, "UPDATE producto SET categoria_id = 2 WHERE producto_id = 1;"
                + "INSERT INTO pedido (pedido_id, cliente_id, empleado_id, fecha_pedido, estado) VALUES (11, 1, 1, DATE '" + hoy + "', 'PENDIENTE');"
                + "INSERT INTO detalle_pedido (pedido_id, linea, producto_id, cantidad, precio_unitario, descuento) VALUES (11, 1, 1, 1, 10.00, 0);");
        etl.ejecutar(EtlService.Modo.AUTO);

        assertThat(num("SELECT count(*) FROM dwh.dim_producto WHERE producto_id = 1")).isEqualTo(2L);
        assertThat(num("SELECT count(*) FROM dwh.dim_producto WHERE producto_id = 1 AND es_actual")).isEqualTo(1L);
        // La venta antigua sigue en la categoría A, la nueva usa la B
        assertThat(texto("SELECT p.categoria FROM dwh.fact_ventas f JOIN dwh.dim_producto p USING (producto_key) "
                + "JOIN dwh.dim_canal c USING (canal_key) WHERE c.codigo = 'TIENDA' AND f.pedido_id = 1 AND f.linea = 1")).isEqualTo("A");
        assertThat(texto("SELECT p.categoria FROM dwh.fact_ventas f JOIN dwh.dim_producto p USING (producto_key) "
                + "JOIN dwh.dim_canal c USING (canal_key) WHERE c.codigo = 'TIENDA' AND f.pedido_id = 11")).isEqualTo("B");
        // Ninguna venta apunta a una versión que no estaba vigente en su fecha
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas f JOIN dwh.dim_producto p USING (producto_key) "
                + "JOIN dwh.dim_fecha d USING (fecha_key) WHERE p.producto_key <> -1 "
                + "AND NOT (d.fecha >= p.vigente_desde AND d.fecha < p.vigente_hasta)")).isZero();

        // Un segundo cambio el mismo día corrige la versión actual, no crea otra
        sql(oltp, "UPDATE producto SET nombre = 'P1 renombrado' WHERE producto_id = 1;");
        etl.ejecutar(EtlService.Modo.AUTO);
        assertThat(num("SELECT count(*) FROM dwh.dim_producto WHERE producto_id = 1")).isEqualTo(2L);
        assertThat(texto("SELECT producto FROM dwh.dim_producto WHERE producto_id = 1 AND es_actual")).isEqualTo("P1 renombrado");
    }

    @Test
    void unPedidoYaCargadoQuePasaACanceladoSaleDelDwh() throws Exception {
        datosBase();
        etl.ejecutar(EtlService.Modo.AUTO);
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas f JOIN dwh.dim_canal c USING (canal_key) "
                + "WHERE c.codigo = 'TIENDA' AND f.pedido_id = 4")).isEqualTo(1L);

        sql(oltp, "UPDATE pedido SET estado = 'CANCELADO' WHERE pedido_id = 4;");
        etl.ejecutar(EtlService.Modo.AUTO);

        assertThat(num("SELECT count(*) FROM dwh.fact_ventas f JOIN dwh.dim_canal c USING (canal_key) "
                + "WHERE c.codigo = 'TIENDA' AND f.pedido_id = 4")).isZero();
    }

    @Test
    void laCargaTotalReconstruyeElMismoResultadoSinDuplicarExcepciones() throws Exception {
        datosBase();
        etl.ejecutar(EtlService.Modo.AUTO);
        BigDecimal importe = decimal("SELECT sum(importe_total) FROM dwh.fact_ventas");
        long excepciones = num("SELECT count(*) FROM etl.excepcion");

        EtlResultado total = etl.ejecutar(EtlService.Modo.TOTAL);

        assertThat(total.tipoCarga()).isEqualTo("TOTAL");
        assertThat(total.archivosCsv()).containsExactly("ventas_online_20260310.csv");   // se vuelven a leer
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas")).isEqualTo(8L);
        assertThat(decimal("SELECT sum(importe_total) FROM dwh.fact_ventas")).isEqualByComparingTo(importe);
        assertThat(num("SELECT count(*) FROM etl.excepcion")).isEqualTo(excepciones);
    }

    @Test
    void unCsvInvalidoDejaLaEjecucionFallidaYNoAvanzaElControl() throws Exception {
        datosBase();
        Files.writeString(csv.resolve("ventas_online_20260312.csv"), "columna_inesperada\nx\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> etl.ejecutar(EtlService.Modo.AUTO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Falta la columna");

        assertThat(texto("SELECT estado FROM etl.ejecucion ORDER BY id DESC LIMIT 1")).isEqualTo("FALLIDA");
        assertThat(num("SELECT count(*) FROM etl.control WHERE carga_inicial_completa")).isZero();
        assertThat(num("SELECT count(*) FROM dwh.fact_ventas")).isZero();
    }

    // ----------------------------------------------------------------------------- utilidades

    private void script(PGSimpleDataSource ds, String recurso) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(recurso)) {
            assertThat(in).as("recurso " + recurso).isNotNull();
            sql(ds, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    protected static void sql(PGSimpleDataSource ds, String sentencias) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sentencias);
        }
    }

    private long num(String consulta) throws SQLException {
        try (Connection c = dwh.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(consulta)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private BigDecimal decimal(String consulta) throws SQLException {
        try (Connection c = dwh.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(consulta)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private String texto(String consulta) throws SQLException {
        try (Connection c = dwh.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(consulta)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
