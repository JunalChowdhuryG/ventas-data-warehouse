package pe.ventasdw.olap.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import pe.ventasdw.olap.core.CuboVentas.Atributo;
import pe.ventasdw.olap.core.CuboVentas.Dimension;
import pe.ventasdw.olap.core.CuboVentas.Tipo;

/**
 * Motor OLAP sobre el esquema estrella. Cada operación (consulta, drill-down, drill-up, drill-across,
 * roll-across, pivot y page) recibe un contexto, calcula el nuevo y lo ejecuta contra el DWH.
 * Solo lee; no guarda estado entre llamadas.
 */
public final class OlapService {

    private static final int MAX_COLUMNAS_PIVOT = 60;
    private static final int MAX_VALORES_PAGINA = 200;
    private static final int TIMEOUT_SEGUNDOS = 30;

    private final DataSource dwh;

    public OlapService(DataSource dwh) {
        this.dwh = dwh;
    }

    // ------------------------------------------------------------------------------ operaciones

    public Resultado consultar(Contexto solicitado) {
        return ejecutar(solicitado.normalizado(), null);
    }

    /**
     * Baja un nivel en la jerarquía de la dimensión. Si la dimensión no está en los ejes, agrega su nivel superior.
     * "miembro" fija valores de atributos (p. ej. fecha.anio = 2026) para ver el detalle de esos miembros.
     */
    public Resultado drillDown(Contexto solicitado, String dimension, Map<String, String> miembro) {
        Contexto c = solicitado.normalizado();
        Dimension d = CuboVentas.dimension(dimension);
        if (d.jerarquia().size() < 2) {
            throw new OlapException("La dimensión " + dimension + " no tiene jerarquía para bajar de nivel");
        }
        List<String> presentes = nivelesPresentes(c, d);
        String nuevo;
        if (presentes.isEmpty()) {
            nuevo = d.jerarquia().get(0);
        } else {
            int ultimo = d.jerarquia().indexOf(presentes.get(presentes.size() - 1));
            if (ultimo == d.jerarquia().size() - 1) {
                throw new OlapException("La dimensión " + dimension + " ya está en su nivel más bajo (" + d.jerarquia().get(ultimo) + ")");
            }
            nuevo = d.jerarquia().get(ultimo + 1);
        }
        Contexto resultado = insertarJuntoAlNivel(c, d, presentes, nuevo);
        if (miembro != null) {
            for (Map.Entry<String, String> e : miembro.entrySet()) {
                CuboVentas.atributo(e.getKey());
                resultado = resultado.conFiltroIgual(e.getKey(), e.getValue());
            }
        }
        return ejecutar(resultado.normalizado(), null);
    }

    /** Sube un nivel: quita el nivel más bajo de la dimensión presente en los ejes. */
    public Resultado drillUp(Contexto solicitado, String dimension) {
        Contexto c = solicitado.normalizado();
        Dimension d = CuboVentas.dimension(dimension);
        List<String> presentes = nivelesPresentes(c, d);
        if (presentes.size() < 2) {
            throw new OlapException("La dimensión " + dimension + " ya está en su nivel superior: no hay nivel al que subir");
        }
        String quitar = presentes.get(presentes.size() - 1);
        return ejecutar(sinAtributo(c, quitar).normalizado(), null);
    }

    /** Agrega un criterio de análisis (análisis a mayor detalle). */
    public Resultado drillAcross(Contexto solicitado, String atributo) {
        Contexto c = solicitado.normalizado();
        CuboVentas.atributo(atributo);
        if (c.atributos().contains(atributo)) {
            throw new OlapException("El atributo " + atributo + " ya está en el análisis");
        }
        List<String> filas = new ArrayList<>(c.filas());
        filas.add(atributo);
        return ejecutar(c.conFilas(filas).normalizado(), null);
    }

    /** Quita un criterio de análisis y vuelve a agregar los indicadores. */
    public Resultado rollAcross(Contexto solicitado, String atributo) {
        Contexto c = solicitado.normalizado();
        CuboVentas.atributo(atributo);
        if (!c.atributos().contains(atributo)) {
            throw new OlapException("El atributo " + atributo + " no está en el análisis actual");
        }
        return ejecutar(sinAtributo(c, atributo).normalizado(), null);
    }

