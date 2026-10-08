package pe.ventasdw.etl.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * ETL de ventas: OLTP (PostgreSQL) y CSV del canal online hacia el DWH (esquema estrella).
 *
 * <p>Fases: (1) extracción a staging, (2) dimensiones (SCD1 y SCD2), (3) reglas de calidad sobre las líneas,
 * (4) carga de hechos con claves sustitutas, (5) control (watermarks, archivos procesados).
 * Las fases 2 a 5 corren en una sola transacción: o se aplica todo o nada, y el watermark solo avanza
 * si todo salió bien. La carga es idempotente: repetirla no duplica ni altera datos.
 *
 * <p>La mayor parte del trabajo es SQL por conjuntos dentro del DWH (staging más reglas), con JDBC
 * estándar. Solo una ejecución a la vez (bloqueo asesor de PostgreSQL entre instancias).
 */
public final class EtlService {

    public enum Modo { AUTO, TOTAL }

    private static final String JOB = "carga_ventas";
    private static final long LOCK_ID = 727_001L;
    private static final OffsetDateTime EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String HOY_LIMA = "(now() AT TIME ZONE 'America/Lima')::date";
    private static final String FECHA_LIMA = "(%s.updated_at AT TIME ZONE 'America/Lima')::date";
    private static final List<String> TABLAS_OLTP = List.of("ciudad", "categoria", "producto", "cliente", "empleado", "pedido");

    private final DataSource dwh;
    private final DataSource oltp;
    private final EtlConfig cfg;

    public EtlService(DataSource dwh, DataSource oltp, EtlConfig cfg) {
        this.dwh = dwh;
        this.oltp = oltp;
        this.cfg = cfg;
    }

    /** Estado acumulado de una ejecución. */
    private static final class Ctx {
        long id;
        String tipo;
        long extraidas;
        long cargadas;
        long excluidas;
        final Map<String, Long> detalle = new LinkedHashMap<>();
        final Map<String, Long> duraciones = new LinkedHashMap<>();
        final List<String> archivos = new ArrayList<>();
        final Map<String, Integer> filasPorArchivo = new LinkedHashMap<>();
        OffsetDateTime corte;
    }

    // ----------------------------------------------------------------------------------- API

