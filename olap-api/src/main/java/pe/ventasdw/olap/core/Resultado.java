package pe.ventasdw.olap.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Respuesta de una operación OLAP: datos, contexto resultante y qué operaciones siguen disponibles. */
public record Resultado(
        Contexto contexto,
        List<Columna> columnas,
        List<Map<String, Object>> datos,
        Pivot pivot,
        Paginas paginas,
        String origen,
        boolean truncado,
        Map<String, List<String>> operacionesDisponibles,
        long duracionMs,
        String sql) {

    public record Columna(String id, String tipo, String etiqueta) {
    }

    /** Tabla dinámica: una fila por combinación de atributos de fila, una columna por combinación de atributos de columna. */
    public record Pivot(List<String> atributosFila, List<String> atributosColumna, List<List<Object>> columnas, List<PivotFila> filas) {
    }

    /** celdas va alineada con Pivot.columnas; una celda es null cuando no hay datos para esa combinación. */
    public record PivotFila(List<Object> clave, List<Map<String, Object>> celdas) {
    }

    public record Paginas(String atributo, List<Object> valores, Object actual) {
    }

    public String aJson(boolean incluirSql) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contexto", contextoComoMapa(contexto));
        List<Object> cols = new ArrayList<>();
        for (Columna c : columnas) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("tipo", c.tipo());
            cm.put("etiqueta", c.etiqueta());
            cols.add(cm);
        }
        m.put("columnas", cols);
        m.put("datos", datos);
        if (pivot != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("atributosFila", pivot.atributosFila());
            p.put("atributosColumna", pivot.atributosColumna());
            p.put("columnas", pivot.columnas());
            List<Object> filasPivot = new ArrayList<>();
            for (PivotFila f : pivot.filas()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("clave", f.clave());
                fm.put("celdas", f.celdas());
                filasPivot.add(fm);
            }
            p.put("filas", filasPivot);
            m.put("pivot", p);
        }
        if (paginas != null) {
            Map<String, Object> pg = new LinkedHashMap<>();
            pg.put("atributo", paginas.atributo());
            pg.put("valores", paginas.valores());
            pg.put("actual", paginas.actual());
            m.put("paginas", pg);
        }
        m.put("origen", origen);
        m.put("truncado", truncado);
        m.put("operacionesDisponibles", operacionesDisponibles);
        m.put("duracionMs", duracionMs);
        if (incluirSql) {
            m.put("sql", sql);
        }
        return Json.escribir(m);
    }

    static Map<String, Object> contextoComoMapa(Contexto c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filas", c.filas());
        m.put("columnas", c.columnas());
        m.put("medidas", c.medidas());
        List<Object> filtros = new ArrayList<>();
        for (Contexto.Filtro f : c.filtros()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("atributo", f.atributo());
            if (f.valores() != null && !f.valores().isEmpty()) {
                fm.put("valores", f.valores());
            }
            if (f.desde() != null) {
                fm.put("desde", f.desde());
            }
            if (f.hasta() != null) {
                fm.put("hasta", f.hasta());
            }
            filtros.add(fm);
        }
        m.put("filtros", filtros);
        List<Object> orden = new ArrayList<>();
        for (Contexto.Orden o : c.orden()) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("campo", o.campo());
            om.put("direccion", o.direccion() == null ? "asc" : o.direccion());
            orden.add(om);
        }
        m.put("orden", orden);
        m.put("limite", c.limite());
        m.put("subtotales", c.subtotales());
        m.put("usarAgregados", c.usarAgregados());
        return m;
    }
}