    /** Reordena los ejes: mueve atributos entre filas y columnas o cambia su orden. Los atributos no pueden cambiar. */
    public Resultado pivot(Contexto solicitado, List<String> filas, List<String> columnas) {
        Contexto c = solicitado.normalizado();
        List<String> nuevasFilas = filas == null ? List.of() : filas;
        List<String> nuevasColumnas = columnas == null ? List.of() : columnas;
        List<String> actuales = new ArrayList<>(c.atributos());
        List<String> nuevos = new ArrayList<>(nuevasFilas);
        nuevos.addAll(nuevasColumnas);
        if (actuales.size() != nuevos.size() || !new java.util.HashSet<>(actuales).equals(new java.util.HashSet<>(nuevos))) {
            throw new OlapException("El pivot solo reordena los atributos actuales " + actuales + "; recibido " + nuevos);
        }
        return ejecutar(c.conEjes(nuevasFilas, nuevasColumnas).normalizado(), null);
    }

    /**
     * Presenta el cubo dividido por los valores de un atributo, como las páginas de un libro. Devuelve la lista de páginas
     * y los datos de la página pedida (la primera si no se indica).
     */
    public Resultado page(Contexto solicitado, String atributo, String valor) {
        Contexto c = solicitado.normalizado();
        Atributo a = CuboVentas.atributo(atributo);
        // Valores disponibles según los filtros actuales (sin el filtro del propio atributo)
        List<Contexto.Filtro> sinFiltroPropio = new ArrayList<>();
        for (Contexto.Filtro f : c.filtros()) {
            if (!f.atributo().equals(atributo)) {
                sinFiltroPropio.add(f);
            }
        }
        Contexto paraValores = new Contexto(List.of(atributo), List.of(), List.of("ventas"), sinFiltroPropio, List.of(),
                MAX_VALORES_PAGINA, false, c.usarAgregados()).normalizado();
        List<Object> valores = new ArrayList<>();
        for (Map<String, Object> fila : ejecutar(paraValores, null).datos()) {
            valores.add(fila.get(atributo));
        }
        if (valores.isEmpty()) {
            throw new OlapException("No hay datos para paginar por " + atributo + " con los filtros actuales");
        }
        String actual = valor != null ? valor : String.valueOf(valores.get(0));
        if (valor != null && valores.stream().noneMatch(v -> String.valueOf(v).equals(valor))) {
            throw new OlapException("La página '" + valor + "' no existe para " + atributo);
        }
        Contexto conPagina = c.conFiltroIgual(atributo, actual).normalizado();
        return ejecutar(conPagina, new Resultado.Paginas(atributo, valores, a.tipo() == Tipo.TEXTO ? actual : ConstructorConsulta.convertir(actual, a.tipo(), atributo)));
    }

