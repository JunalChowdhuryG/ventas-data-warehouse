package pe.ventasdw.generator.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.sql.DataSource;

/**
 * Generador de datos de ventas para el OLTP y el canal online  
 */
public final class GeneradorVentas {

    private static final ZoneId ZONA = ZoneId.of("America/Lima");

    public record Config(
            long semilla,
            int anosHistorico,
            int pedidosPorDia,
            int clientes,
            double tasaErrores,
            double proporcionOnline,
            Path directorioCsv) {
    }

    public record Resumen(
            int dias,
            int pedidos,
            int lineas,
            int pedidosOnline,
            int lineasOnline,
            int archivosCsv,
            int erroresInyectados,
            Map<String, Integer> erroresPorTipo,
            int estadosActualizados,
            int cambiosEnMaestros) {
    }

    private record Prod(int id, String nombre, String categoria, int categoriaId, BigDecimal precio) {
    }

    private record Cli(int id, String nombre, String email, int ciudadId) {
    }

    private record Catalogo(List<Prod> productos, double[] acumProductos, List<Cli> clientes,
                            double[] acumClientes, int[] empleados) {
    }

    /** Un error inyectado pendiente de registrar en qa_error_inyectado  */
    private record ErrorQa(String fuente, TipoError tipo, long pedidoId, Integer linea, LocalDate fecha, String detalle) {
    }

    private static final class Acum {
        int dias;
        int pedidos;
        int lineas;
        int pedidosOnline;
        int lineasOnline;
        int archivosCsv;
        int estados;
        int maestros;
        final Map<TipoError, Integer> errores = new EnumMap<>(TipoError.class);

        Resumen resumen() {
            Map<String, Integer> porTipo = new LinkedHashMap<>();
            int total = 0;
            for (Map.Entry<TipoError, Integer> e : errores.entrySet()) {
                porTipo.put(e.getKey().name(), e.getValue());
                total += e.getValue();
            }
            return new Resumen(dias, pedidos, lineas, pedidosOnline, lineasOnline, archivosCsv, total, porTipo, estados, maestros);
        }
    }

    private final DataSource dataSource;
    private final Config cfg;

    public GeneradorVentas(DataSource dataSource, Config cfg) {
        this.dataSource = dataSource;
        this.cfg = cfg;
    }

    //  API publica

    public synchronized boolean oltpVacio() {
        return escalar("SELECT count(*) FROM pedido") == 0;
    }
 
