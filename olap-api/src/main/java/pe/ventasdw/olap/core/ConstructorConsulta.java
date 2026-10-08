package pe.ventasdw.olap.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pe.ventasdw.olap.core.CuboVentas.Agregado;
import pe.ventasdw.olap.core.CuboVentas.Atributo;
import pe.ventasdw.olap.core.CuboVentas.Dimension;
import pe.ventasdw.olap.core.CuboVentas.Tipo;

/**
 * Traduce un contexto OLAP a SQL
 */
final class ConstructorConsulta {
 
    record Consulta(String sql, List<Object> parametros, List<String> atributos, List<String> medidas,
                    boolean conSubtotales, String origen) {
    }

    private ConstructorConsulta() {
    }

    static Consulta construir(Contexto c) {
        List<String> atributos = c.atributos();
        Agregado agregado = c.usarAgregados() ? elegirAgregado(c, atributos) : null;

        List<String> select = new ArrayList<>();
        List<String> expresiones = new ArrayList<>();
        for (int i = 0; i < atributos.size(); i++) {
            String expr = expresion(atributos.get(i), agregado);
            expresiones.add(expr);
            select.add(expr + " AS a" + i);
        }
        for (int j = 0; j < c.medidas().size(); j++) {
            String m = c.medidas().get(j);
            select.add((agregado != null ? agregado.medidasSql().get(m) : CuboVentas.medida(m).sql()) + " AS m" + j);
        }
        if (c.subtotales() && !atributos.isEmpty()) {
            for (int i = 0; i < atributos.size(); i++) {
                select.add("GROUPING(" + expresiones.get(i) + ") AS g" + i);
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select)).append(" FROM ");
        if (agregado != null) {
            sql.append(agregado.tabla()).append(" m");
        } else {
            sql.append("dwh.fact_ventas f");
            for (Dimension d : dimensionesNecesarias(c, atributos)) {
                sql.append(" JOIN ").append(d.tabla()).append(' ').append(d.alias()).append(" ON ").append(d.union());
            }
        }

        List<Object> parametros = new ArrayList<>();
        List<String> condiciones = new ArrayList<>();
        for (Contexto.Filtro f : c.filtros()) {
            String expr = expresion(f.atributo(), agregado);
            Tipo tipo = CuboVentas.atributo(f.atributo()).tipo();
            if (f.valores() != null && !f.valores().isEmpty()) {
                condiciones.add(expr + " IN (" + String.join(", ", java.util.Collections.nCopies(f.valores().size(), "?")) + ")");
                for (String v : f.valores()) {
                    parametros.add(convertir(v, tipo, f.atributo()));
                }
            } else {
                if (f.desde() != null) {
                    condiciones.add(expr + " >= ?");
                    parametros.add(convertir(f.desde(), tipo, f.atributo()));
                }
                if (f.hasta() != null) {
                    condiciones.add(expr + " <= ?");
                    parametros.add(convertir(f.hasta(), tipo, f.atributo()));
                }
            }
        }
        if (!condiciones.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", condiciones));
        }
        if (!atributos.isEmpty()) {
            String lista = String.join(", ", expresiones);
            sql.append(" GROUP BY ").append(c.subtotales() ? "ROLLUP (" + lista + ")" : lista);
        }

        List<String> orden = new ArrayList<>();
        if (c.orden().isEmpty()) {
            for (int i = 0; i < atributos.size(); i++) {
                orden.add("a" + i);
            }
        } else {
            for (Contexto.Orden o : c.orden()) {
                int ia = atributos.indexOf(o.campo());
                String alias = ia >= 0 ? "a" + ia : "m" + c.medidas().indexOf(o.campo());
                boolean desc = o.direccion() != null && o.direccion().toLowerCase(Locale.ROOT).equals("desc");
                orden.add(alias + (desc ? " DESC" : " ASC"));
            }
        }
        if (!orden.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orden));
        }
        sql.append(" LIMIT ").append(c.limite() + 1);    
        return new Consulta(sql.toString(), parametros, atributos, c.medidas(), c.subtotales() && !atributos.isEmpty(),
                agregado != null ? agregado.tabla().substring(agregado.tabla().indexOf('.') + 1) : "fact_ventas");
    }

    private static String expresion(String atributo, Agregado agregado) {
        if (agregado != null) {
            return "m." + agregado.columnasPorAtributo().get(atributo);
        }
        return CuboVentas.atributo(atributo).expresion();
    }
 
    private static Agregado elegirAgregado(Contexto c, List<String> atributos) {
        Set<String> usados = new LinkedHashSet<>(atributos);
        for (Contexto.Filtro f : c.filtros()) {
            usados.add(f.atributo());
        }
        for (Agregado a : CuboVentas.agregados()) {
            if (a.columnasPorAtributo().keySet().containsAll(usados) && a.medidasSql().keySet().containsAll(c.medidas())) {
                return a;
            }
        }
        return null;
    }

    private static List<Dimension> dimensionesNecesarias(Contexto c, List<String> atributos) {
        Set<String> ids = new LinkedHashSet<>();
        for (String a : atributos) {
            ids.add(CuboVentas.atributo(a).dimension());
        }
        for (Contexto.Filtro f : c.filtros()) {
            ids.add(CuboVentas.atributo(f.atributo()).dimension());
        }
        List<Dimension> orden = new ArrayList<>();
        for (Map.Entry<String, Dimension> e : CuboVentas.dimensiones().entrySet()) {
            if (ids.contains(e.getKey())) {
                orden.add(e.getValue());
            }
        }
        return orden;
    }

    static Object convertir(String valor, Tipo tipo, String atributo) {
        try {
            return switch (tipo) {
                case ENTERO -> Integer.valueOf(valor.trim());
                case FECHA -> LocalDate.parse(valor.trim());
                case TEXTO -> valor;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new OlapException("Valor inválido para " + atributo + ": '" + valor + "' (se esperaba " + tipo.name().toLowerCase(Locale.ROOT) + ")");
        }
    }

    static Atributo atributo(String id) {
        return CuboVentas.atributo(id);
    }
}