    /** Valores distintos de un atributo directamente desde su dimensión (para armar filtros). */
    public List<Object> valoresDe(String atributo, String texto, int limite) {
        Atributo a = CuboVentas.atributo(atributo);
        Dimension d = CuboVentas.dimension(a.dimension());
        int lim = Math.max(1, Math.min(limite, 500));
        boolean filtrar = texto != null && !texto.isBlank() && a.tipo() == Tipo.TEXTO;
        String sql = "SELECT DISTINCT " + a.expresion() + " AS v FROM " + d.tabla() + " " + d.alias()
                + (filtrar ? " WHERE " + a.expresion() + " ILIKE ?" : "") + " ORDER BY 1 LIMIT " + lim;
        List<Object> valores = new ArrayList<>();
        try (Connection con = dwh.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            con.setReadOnly(true);
            ps.setQueryTimeout(TIMEOUT_SEGUNDOS);
            if (filtrar) {
                ps.setString(1, "%" + texto.trim() + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    valores.add(leer(rs, "v", a.tipo()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los valores de " + atributo, e);
        }
        return valores;
    }

    // ------------------------------------------------------------------------------ ejecución

    private Resultado ejecutar(Contexto c, Resultado.Paginas paginas) {
        long inicio = System.nanoTime();
        ConstructorConsulta.Consulta q = ConstructorConsulta.construir(c);
        List<Map<String, Object>> datos = new ArrayList<>();
        boolean truncado = false;
        try (Connection con = dwh.getConnection(); PreparedStatement ps = con.prepareStatement(q.sql())) {
            con.setReadOnly(true);
            ps.setQueryTimeout(TIMEOUT_SEGUNDOS);
            for (int i = 0; i < q.parametros().size(); i++) {
                ps.setObject(i + 1, q.parametros().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (datos.size() == c.limite()) {
                        truncado = true;
                        break;
                    }
                    Map<String, Object> fila = new LinkedHashMap<>();
                    boolean total = q.conSubtotales();
                    boolean algunoAgregado = false;
                    boolean todosAgregados = true;
                    for (int i = 0; i < q.atributos().size(); i++) {
                        String id = q.atributos().get(i);
                        fila.put(id, leer(rs, "a" + i, CuboVentas.atributo(id).tipo()));
                        if (total) {
                            boolean agregado = rs.getInt("g" + i) == 1;
                            algunoAgregado |= agregado;
                            todosAgregados &= agregado;
                        }
                    }
                    for (int j = 0; j < q.medidas().size(); j++) {
                        fila.put(q.medidas().get(j), rs.getObject("m" + j));
                    }
                    if (total) {
                        fila.put("_subtotal", algunoAgregado && !todosAgregados);
                        fila.put("_total", todosAgregados);
                    }
                    datos.add(fila);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falló la consulta OLAP: " + e.getMessage(), e);
        }

        List<Resultado.Columna> columnas = new ArrayList<>();
        for (String a : q.atributos()) {
            columnas.add(new Resultado.Columna(a, "atributo", CuboVentas.atributo(a).etiqueta()));
        }
        for (String m : q.medidas()) {
            columnas.add(new Resultado.Columna(m, "medida", CuboVentas.medida(m).etiqueta()));
        }
        Resultado.Pivot pivot = c.columnas().isEmpty() ? null : construirPivot(c, datos);
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        return new Resultado(c, columnas, datos, pivot, paginas, q.origen(), truncado, operacionesDisponibles(c), ms, q.sql());
    }

    private static Object leer(ResultSet rs, String columna, Tipo tipo) throws SQLException {
        return switch (tipo) {
            case ENTERO -> {
                int v = rs.getInt(columna);
                yield rs.wasNull() ? null : v;
            }
            case FECHA -> rs.getObject(columna, LocalDate.class);
            case TEXTO -> rs.getString(columna);
        };
    }

    // ------------------------------------------------------------------------------ pivot

    private Resultado.Pivot construirPivot(Contexto c, List<Map<String, Object>> datos) {
        TreeSet<List<Object>> columnasOrdenadas = new TreeSet<>(COMPARADOR_TUPLAS);
        Map<List<Object>, Map<List<Object>, Map<String, Object>>> porFila = new LinkedHashMap<>();
        for (Map<String, Object> fila : datos) {
            List<Object> claveFila = valores(fila, c.filas());
            List<Object> claveColumna = valores(fila, c.columnas());
            columnasOrdenadas.add(claveColumna);
            Map<String, Object> medidas = new LinkedHashMap<>();
            for (String m : c.medidas()) {
                medidas.put(m, fila.get(m));
            }
            porFila.computeIfAbsent(claveFila, k -> new LinkedHashMap<>()).put(claveColumna, medidas);
        }
        if (columnasOrdenadas.size() > MAX_COLUMNAS_PIVOT) {
            throw new OlapException("El pivot tendría " + columnasOrdenadas.size() + " columnas (máximo " + MAX_COLUMNAS_PIVOT
                    + "). Filtra o usa un atributo de columna con menos valores.");
        }
        List<List<Object>> columnas = new ArrayList<>(columnasOrdenadas);
        List<Resultado.PivotFila> filas = new ArrayList<>();
        for (Map.Entry<List<Object>, Map<List<Object>, Map<String, Object>>> e : porFila.entrySet()) {
            List<Map<String, Object>> celdas = new ArrayList<>();
            for (List<Object> col : columnas) {
                celdas.add(e.getValue().get(col));
            }
            filas.add(new Resultado.PivotFila(e.getKey(), celdas));
        }
        return new Resultado.Pivot(c.filas(), c.columnas(), columnas, filas);
    }

    private static List<Object> valores(Map<String, Object> fila, List<String> atributos) {
        List<Object> v = new ArrayList<>();
        for (String a : atributos) {
            v.add(fila.get(a));
        }
        return v;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Comparator<List<Object>> COMPARADOR_TUPLAS = (a, b) -> {
        for (int i = 0; i < a.size(); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            if (x == null && y == null) {
                continue;
            }
            if (x == null) {
                return 1;
            }
            if (y == null) {
                return -1;
            }
            int r = ((Comparable) x).compareTo(y);
            if (r != 0) {
                return r;
            }
        }
        return 0;
    };

    // ------------------------------------------------------------------------------ jerarquías y operaciones disponibles

    /** Niveles de la dimensión presentes en los ejes, ordenados de arriba hacia abajo en la jerarquía. */
    private static List<String> nivelesPresentes(Contexto c, Dimension d) {
        List<String> presentes = new ArrayList<>();
        for (String nivel : d.jerarquia()) {
            if (c.atributos().contains(nivel)) {
                presentes.add(nivel);
            }
        }
        return presentes;
    }

    /** Inserta el nuevo nivel justo después del último nivel presente de la dimensión, en el mismo eje; si no hay, al final de las filas. */
    private static Contexto insertarJuntoAlNivel(Contexto c, Dimension d, List<String> presentes, String nuevo) {
        List<String> filas = new ArrayList<>(c.filas());
        List<String> columnas = new ArrayList<>(c.columnas());
        if (presentes.isEmpty()) {
            filas.add(nuevo);
        } else {
            String ultimo = presentes.get(presentes.size() - 1);
            if (filas.contains(ultimo)) {
                filas.add(filas.indexOf(ultimo) + 1, nuevo);
            } else {
                columnas.add(columnas.indexOf(ultimo) + 1, nuevo);
            }
        }
        return c.conEjes(filas, columnas);
    }

    private static Contexto sinAtributo(Contexto c, String atributo) {
        List<String> filas = new ArrayList<>(c.filas());
        List<String> columnas = new ArrayList<>(c.columnas());
        filas.remove(atributo);
        columnas.remove(atributo);
        List<Contexto.Orden> orden = new ArrayList<>();
        for (Contexto.Orden o : c.orden()) {
            if (!o.campo().equals(atributo)) {
                orden.add(o);
            }
        }
        return new Contexto(filas, columnas, c.medidas(), c.filtros(), orden, c.limite(), c.subtotales() && !filas.isEmpty(), c.usarAgregados());
    }

    private static Map<String, List<String>> operacionesDisponibles(Contexto c) {
        List<String> drillDown = new ArrayList<>();
        List<String> drillUp = new ArrayList<>();
        for (Dimension d : CuboVentas.dimensiones().values()) {
            if (d.jerarquia().size() < 2) {
                continue;
            }
            List<String> presentes = nivelesPresentes(c, d);
            if (presentes.isEmpty() || d.jerarquia().indexOf(presentes.get(presentes.size() - 1)) < d.jerarquia().size() - 1) {
                drillDown.add(d.id());
            }
            if (presentes.size() >= 2) {
                drillUp.add(d.id());
            }
        }
        List<String> fuera = new ArrayList<>();
        for (String a : CuboVentas.atributos().keySet()) {
            if (!c.atributos().contains(a)) {
                fuera.add(a);
            }
        }
        Map<String, List<String>> ops = new LinkedHashMap<>();
        ops.put("drillDown", drillDown);
        ops.put("drillUp", drillUp);
        ops.put("drillAcross", fuera);
        ops.put("rollAcross", c.atributos());
        ops.put("pivot", c.atributos().size() >= 2 ? List.of("mover entre filas y columnas") : List.of());
        ops.put("page", fuera);
        return ops;
    }
}