    public synchronized Resumen cargarHistorico(LocalDate hasta) {
        asegurarCatalogos();
        Catalogo cat = leerCatalogo();
        Acum acum = new Acum();
        LocalDate desde = hasta.minusYears(cfg.anosHistorico()).plusDays(1);
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            generarDiaInterno(cat, d, acum, true);
        }
        return acum.resumen();
    }
 
    public synchronized Resumen generarDia(LocalDate fecha) {
        asegurarCatalogos();
        Acum acum = new Acum();
        Random r = new Random(cfg.semilla() * 31 + fecha.toEpochDay() * 0x9E3779B97F4A7C15L);
        aplicarCambiosEnMaestros(r, acum);
        avanzarEstados(fecha, acum);
        generarDiaInterno(leerCatalogo(), fecha, acum, false);
        return acum.resumen();
    }

    // catalogos

    private void asegurarCatalogos() {
        if (escalar("SELECT count(*) FROM categoria") > 0) {
            return;
        }
        Random r = new Random(cfg.semilla());
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Ciudades
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ciudad (ciudad, provincia, departamento) VALUES (?, ?, ?)")) {
                    for (Catalogos.CiudadSemilla ci : Catalogos.CIUDADES) {
                        ps.setString(1, ci.ciudad());
                        ps.setString(2, ci.provincia());
                        ps.setString(3, ci.departamento());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                List<Integer> ciudadIds = ids(c, "SELECT ciudad_id FROM ciudad ORDER BY ciudad_id");
                int[] pesosCiudad = new int[Catalogos.CIUDADES.size()];
                for (int i = 0; i < pesosCiudad.length; i++) {
                    pesosCiudad[i] = Catalogos.CIUDADES.get(i).peso();
                }

                // Categorias y productos
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO categoria (nombre) VALUES (?)")) {
                    for (String nombre : Catalogos.categorias()) {
                        ps.setString(1, nombre);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                Map<String, Integer> categoriaId = new LinkedHashMap<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT categoria_id, nombre FROM categoria")) {
                    while (rs.next()) {
                        categoriaId.put(rs.getString(2), rs.getInt(1));
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO producto (nombre, categoria_id, precio_lista) VALUES (?, ?, ?)")) {
                    for (Catalogos.ProductoSemilla p : Catalogos.productos()) {
                        ps.setString(1, p.nombre());
                        ps.setInt(2, categoriaId.get(p.categoria()));
                        ps.setBigDecimal(3, BigDecimal.valueOf(p.precio()).setScale(2, RoundingMode.HALF_UP));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Empleados
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO empleado (nombre, cargo) VALUES (?, ?)")) {
                    for (int i = 0; i < 40; i++) {
                        ps.setString(1, nombreCompleto(r));
                        ps.setString(2, Catalogos.CARGOS[r.nextInt(Catalogos.CARGOS.length)]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Clientes  
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cliente (nombre, email, ciudad_id) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < cfg.clientes(); i++) {
                        String nombre = nombreCompleto(r);
                        ps.setString(1, nombre);
                        ps.setString(2, correo(r, nombre, i));
                        ps.setInt(3, ciudadIds.get(ModeloVentas.elegir(r, indices(pesosCiudad.length), pesosCiudad)));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron crear los catalogos", e);
        }
    }

    private Catalogo leerCatalogo() {
        List<Prod> productos = new ArrayList<>();
        List<Cli> clientes = new ArrayList<>();
        List<Integer> empleados = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT p.producto_id, p.nombre, k.nombre, k.categoria_id, p.precio_lista "
                            + "FROM producto p JOIN categoria k USING (categoria_id) "
                            + "WHERE NOT p.descontinuado AND p.precio_lista IS NOT NULL ORDER BY p.producto_id")) {
                while (rs.next()) {
                    productos.add(new Prod(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT cliente_id, nombre, email, ciudad_id FROM cliente ORDER BY cliente_id")) {
                while (rs.next()) {
                    clientes.add(new Cli(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT empleado_id FROM empleado ORDER BY empleado_id")) {
                while (rs.next()) {
                    empleados.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el catalogo", e);
        }
        if (productos.isEmpty() || clientes.isEmpty() || empleados.isEmpty()) {
            throw new IllegalStateException("Catalogos vacios: no se puede generar ventas");
        } 
        Random r = new Random(cfg.semilla() + 1);
        double[] acumP = acumuladoZipf(productos.size(), 0.9, r);
        double[] acumC = acumuladoZipf(clientes.size(), 0.6, r);
        int[] emp = empleados.stream().mapToInt(Integer::intValue).toArray();
        return new Catalogo(productos, acumP, clientes, acumC, emp);
    }

    private static double[] acumuladoZipf(int n, double exponente, Random r) {
        List<Integer> rango = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rango.add(i);
        }
        Collections.shuffle(rango, r);
        double[] pesos = new double[n];
        for (int i = 0; i < n; i++) {
            pesos[i] = 1.0 / Math.pow(rango.get(i) + 1.0, exponente);
        }
        double[] acum = new double[n];
        double suma = 0;
        for (int i = 0; i < n; i++) {
            suma += pesos[i];
            acum[i] = suma;
        }
        return acum;
    }

    private static int muestrear(Random r, double[] acum) {
        double x = r.nextDouble() * acum[acum.length - 1];
        int lo = 0;
        int hi = acum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (acum[mid] < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    //  un dia de ventas
    private void generarDiaInterno(Catalogo cat, LocalDate fecha, Acum acum, boolean historico) {
        Random r = new Random(cfg.semilla() * 31 + fecha.toEpochDay() * 0x9E3779B97F4A7C15L + 7);
        int total = ModeloVentas.pedidosDelDia(r, cfg.pedidosPorDia(), fecha);
        int nOnline = 0;
        for (int i = 0; i < total; i++) {
            if (r.nextDouble() < cfg.proporcionOnline()) {
                nOnline++;
            }
        }
        int nTienda = total - nOnline;
        boolean reciente = fecha.isAfter(LocalDate.now(ZONA).minusDays(3));
        List<ErrorQa> errores = new ArrayList<>();
        List<LineaOnline> lineasOnline = new ArrayList<>();
        int lineasTienda = 0;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (historico) {
                    // Conserva las marcas updated_at que asigna el generador (ver migracion OLTP V3)
                    try (Statement st = c.createStatement()) {
                        st.execute("SET LOCAL ventasdw.omitir_touch = 'on'");
                    }
                }
                //   Canal tienda: va al OLTP
                if (nTienda > 0) {
                    List<Long> ids = reservarIdsPedido(c, nTienda);
                    try (PreparedStatement psPedido = c.prepareStatement(
                                 "INSERT INTO pedido (pedido_id, cliente_id, empleado_id, fecha_pedido, estado, updated_at) "
                                         + "VALUES (?, ?, ?, ?, ?, ?)");
                         PreparedStatement psDetalle = c.prepareStatement(
                                 "INSERT INTO detalle_pedido (pedido_id, linea, producto_id, cantidad, precio_unitario, descuento) "
                                         + "VALUES (?, ?, ?, ?, ?, ?)")) {
                        for (long pedidoId : ids) {
                            Cli cli = cat.clientes().get(muestrear(r, cat.acumClientes()));
                            int empleado = cat.empleados()[r.nextInt(cat.empleados().length)];
                            LocalDate fechaPedido = fecha;
                            if (r.nextDouble() < cfg.tasaErrores() / 4) {
                                fechaPedido = LocalDate.now(ZONA).plusDays(1 + r.nextInt(30));
                                errores.add(new ErrorQa("OLTP", TipoError.FECHA_FUTURA, pedidoId, null, fecha,
                                        "fecha_pedido=" + fechaPedido));
                            }
                            psPedido.setLong(1, pedidoId);
                            psPedido.setInt(2, cli.id());
                            psPedido.setInt(3, empleado);
                            psPedido.setObject(4, fechaPedido);
                            psPedido.setString(5, estadoInicial(r, fecha, reciente));
                            psPedido.setObject(6, marca(r, fecha, historico));
                            psPedido.addBatch();

                            int nLineas = ModeloVentas.lineasPorPedido(r);
                            for (int linea = 1; linea <= nLineas; linea++) {
                                Prod p = cat.productos().get(muestrear(r, cat.acumProductos()));
                                Integer productoId = p.id();
                                Integer cantidad = ModeloVentas.cantidad(r);
                                BigDecimal precio = precioEfectivo(r, p.precio());
                                BigDecimal descuento = BigDecimal.valueOf(ModeloVentas.descuento(r));

                                if (r.nextDouble() < cfg.tasaErrores()) {
                                    TipoError t = elegirErrorOltp(r);
                                    switch (t) {
                                        case PRECIO_NULO -> precio = null;
                                        case CANTIDAD_NO_POSITIVA -> cantidad = r.nextBoolean() ? 0 : -(1 + r.nextInt(3));
                                        case PRECIO_ATIPICO -> precio = precio.multiply(BigDecimal.valueOf(50 + r.nextInt(51)))
                                                .setScale(2, RoundingMode.HALF_UP);
                                        case PRODUCTO_NULO -> productoId = null;
                                        default -> throw new IllegalStateException("Tipo no aplicable: " + t);
                                    }
                                    errores.add(new ErrorQa("OLTP", t, pedidoId, linea, fecha, null));
                                }
                                psDetalle.setLong(1, pedidoId);
                                psDetalle.setInt(2, linea);
                                if (productoId == null) {
                                    psDetalle.setNull(3, Types.INTEGER);
                                } else {
                                    psDetalle.setInt(3, productoId);
                                }
                                if (cantidad == null) {
                                    psDetalle.setNull(4, Types.INTEGER);
                                } else {
                                    psDetalle.setInt(4, cantidad);
                                }
                                if (precio == null) {
                                    psDetalle.setNull(5, Types.NUMERIC);
                                } else {
                                    psDetalle.setBigDecimal(5, precio);
                                }
                                psDetalle.setBigDecimal(6, descuento);
                                psDetalle.addBatch();
                                lineasTienda++;
                            }
                        }
                        psPedido.executeBatch();
                        psDetalle.executeBatch();
                    }
                }

                //  
                if (nOnline > 0) {
                    long primerId = reservarIdsOnline(c, nOnline);
                    for (int i = 0; i < nOnline; i++) {
                        lineasOnline.addAll(pedidoOnline(r, cat, fecha, primerId + i, reciente, errores, historico));
                    }
                }

                registrarErrores(c, errores);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Fallo la generacion del dia " + fecha, e);
        }

        if (!lineasOnline.isEmpty()) {
            try {
                CsvOnlineWriter.escribir(cfg.directorioCsv(), fecha, lineasOnline);
                acum.archivosCsv++;
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo escribir el CSV online de " + fecha, e);
            }
        }
        acum.dias++;
        acum.pedidos += total;
        acum.pedidosOnline += nOnline;
        acum.lineas += lineasTienda + lineasOnline.size();
        acum.lineasOnline += lineasOnline.size();
        for (ErrorQa e : errores) {
            acum.errores.merge(e.tipo(), 1, Integer::sum);
        }
    }

    private List<LineaOnline> pedidoOnline(Random r, Catalogo cat, LocalDate fecha, long pedidoId,
                                           boolean reciente, List<ErrorQa> errores, boolean historico) {
        Cli cli = cat.clientes().get(muestrear(r, cat.acumClientes()));
        double tipoCambio = 3.60 + 0.15 * Math.sin(fecha.toEpochDay() / 60.0);
        LocalDate fechaPedido = fecha;
        if (r.nextDouble() < cfg.tasaErrores() / 4) {
            fechaPedido = LocalDate.now(ZONA).plusDays(1 + r.nextInt(30));
            errores.add(new ErrorQa("CSV_ONLINE", TipoError.FECHA_FUTURA, pedidoId, null, fecha, "fecha_pedido=" + fechaPedido));
        }
        String estado = reciente
                ? (r.nextDouble() < 0.6 ? "PENDIENTE" : "ENVIADO")
                : r.nextDouble() < 0.95 ? ModeloVentas.estadoEntregado(r) : "CANCELADO";
        String razon = r.nextDouble() < 0.20 ? cli.nombre().toUpperCase() : cli.nombre();
        String email = cli.email() == null ? null : (r.nextDouble() < 0.10 ? " " + cli.email().toUpperCase() + " " : cli.email());
        OffsetDateTime ts = marca(r, fecha, historico);

        List<LineaOnline> lineas = new ArrayList<>();
        int nLineas = ModeloVentas.lineasPorPedido(r);
        for (int linea = 1; linea <= nLineas; linea++) {
            Prod p = cat.productos().get(muestrear(r, cat.acumProductos()));
            Integer productoId = p.id();
            Integer cantidad = ModeloVentas.cantidad(r);
            BigDecimal precioPen = precioEfectivo(r, p.precio());
            boolean enDolares = r.nextDouble() < 0.85;
            BigDecimal precio = enDolares
                    ? precioPen.divide(BigDecimal.valueOf(tipoCambio), 2, RoundingMode.HALF_UP)
                    : precioPen;
            String moneda = enDolares ? "USD" : "PEN";
            BigDecimal descuento = BigDecimal.valueOf(ModeloVentas.descuento(r));
            boolean duplicar = false;

            if (r.nextDouble() < cfg.tasaErrores()) {
                TipoError t = elegirErrorCsv(r);
                switch (t) {
                    case PRECIO_NULO -> precio = null;
                    case CANTIDAD_NO_POSITIVA -> cantidad = r.nextBoolean() ? 0 : -(1 + r.nextInt(3));
                    case PRECIO_ATIPICO -> precio = precio.multiply(BigDecimal.valueOf(50 + r.nextInt(51)))
                            .setScale(2, RoundingMode.HALF_UP);
                    case PRODUCTO_INEXISTENTE -> productoId = 99000 + r.nextInt(900);
                    case LINEA_DUPLICADA -> duplicar = true;
                    default -> throw new IllegalStateException("Tipo no aplicable: " + t);
                }
                errores.add(new ErrorQa("CSV_ONLINE", t, pedidoId, linea, fecha, null));
            }
            LineaOnline l = new LineaOnline(pedidoId, linea, fechaPedido, estado, cli.id(), razon, email,
                    cli.ciudadId(), productoId, p.nombre(), p.categoria(), cantidad, precio, descuento, moneda, ts);
            lineas.add(l);
            if (duplicar) {
                lineas.add(l);
            }
        }
        return lineas;
    }

    private static TipoError elegirErrorOltp(Random r) {
        TipoError[] t = {TipoError.PRECIO_NULO, TipoError.CANTIDAD_NO_POSITIVA, TipoError.PRECIO_ATIPICO, TipoError.PRODUCTO_NULO};
        int i = ModeloVentas.elegir(r, new int[] {0, 1, 2, 3}, new int[] {30, 25, 25, 20});
        return t[i];
    }

    private static TipoError elegirErrorCsv(Random r) {
        TipoError[] t = {TipoError.PRECIO_NULO, TipoError.CANTIDAD_NO_POSITIVA, TipoError.PRECIO_ATIPICO,
            TipoError.PRODUCTO_INEXISTENTE, TipoError.LINEA_DUPLICADA};
        int i = ModeloVentas.elegir(r, new int[] {0, 1, 2, 3, 4}, new int[] {25, 20, 20, 15, 20});
        return t[i];
    }

    private static String estadoInicial(Random r, LocalDate fecha, boolean reciente) {
        if (reciente) {
            return r.nextDouble() < 0.6 ? "PENDIENTE" : "ENVIADO";
        }
        double x = r.nextDouble();
        if (x < 0.93) {
            return ModeloVentas.estadoEntregado(r);
        }
        return x < 0.98 ? "CANCELADO" : "DEVUELTO";
    }

    private static BigDecimal precioEfectivo(Random r, BigDecimal lista) {
        double factor = 1 + (r.nextDouble() - 0.5) * 0.06;
        return lista.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime marca(Random r, LocalDate fecha, boolean historico) {
        OffsetDateTime simulada = marcaDeTiempo(r, fecha);   // consume siempre el mismo numero de valores aleatorios
        return historico ? simulada : OffsetDateTime.now(ZONA);
    }

    private static OffsetDateTime marcaDeTiempo(Random r, LocalDate fecha) {
        OffsetDateTime ts = fecha.atTime(8 + r.nextInt(14), r.nextInt(60), r.nextInt(60)).atZone(ZONA).toOffsetDateTime();
        OffsetDateTime ahora = OffsetDateTime.now(ZONA);
        return ts.isAfter(ahora) ? ahora : ts;
    }

    //   flujo diario

    private void avanzarEstados(LocalDate fecha, Acum acum) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pedido SET estado = 'CANCELADO' WHERE estado = 'PENDIENTE' AND fecha_pedido <= ? AND random() < 0.04")) {
                    ps.setObject(1, fecha.minusDays(1));
                    n += ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pedido SET estado = (ARRAY['ENTREGADO','ENTREGADO','ENTREGADO','ENTREGADO','ENTREGADO',"
                                + "'ENTREGADO','ENT','E','Entregado'])[1 + floor(random() * 9)::int] "
                                + "WHERE estado IN ('PENDIENTE', 'ENVIADO') AND fecha_pedido <= ?")) {
                    ps.setObject(1, fecha.minusDays(2));
                    n += ps.executeUpdate();
                }
                c.commit();
                acum.estados += n;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron avanzar los estados", e);
        }
    }

    private void aplicarCambiosEnMaestros(Random r, Acum acum) {
        Catalogo cat = leerCatalogo();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int cambios = 0;
                List<Integer> categorias = ids(c, "SELECT categoria_id FROM categoria ORDER BY categoria_id");
                if (r.nextDouble() < 0.20) {
                    Prod p = cat.productos().get(r.nextInt(cat.productos().size()));
                    int nueva = categorias.get(r.nextInt(categorias.size()));
                    if (nueva != p.categoriaId()) {
                        cambios += actualizar(c, "UPDATE producto SET categoria_id = ? WHERE producto_id = ?", nueva, p.id());
                    }
                }
                if (r.nextDouble() < 0.03) {
                    Prod p = cat.productos().get(r.nextInt(cat.productos().size()));
                    if (!p.nombre().endsWith(" (nueva presentacion)")) {
                        try (PreparedStatement ps = c.prepareStatement("UPDATE producto SET nombre = ? WHERE producto_id = ?")) {
                            ps.setString(1, p.nombre() + " (nueva presentacion)");
                            ps.setInt(2, p.id());
                            cambios += ps.executeUpdate();
                        }
                    }
                }
                if (r.nextDouble() < 0.30) {
                    int k = 1 + r.nextInt(3);
                    for (int i = 0; i < k; i++) {
                        Cli cli = cat.clientes().get(r.nextInt(cat.clientes().size()));
                        try (PreparedStatement ps = c.prepareStatement("UPDATE cliente SET email = ? WHERE cliente_id = ?")) {
                            ps.setString(1, correo(r, cli.nombre(), r.nextInt(99)));
                            ps.setInt(2, cli.id());
                            cambios += ps.executeUpdate();
                        }
                    }
                }
                if (r.nextDouble() < 0.05) {
                    Cli cli = cat.clientes().get(r.nextInt(cat.clientes().size()));
                    try (PreparedStatement ps = c.prepareStatement("UPDATE cliente SET nombre = ? WHERE cliente_id = ?")) {
                        ps.setString(1, nombreCompleto(r));
                        ps.setInt(2, cli.id());
                        cambios += ps.executeUpdate();
                    }
                }
                if (r.nextDouble() < 0.05) {
                    Cli cli = cat.clientes().get(r.nextInt(cat.clientes().size()));
                    List<Integer> ciudades = ids(c, "SELECT ciudad_id FROM ciudad ORDER BY ciudad_id");
                    cambios += actualizar(c, "UPDATE cliente SET ciudad_id = ? WHERE cliente_id = ?",
                            ciudades.get(r.nextInt(ciudades.size())), cli.id());
                }
                c.commit();
                acum.maestros += cambios;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron aplicar cambios en maestros", e);
        }
    }

    //   utilidades JDBC

    private List<Long> reservarIdsPedido(Connection c, int cantidad) throws SQLException {
        List<Long> ids = new ArrayList<>(cantidad);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT nextval(pg_get_serial_sequence('pedido', 'pedido_id')) FROM generate_series(1, ?)")) {
            ps.setInt(1, cantidad);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private long reservarIdsOnline(Connection c, int cantidad) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE generador_estado SET valor = valor + ? WHERE clave = 'pedido_online' RETURNING valor - ?")) {
            ps.setInt(1, cantidad);
            ps.setInt(2, cantidad);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void registrarErrores(Connection c, List<ErrorQa> errores) throws SQLException {
        if (errores.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO qa_error_inyectado (fuente, tipo, pedido_id, linea, fecha_pedido, detalle) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (ErrorQa e : errores) {
                ps.setString(1, e.fuente());
                ps.setString(2, e.tipo().name());
                ps.setLong(3, e.pedidoId());
                if (e.linea() == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setInt(4, e.linea());
                }
                ps.setObject(5, e.fecha());
                ps.setString(6, e.detalle());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private long escalar(String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Consulta fallida: " + sql, e);
        }
    }

    private static List<Integer> ids(Connection c, String sql) throws SQLException {
        List<Integer> lista = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(rs.getInt(1));
            }
        }
        return lista;
    }

    private static int actualizar(Connection c, String sql, int a, int b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, a);
            ps.setInt(2, b);
            return ps.executeUpdate();
        }
    }

    private static int[] indices(int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = i;
        }
        return v;
    }

    private static String nombreCompleto(Random r) {
        return Catalogos.NOMBRES[r.nextInt(Catalogos.NOMBRES.length)] + " "
                + Catalogos.APELLIDOS[r.nextInt(Catalogos.APELLIDOS.length)] + " "
                + Catalogos.APELLIDOS[r.nextInt(Catalogos.APELLIDOS.length)];
    }

    private static String correo(Random r, String nombre, int n) {
        String[] partes = nombre.split(" ");
        String local = Catalogos.paraCorreo(partes[0]) + "." + Catalogos.paraCorreo(partes[1]) + n;
        return local + "@" + Catalogos.DOMINIOS[r.nextInt(Catalogos.DOMINIOS.length)];
    }
}