    public synchronized EtlResultado ejecutar(Modo modo) {
        long inicio = System.nanoTime();
        try (Connection lock = dwh.getConnection()) {
            lock.setAutoCommit(true);
            if (!adquirirLock(lock)) {
                throw new IllegalStateException("Ya hay una ejecución del ETL en curso");
            }
            try {
                return correr(modo, inicio);
            } finally {
                liberarLock(lock);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo obtener el bloqueo del ETL", e);
        }
    }

    private EtlResultado correr(Modo modo, long inicioNanos) {
        Ctx x = new Ctx();
        x.tipo = modo == Modo.TOTAL ? "TOTAL" : (cargaInicialPendiente() ? "INICIAL" : "INCREMENTAL");
        x.id = abrirEjecucion(x.tipo);
        try {
            if (modo == Modo.TOTAL) {
                medir(x, "reinicio", this::reiniciar);
            }
            Map<String, OffsetDateTime> desde = ventanas(x.tipo);
            try (Connection d = dwh.getConnection(); Connection o = oltp.getConnection()) {
                o.setAutoCommit(false);          // cursor para fetchSize
                o.setReadOnly(true);
                x.corte = ahoraOltp(o);
                d.setAutoCommit(false);
                try {
                    long t = System.nanoTime();
                    extraer(x, o, d, desde);
                    d.commit();
                    x.duraciones.put("extraccion", (System.nanoTime() - t) / 1_000_000);
                } catch (SQLException | IOException | RuntimeException e) {
                    d.rollback();
                    throw e;
                } finally {
                    o.rollback();
                }
                d.setAutoCommit(false);
                try {
                    transformarYCargar(x, d);
                    d.commit();
                } catch (SQLException | RuntimeException e) {
                    d.rollback();
                    throw e;
                }
            }
            return cerrarOk(x, inicioNanos);
        } catch (SQLException | IOException | RuntimeException e) {
            cerrarFallida(x, e);
            throw new IllegalStateException("ETL fallido (ejecución " + x.id + "): " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------------------------- extracción

    private void extraer(Ctx x, Connection o, Connection d, Map<String, OffsetDateTime> desde)
            throws SQLException, IOException {
        try (Statement st = d.createStatement()) {
            st.execute("TRUNCATE stg.venta_linea, stg.producto, stg.cliente, stg.ciudad, stg.empleado");
        }
        OffsetDateTime hasta = x.corte;

        long n = copiar(o, d, desde.get("ciudad"), hasta,
                "SELECT ciudad_id, pais, departamento, provincia, ciudad FROM ciudad WHERE updated_at > ? AND updated_at <= ?",
                "INSERT INTO stg.ciudad (ciudad_id, pais, departamento, provincia, ciudad) VALUES (?, ?, ?, ?, ?)",
                (rs, ps) -> {
                    ps.setInt(1, rs.getInt(1));
                    ps.setString(2, rs.getString(2));
                    ps.setString(3, rs.getString(3));
                    ps.setString(4, rs.getString(4));
                    ps.setString(5, rs.getString(5));
                });
        x.detalle.put("extraidas.ciudad", n);

        n = copiar(o, d, desde.get("empleado"), hasta,
                "SELECT empleado_id, nombre, cargo FROM empleado WHERE updated_at > ? AND updated_at <= ?",
                "INSERT INTO stg.empleado (empleado_id, empleado, cargo) VALUES (?, ?, ?)",
                (rs, ps) -> {
                    ps.setInt(1, rs.getInt(1));
                    ps.setString(2, rs.getString(2));
                    ps.setString(3, rs.getString(3));
                });
        x.detalle.put("extraidas.empleado", n);

        // Producto: cambia si cambia el producto o su categoría
        n = copiar(o, d, desde.get("producto"), hasta,
                "SELECT p.producto_id, p.nombre, coalesce(k.nombre, 'Sin categoría'), p.precio_lista, "
                        + "greatest(p.updated_at, coalesce(k.updated_at, p.updated_at)) AS actualizado "
                        + "FROM producto p LEFT JOIN categoria k ON k.categoria_id = p.categoria_id "
                        + "WHERE greatest(p.updated_at, coalesce(k.updated_at, p.updated_at)) > ? "
                        + "AND greatest(p.updated_at, coalesce(k.updated_at, p.updated_at)) <= ?",
                "INSERT INTO stg.producto (producto_id, producto, categoria, precio_lista, updated_at) VALUES (?, ?, ?, ?, ?)",
                (rs, ps) -> {
                    ps.setInt(1, rs.getInt(1));
                    ps.setString(2, rs.getString(2));
                    ps.setString(3, rs.getString(3));
                    ps.setBigDecimal(4, rs.getBigDecimal(4));
                    ps.setObject(5, rs.getObject(5, OffsetDateTime.class));
                });
        x.detalle.put("extraidas.producto", n);

        n = copiar(o, d, desde.get("cliente"), hasta,
                "SELECT cliente_id, nombre, email, updated_at FROM cliente WHERE updated_at > ? AND updated_at <= ?",
                "INSERT INTO stg.cliente (cliente_id, cliente, email, updated_at) VALUES (?, ?, ?, ?)",
                (rs, ps) -> {
                    ps.setInt(1, rs.getInt(1));
                    ps.setString(2, rs.getString(2));
                    ps.setString(3, rs.getString(3));
                    ps.setObject(4, rs.getObject(4, OffsetDateTime.class));
                });
        x.detalle.put("extraidas.cliente", n);

        // Líneas de pedidos del OLTP (canal tienda). Un pedido cambia si cambia su cabecera o sus líneas (trigger).
        final long ejec = x.id;
        n = copiar(o, d, desde.get("pedido"), hasta,
                "SELECT p.pedido_id, dp.linea, p.fecha_pedido, p.estado, p.updated_at, p.cliente_id, c.ciudad_id, "
                        + "p.empleado_id, dp.producto_id, dp.cantidad, dp.precio_unitario, dp.descuento "
                        + "FROM pedido p JOIN detalle_pedido dp ON dp.pedido_id = p.pedido_id "
                        + "LEFT JOIN cliente c ON c.cliente_id = p.cliente_id "
                        + "WHERE p.updated_at > ? AND p.updated_at <= ?",
                "INSERT INTO stg.venta_linea (ejecucion_id, fuente, canal_codigo, pedido_id, linea, fecha_pedido, estado_origen, "
                        + "updated_at, cliente_id, ciudad_id, empleado_id, producto_id, cantidad, precio_unitario, descuento, moneda) "
                        + "VALUES (?, 'OLTP', 'TIENDA', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PEN')",
                (rs, ps) -> {
                    ps.setLong(1, ejec);
                    ps.setLong(2, rs.getLong(1));
                    ps.setInt(3, rs.getInt(2));
                    ps.setObject(4, rs.getObject(3, LocalDate.class));
                    ps.setString(5, rs.getString(4));
                    ps.setObject(6, rs.getObject(5, OffsetDateTime.class));
                    setIntONull(ps, 7, rs, 6);
                    setIntONull(ps, 8, rs, 7);
                    setIntONull(ps, 9, rs, 8);
                    setIntONull(ps, 10, rs, 9);
                    setIntONull(ps, 11, rs, 10);
                    ps.setBigDecimal(12, rs.getBigDecimal(11));
                    ps.setBigDecimal(13, rs.getBigDecimal(12));
                });
        x.detalle.put("extraidas.lineas_oltp", n);
        x.extraidas += n;

        // CSV del canal online: solo los archivos que aún no se procesaron
        long lineasCsv = extraerCsv(x, d);
        x.detalle.put("extraidas.lineas_csv", lineasCsv);
        x.extraidas += lineasCsv;
    }

    @FunctionalInterface
    private interface Fila {
        void aplicar(ResultSet origen, PreparedStatement destino) throws SQLException;
    }

    private long copiar(Connection o, Connection d, OffsetDateTime desde, OffsetDateTime hasta,
                        String selectSql, String insertSql, Fila fila) throws SQLException {
        long n = 0;
        try (PreparedStatement sel = o.prepareStatement(selectSql);
             PreparedStatement ins = d.prepareStatement(insertSql)) {
            sel.setFetchSize(5_000);
            sel.setObject(1, desde);
            sel.setObject(2, hasta);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    fila.aplicar(rs, ins);
                    ins.addBatch();
                    if (++n % cfg.tamanoLote() == 0) {
                        ins.executeBatch();
                    }
                }
            }
            ins.executeBatch();
        }
        return n;
    }

    private static void setIntONull(PreparedStatement ps, int destino, ResultSet rs, int origen) throws SQLException {
        int v = rs.getInt(origen);
        if (rs.wasNull()) {
            ps.setNull(destino, Types.INTEGER);
        } else {
            ps.setInt(destino, v);
        }
    }

    private long extraerCsv(Ctx x, Connection d) throws SQLException, IOException {
        if (cfg.directorioCsv() == null || !Files.isDirectory(cfg.directorioCsv())) {
            return 0;
        }
        Set<String> procesados = new java.util.HashSet<>();
        try (Statement st = d.createStatement(); ResultSet rs = st.executeQuery("SELECT nombre FROM etl.archivo_cargado")) {
            while (rs.next()) {
                procesados.add(rs.getString(1));
            }
        }
        List<Path> nuevos;
        try (Stream<Path> s = Files.list(cfg.directorioCsv())) {
            nuevos = s.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .filter(p -> !procesados.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
        long total = 0;
        try (PreparedStatement ins = d.prepareStatement(
                "INSERT INTO stg.venta_linea (ejecucion_id, fuente, canal_codigo, pedido_id, linea, fecha_pedido, estado_origen, "
                        + "updated_at, cliente_id, ciudad_id, producto_id, cantidad, precio_unitario, descuento, moneda, cliente, email, categoria) "
                        + "VALUES (?, 'CSV_ONLINE', 'ONLINE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Path archivo : nuevos) {
                int[] filas = {0};
                int[] nLote = {0};
                java.util.concurrent.atomic.AtomicReference<Map<String, Integer>> cab = new java.util.concurrent.atomic.AtomicReference<>();
                try {
                    CsvLector.leer(archivo, r -> {
                        try {
                            if (cab.get() == null) {
                                cab.set(indiceCabecera(r, archivo));
                                return;
                            }
                            Map<String, Integer> c = cab.get();
                            ins.setLong(1, x.id);
                            ins.setLong(2, Long.parseLong(celda(r, c, "pedido_online_id")));
                            ins.setInt(3, Integer.parseInt(celda(r, c, "linea")));
                            ins.setObject(4, fecha(celda(r, c, "fecha_pedido")));
                            ins.setString(5, texto(celda(r, c, "estado")));
                            ins.setObject(6, marca(celda(r, c, "updated_at")));
                            setEntero(ins, 7, celda(r, c, "cliente_id"));
                            setEntero(ins, 8, celda(r, c, "ciudad_id"));
                            setEntero(ins, 9, celda(r, c, "producto_id"));
                            setEntero(ins, 10, celda(r, c, "cantidad"));
                            String precio = texto(celda(r, c, "precio_unitario"));
                            ins.setBigDecimal(11, precio == null ? null : new BigDecimal(precio));
                            String desc = texto(celda(r, c, "descuento"));
                            ins.setBigDecimal(12, desc == null ? null : new BigDecimal(desc));
                            ins.setString(13, texto(celda(r, c, "moneda")));
                            ins.setString(14, texto(celda(r, c, "razon_social")));
                            String email = texto(celda(r, c, "email"));
                            ins.setString(15, email == null ? null : email.toLowerCase(java.util.Locale.ROOT));
                            ins.setString(16, texto(celda(r, c, "categoria")));
                            ins.addBatch();
                            filas[0]++;
                            if (++nLote[0] % cfg.tamanoLote() == 0) {
                                ins.executeBatch();
                            }
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        } catch (RuntimeException e) {
                            throw new IllegalStateException("Fila inválida en " + archivo.getFileName() + ": " + e.getMessage(), e);
                        }
                    });
                } catch (IllegalStateException e) {
                    if (e.getCause() instanceof SQLException se) {
                        throw se;
                    }
                    throw e;
                }
                ins.executeBatch();
                x.archivos.add(archivo.getFileName().toString());
                x.filasPorArchivo.put(archivo.getFileName().toString(), filas[0]);
                total += filas[0];
            }
        }
        return total;
    }

    private static Map<String, Integer> indiceCabecera(String[] cabecera, Path archivo) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < cabecera.length; i++) {
            m.put(cabecera[i].trim().toLowerCase(java.util.Locale.ROOT), i);
        }
        for (String req : List.of("pedido_online_id", "linea", "fecha_pedido", "estado", "cliente_id", "ciudad_id",
                "producto_id", "cantidad", "precio_unitario", "descuento", "moneda", "updated_at")) {
            if (!m.containsKey(req)) {
                throw new IllegalStateException("Falta la columna " + req + " en " + archivo.getFileName());
            }
        }
        return m;
    }

    private static String celda(String[] r, Map<String, Integer> c, String nombre) {
        Integer i = c.get(nombre);
        return i == null || i >= r.length ? null : r[i];
    }

    private static String texto(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static LocalDate fecha(String s) {
        String t = texto(s);
        return t == null ? null : LocalDate.parse(t);
    }

    private static OffsetDateTime marca(String s) {
        String t = texto(s);
        return t == null ? null : OffsetDateTime.parse(t);
    }

    private static void setEntero(PreparedStatement ps, int idx, String s) throws SQLException {
        String t = texto(s);
        if (t == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, Integer.parseInt(t));
        }
    }

    // ----------------------------------------------------------------------------------- transformación y carga

    private void transformarYCargar(Ctx x, Connection d) throws SQLException {
        medirSql(x, "dimensiones", () -> cargarDimensiones(x, d));
        medirSql(x, "calidad", () -> aplicarReglas(x, d));
        medirSql(x, "hechos", () -> cargarHechos(x, d));
        medirSql(x, "control", () -> actualizarControl(x, d));
    }

    private void cargarDimensiones(Ctx x, Connection d) throws SQLException {
        x.detalle.put("dim_geografia.upserts", ejecutar(d,
                "INSERT INTO dwh.dim_geografia (ciudad_id, pais, departamento, provincia, ciudad) "
                        + "SELECT ciudad_id, pais, departamento, provincia, ciudad FROM stg.ciudad "
                        + "ON CONFLICT (ciudad_id) DO UPDATE SET pais = EXCLUDED.pais, departamento = EXCLUDED.departamento, "
                        + "provincia = EXCLUDED.provincia, ciudad = EXCLUDED.ciudad "
                        + "WHERE (dwh.dim_geografia.pais, dwh.dim_geografia.departamento, dwh.dim_geografia.provincia, dwh.dim_geografia.ciudad) "
                        + "IS DISTINCT FROM (EXCLUDED.pais, EXCLUDED.departamento, EXCLUDED.provincia, EXCLUDED.ciudad)"));
        x.detalle.put("dim_empleado.upserts", ejecutar(d,
                "INSERT INTO dwh.dim_empleado (empleado_id, empleado, cargo) SELECT empleado_id, empleado, cargo FROM stg.empleado "
                        + "ON CONFLICT (empleado_id) DO UPDATE SET empleado = EXCLUDED.empleado, cargo = EXCLUDED.cargo "
                        + "WHERE (dwh.dim_empleado.empleado, dwh.dim_empleado.cargo) IS DISTINCT FROM (EXCLUDED.empleado, EXCLUDED.cargo)"));

        String fProd = FECHA_LIMA.formatted("s");
        // SCD2 producto: (a) mismo día: se corrige la versión actual; (b) se cierra la anterior; (c) precio_lista es SCD1; (d) versión nueva
        ejecutar(d, "UPDATE dwh.dim_producto d SET producto = s.producto, categoria = s.categoria, hash_atributos = s.hash_atributos "
                + "FROM stg.producto s WHERE d.producto_id = s.producto_id AND d.es_actual AND d.hash_atributos <> s.hash_atributos "
                + "AND d.vigente_desde >= " + fProd);
        x.detalle.put("dim_producto.versiones_cerradas", ejecutar(d,
                "UPDATE dwh.dim_producto d SET vigente_hasta = " + fProd + ", es_actual = false "
                        + "FROM stg.producto s WHERE d.producto_id = s.producto_id AND d.es_actual AND d.hash_atributos <> s.hash_atributos"));
        ejecutar(d, "UPDATE dwh.dim_producto d SET precio_lista = s.precio_lista FROM stg.producto s "
                + "WHERE d.producto_id = s.producto_id AND d.es_actual AND d.precio_lista IS DISTINCT FROM s.precio_lista");
        x.detalle.put("dim_producto.versiones_nuevas", ejecutar(d,
                "INSERT INTO dwh.dim_producto (producto_id, producto, categoria, precio_lista, hash_atributos, vigente_desde) "
                        + "SELECT s.producto_id, s.producto, s.categoria, s.precio_lista, s.hash_atributos, "
                        + "CASE WHEN EXISTS (SELECT 1 FROM dwh.dim_producto x WHERE x.producto_id = s.producto_id) "
                        + "THEN " + fProd + " ELSE DATE '1900-01-01' END "
                        + "FROM stg.producto s WHERE NOT EXISTS "
                        + "(SELECT 1 FROM dwh.dim_producto d WHERE d.producto_id = s.producto_id AND d.es_actual)"));

        String fCli = FECHA_LIMA.formatted("s");
        ejecutar(d, "UPDATE dwh.dim_cliente d SET cliente = s.cliente, email = s.email, hash_atributos = s.hash_atributos "
                + "FROM stg.cliente s WHERE d.cliente_id = s.cliente_id AND d.es_actual AND d.hash_atributos <> s.hash_atributos "
                + "AND d.vigente_desde >= " + fCli);
        x.detalle.put("dim_cliente.versiones_cerradas", ejecutar(d,
                "UPDATE dwh.dim_cliente d SET vigente_hasta = " + fCli + ", es_actual = false "
                        + "FROM stg.cliente s WHERE d.cliente_id = s.cliente_id AND d.es_actual AND d.hash_atributos <> s.hash_atributos"));
        x.detalle.put("dim_cliente.versiones_nuevas", ejecutar(d,
                "INSERT INTO dwh.dim_cliente (cliente_id, cliente, email, hash_atributos, vigente_desde) "
                        + "SELECT s.cliente_id, s.cliente, s.email, s.hash_atributos, "
                        + "CASE WHEN EXISTS (SELECT 1 FROM dwh.dim_cliente x WHERE x.cliente_id = s.cliente_id) "
                        + "THEN " + fCli + " ELSE DATE '1900-01-01' END "
                        + "FROM stg.cliente s WHERE NOT EXISTS "
                        + "(SELECT 1 FROM dwh.dim_cliente d WHERE d.cliente_id = s.cliente_id AND d.es_actual)"));
    }

    private void aplicarReglas(Ctx x, Connection d) throws SQLException {
        long e = x.id;
        // Normalización (codificación): estados con variantes de escritura
        ejecutar(d, "UPDATE stg.venta_linea SET estado = CASE upper(trim(estado_origen)) "
                + "WHEN 'ENTREGADO' THEN 'ENTREGADO' WHEN 'ENT' THEN 'ENTREGADO' WHEN 'E' THEN 'ENTREGADO' "
                + "WHEN 'CANCELADO' THEN 'CANCELADO' WHEN 'DEVUELTO' THEN 'DEVUELTO' "
                + "WHEN 'PENDIENTE' THEN 'PENDIENTE' WHEN 'ENVIADO' THEN 'ENVIADO' ELSE 'DESCONOCIDO' END "
                + "WHERE ejecucion_id = ?", e);

        // 1. Duplicados: se conserva el registro con updated_at más reciente
        ejecutar(d, "WITH dup AS (SELECT stg_id, row_number() OVER (PARTITION BY canal_codigo, pedido_id, linea "
                + "ORDER BY updated_at DESC NULLS LAST, stg_id DESC) AS rn FROM stg.venta_linea "
                + "WHERE ejecucion_id = ? AND pedido_id IS NOT NULL AND linea IS NOT NULL), "
                + "r AS (UPDATE stg.venta_linea s SET estado_validacion = 'RECHAZADO' FROM dup "
                + "WHERE s.stg_id = dup.stg_id AND dup.rn > 1 RETURNING s.stg_id, s.fuente, s.pedido_id, s.linea) "
                + excepcionDesde("r", "LINEA_DUPLICADA", "ADVERTENCIA"), e, e);

        // 2. Rechazos (ERROR)
        rechazar(d, e, "CLAVE_NULA", "pedido_id IS NULL OR linea IS NULL OR fecha_pedido IS NULL");
        rechazar(d, e, "FECHA_FUTURA", "fecha_pedido > " + HOY_LIMA);
        rechazar(d, e, "FECHA_FUERA_DE_RANGO", "fecha_pedido < (SELECT min(fecha) FROM dwh.dim_fecha)");
        rechazar(d, e, "CANTIDAD_NO_POSITIVA", "cantidad IS NULL OR cantidad <= 0");

        // 3. Precio: referencia de lista vigente, imputación de faltantes y conversión a soles
        ejecutar(d, "UPDATE stg.venta_linea s SET precio_lista = p.precio_lista FROM dwh.dim_producto p "
                + "WHERE s.ejecucion_id = ? AND s.estado_validacion = 'PENDIENTE' AND p.producto_id = s.producto_id AND p.es_actual", e);
        corregir(d, e, "PRECIO_NULO", "precio_unitario = precio_lista, moneda = 'PEN'",
                "precio_unitario IS NULL AND precio_lista IS NOT NULL");
        rechazar(d, e, "PRECIO_NO_IMPUTABLE", "precio_unitario IS NULL");
        ejecutar(d, "UPDATE stg.venta_linea SET precio_unitario = precio_unitario * ?, moneda = 'PEN' "
                + "WHERE ejecucion_id = ? AND estado_validacion = 'PENDIENTE' AND upper(moneda) = 'USD'", cfg.tipoCambioUsd(), e);
        advertir(d, e, "PRECIO_ATIPICO",
                "precio_lista > 0 AND precio_unitario / precio_lista > 5");
        advertir(d, e, "ESTADO_DESCONOCIDO", "estado = 'DESCONOCIDO'");

        // 4. Referencias que no existen: se asigna el miembro Desconocido (-1)
        corregir(d, e, "PRODUCTO_NULO", "corregido = true", "producto_id IS NULL");
        corregir(d, e, "PRODUCTO_INEXISTENTE", "corregido = true", "producto_id IS NOT NULL AND NOT EXISTS "
                + "(SELECT 1 FROM dwh.dim_producto p WHERE p.producto_id = s.producto_id)");
        corregir(d, e, "CLIENTE_NULO", "corregido = true", "cliente_id IS NULL");
        corregir(d, e, "CLIENTE_INEXISTENTE", "corregido = true", "cliente_id IS NOT NULL AND NOT EXISTS "
                + "(SELECT 1 FROM dwh.dim_cliente c WHERE c.cliente_id = s.cliente_id)");
        corregir(d, e, "EMPLEADO_INEXISTENTE", "corregido = true", "empleado_id IS NOT NULL AND NOT EXISTS "
                + "(SELECT 1 FROM dwh.dim_empleado m WHERE m.empleado_id = s.empleado_id)");

        // 5. Regla de negocio (no es un error): los pedidos cancelados o devueltos no cuentan como venta
        x.excluidas = ejecutar(d, "UPDATE stg.venta_linea SET estado_validacion = 'EXCLUIDO' "
                + "WHERE ejecucion_id = ? AND estado_validacion = 'PENDIENTE' AND estado IN ('CANCELADO', 'DEVUELTO')", e);

        ejecutar(d, "UPDATE stg.venta_linea SET estado_validacion = CASE WHEN corregido THEN 'CORREGIDO' ELSE 'VALIDO' END "
                + "WHERE ejecucion_id = ? AND estado_validacion = 'PENDIENTE'", e);
    }

    private void rechazar(Connection d, long e, String regla, String condicion) throws SQLException {
        ejecutar(d, "WITH r AS (UPDATE stg.venta_linea s SET estado_validacion = 'RECHAZADO' "
                + "WHERE s.ejecucion_id = ? AND s.estado_validacion = 'PENDIENTE' AND (" + condicion + ") "
                + "RETURNING s.stg_id, s.fuente, s.pedido_id, s.linea) " + excepcionDesde("r", regla, "ERROR"), e, e);
    }

    private void corregir(Connection d, long e, String regla, String asignacion, String condicion) throws SQLException {
        String set = asignacion.contains("corregido") ? asignacion : asignacion + ", corregido = true";
        ejecutar(d, "WITH r AS (UPDATE stg.venta_linea s SET " + set + " "
                + "WHERE s.ejecucion_id = ? AND s.estado_validacion = 'PENDIENTE' AND (" + condicion + ") "
                + "RETURNING s.stg_id, s.fuente, s.pedido_id, s.linea) " + excepcionDesde("r", regla, "CORREGIDO"), e, e);
    }

    private void advertir(Connection d, long e, String regla, String condicion) throws SQLException {
        ejecutar(d, "WITH r AS (SELECT s.stg_id, s.fuente, s.pedido_id, s.linea FROM stg.venta_linea s "
                + "WHERE s.ejecucion_id = ? AND s.estado_validacion = 'PENDIENTE' AND (" + condicion + ")) "
                + excepcionDesde("r", regla, "ADVERTENCIA"), e, e);
    }

    /** INSERT en etl.excepcion a partir de un CTE r; no repite una excepción ya registrada para la misma clave y regla. */
    private static String excepcionDesde(String cte, String regla, String severidad) {
        return "INSERT INTO etl.excepcion (ejecucion_id, tabla, clave_natural, regla, severidad, detalle) "
                + "SELECT ?, 'venta_linea', " + cte + ".fuente || ':' || " + cte + ".pedido_id || ':' || coalesce(" + cte + ".linea::text, ''), "
                + "'" + regla + "', '" + severidad + "', jsonb_build_object('stg_id', " + cte + ".stg_id) FROM " + cte + " "
                + "WHERE NOT EXISTS (SELECT 1 FROM etl.excepcion x WHERE x.regla = '" + regla + "' AND x.clave_natural = "
                + cte + ".fuente || ':' || " + cte + ".pedido_id || ':' || coalesce(" + cte + ".linea::text, ''))";
    }

    private void cargarHechos(Ctx x, Connection d) throws SQLException {
        long e = x.id;
        // Pedidos que pasaron a cancelados o devueltos: salen del DWH
        x.detalle.put("hechos.eliminados", ejecutar(d,
                "DELETE FROM dwh.fact_ventas f USING stg.venta_linea s, dwh.dim_canal c "
                        + "WHERE s.ejecucion_id = ? AND s.estado_validacion = 'EXCLUIDO' AND c.codigo = s.canal_codigo "
                        + "AND f.canal_key = c.canal_key AND f.pedido_id = s.pedido_id AND f.linea = s.linea "
                        + "AND f.fecha_key = to_char(s.fecha_pedido, 'YYYYMMDD')::int", e));

        // Hechos: cada dimensión se resuelve a su clave sustituta (producto y cliente según la versión vigente en la fecha de la venta)
        long n = ejecutar(d,
                "INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key, "
                        + "pedido_id, linea, cantidad, precio_unitario, descuento, ejecucion_id) "
                        + "SELECT to_char(s.fecha_pedido, 'YYYYMMDD')::int, c.canal_key, "
                        + "coalesce(p.producto_key, -1), coalesce(cl.cliente_key, -1), coalesce(g.geografia_key, -1), coalesce(em.empleado_key, -1), "
                        + "s.pedido_id, s.linea, s.cantidad, round(s.precio_unitario, 2), "
                        + "least(greatest(coalesce(s.descuento, 0), 0), 1), ? "
                        + "FROM stg.venta_linea s "
                        + "JOIN dwh.dim_canal c ON c.codigo = s.canal_codigo "
                        + "LEFT JOIN dwh.dim_producto p ON p.producto_id = s.producto_id AND s.fecha_pedido >= p.vigente_desde AND s.fecha_pedido < p.vigente_hasta "
                        + "LEFT JOIN dwh.dim_cliente cl ON cl.cliente_id = s.cliente_id AND s.fecha_pedido >= cl.vigente_desde AND s.fecha_pedido < cl.vigente_hasta "
                        + "LEFT JOIN dwh.dim_geografia g ON g.ciudad_id = s.ciudad_id "
                        + "LEFT JOIN dwh.dim_empleado em ON em.empleado_id = s.empleado_id "
                        + "WHERE s.ejecucion_id = ? AND s.estado_validacion IN ('VALIDO', 'CORREGIDO') "
                        + "ON CONFLICT (fecha_key, canal_key, pedido_id, linea) DO UPDATE SET "
                        + "producto_key = EXCLUDED.producto_key, cliente_key = EXCLUDED.cliente_key, geografia_key = EXCLUDED.geografia_key, "
                        + "empleado_key = EXCLUDED.empleado_key, cantidad = EXCLUDED.cantidad, precio_unitario = EXCLUDED.precio_unitario, "
                        + "descuento = EXCLUDED.descuento, ejecucion_id = EXCLUDED.ejecucion_id, cargado_en = now() "
                        + "WHERE (dwh.fact_ventas.producto_key, dwh.fact_ventas.cliente_key, dwh.fact_ventas.geografia_key, "
                        + "dwh.fact_ventas.empleado_key, dwh.fact_ventas.cantidad, dwh.fact_ventas.precio_unitario, dwh.fact_ventas.descuento) "
                        + "IS DISTINCT FROM (EXCLUDED.producto_key, EXCLUDED.cliente_key, EXCLUDED.geografia_key, "
                        + "EXCLUDED.empleado_key, EXCLUDED.cantidad, EXCLUDED.precio_unitario, EXCLUDED.descuento)", e, e);
        x.cargadas = n;
        x.detalle.put("hechos.insertados_o_actualizados", n);
    }

    private void actualizarControl(Ctx x, Connection d) throws SQLException {
        try (PreparedStatement ps = d.prepareStatement(
                "UPDATE etl.control SET watermark = ?, carga_inicial_completa = true, ultima_ejecucion = now() "
                        + "WHERE fuente = 'OLTP' AND tabla = ANY (?)")) {
            ps.setObject(1, x.corte);
            ps.setArray(2, d.createArrayOf("text", TABLAS_OLTP.toArray()));
            ps.executeUpdate();
        }
        ejecutar(d, "UPDATE etl.control SET ultima_ejecucion = now() WHERE fuente = 'CSV_ONLINE'");
        try (PreparedStatement ps = d.prepareStatement(
                "INSERT INTO etl.archivo_cargado (nombre, filas, ejecucion_id) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Integer> a : x.filasPorArchivo.entrySet()) {
                ps.setString(1, a.getKey());
                ps.setInt(2, a.getValue());
                ps.setLong(3, x.id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ----------------------------------------------------------------------------------- control de ejecución

    private boolean cargaInicialPendiente() {
        try (Connection c = dwh.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT coalesce(bool_and(carga_inicial_completa), false) FROM etl.control WHERE fuente = 'OLTP'")) {
            rs.next();
            return !rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer etl.control", e);
        }
    }

    /** Límite inferior de extracción por tabla: época en cargas iniciales; watermark menos el solape en las incrementales. */
    private Map<String, OffsetDateTime> ventanas(String tipo) {
        Map<String, OffsetDateTime> m = new HashMap<>();
        for (String t : TABLAS_OLTP) {
            m.put(t, EPOCH);
        }
        if (!"INCREMENTAL".equals(tipo)) {
            return m;
        }
        try (Connection c = dwh.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT tabla, watermark FROM etl.control WHERE fuente = 'OLTP'")) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getObject(2, OffsetDateTime.class).minusMinutes(cfg.solapeMinutos()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer los watermarks", e);
        }
        return m;
    }

    private static OffsetDateTime ahoraOltp(Connection o) throws SQLException {
        try (Statement st = o.createStatement(); ResultSet rs = st.executeQuery("SELECT now()")) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

    private void reiniciar() throws SQLException {
        try (Connection c = dwh.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("TRUNCATE dwh.fact_ventas");
                st.execute("DELETE FROM dwh.dim_producto WHERE producto_key <> -1");
                st.execute("DELETE FROM dwh.dim_cliente WHERE cliente_key <> -1");
                st.execute("DELETE FROM dwh.dim_geografia WHERE geografia_key <> -1");
                st.execute("DELETE FROM dwh.dim_empleado WHERE empleado_key <> -1");
                st.execute("DELETE FROM etl.archivo_cargado");
                st.execute("UPDATE etl.control SET watermark = TIMESTAMPTZ '1970-01-01 00:00:00+00', carga_inicial_completa = false");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private long abrirEjecucion(String tipo) {
        try (Connection c = dwh.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO etl.ejecucion (job, tipo_carga) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, JOB);
            ps.setString(2, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar la ejecución", e);
        }
    }

    private EtlResultado cerrarOk(Ctx x, long inicioNanos) throws SQLException {
        Map<String, Long> excepciones = new LinkedHashMap<>();
        long rechazadas;
        try (Connection c = dwh.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT regla, severidad, count(*) FROM etl.excepcion WHERE ejecucion_id = ? GROUP BY regla, severidad ORDER BY 1")) {
                ps.setLong(1, x.id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        excepciones.put(rs.getString(1) + " (" + rs.getString(2) + ")", rs.getLong(3));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM stg.venta_linea WHERE ejecucion_id = ? AND estado_validacion = 'RECHAZADO'")) {
                ps.setLong(1, x.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    rechazadas = rs.getLong(1);
                }
            }
            long totalMs = (System.nanoTime() - inicioNanos) / 1_000_000;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE etl.ejecucion SET estado = 'EXITOSA', fin = now(), filas_extraidas = ?, filas_cargadas = ?, "
                            + "filas_rechazadas = ?, mensaje = ? WHERE id = ?")) {
                ps.setLong(1, x.extraidas);
                ps.setLong(2, x.cargadas);
                ps.setLong(3, rechazadas);
                ps.setString(4, "excluidas=" + x.excluidas + ", archivos_csv=" + x.archivos.size());
                ps.setLong(5, x.id);
                ps.executeUpdate();
            }
            // Los agregados materializados se refrescan fuera de la transacción de carga; si fallan, el ETL no se revierte
            try (Statement st = c.createStatement()) {
                long t = System.nanoTime();
                st.execute("SELECT dwh.refrescar_agregados()");
                x.duraciones.put("agregados", (System.nanoTime() - t) / 1_000_000);
            } catch (SQLException e) {
                x.detalle.put("agregados.error", 1L);
            }
            return new EtlResultado(x.id, x.tipo, "EXITOSA", x.extraidas, x.cargadas, rechazadas, x.excluidas,
                    excepciones, x.detalle, x.duraciones, totalMs, List.copyOf(x.archivos));
        }
    }

    private void cerrarFallida(Ctx x, Exception causa) {
        try (Connection c = dwh.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE etl.ejecucion SET estado = 'FALLIDA', fin = now(), mensaje = ? WHERE id = ?")) {
            String msg = String.valueOf(causa.getMessage());
            ps.setString(1, msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            ps.setLong(2, x.id);
            ps.executeUpdate();
        } catch (SQLException ignorada) {
            // no se puede hacer más: la excepción original se propaga
        }
    }

    // ----------------------------------------------------------------------------------- utilidades

    private boolean adquirirLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private void liberarLock(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_ID);
            ps.execute();
        } catch (SQLException ignorada) {
            // al cerrar la conexión PostgreSQL libera el bloqueo de sesión
        }
    }

    @FunctionalInterface
    private interface TareaSql {
        void correr() throws SQLException;
    }

    @FunctionalInterface
    private interface TareaSinChequeo {
        void correr() throws SQLException;
    }

    private void medir(Ctx x, String fase, TareaSinChequeo t) throws SQLException {
        long ini = System.nanoTime();
        t.correr();
        x.duraciones.put(fase, (System.nanoTime() - ini) / 1_000_000);
    }

    private void medirSql(Ctx x, String fase, TareaSql t) throws SQLException {
        long ini = System.nanoTime();
        t.correr();
        x.duraciones.put(fase, (System.nanoTime() - ini) / 1_000_000);
    }

    /** Ejecuta una sentencia con parámetros posicionales (Long, BigDecimal...) y devuelve las filas afectadas. */
    private static long ejecutar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
