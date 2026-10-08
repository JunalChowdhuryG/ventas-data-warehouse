package pe.ventasdw.olap.core;

import java.util.ArrayList;
import java.util.List;

/**
 
 */
public record Contexto(
        List<String> filas,
        List<String> columnas,
        List<String> medidas,
        List<Filtro> filtros,
        List<Orden> orden,
        Integer limite,
        Boolean subtotales,
        Boolean usarAgregados) {

    public static final int LIMITE_POR_DEFECTO = 1_000;
    public static final int LIMITE_MAXIMO = 10_000;
 
    public record Filtro(String atributo, List<String> valores, String desde, String hasta) {
    }

    public record Orden(String campo, String direccion) {
    }
 
    public Contexto normalizado() {
        List<String> f = filas == null ? List.of() : List.copyOf(filas);
        List<String> c = columnas == null ? List.of() : List.copyOf(columnas);
        List<String> m = medidas == null || medidas.isEmpty() ? List.of("ventas") : List.copyOf(medidas);
        List<Filtro> fl = filtros == null ? List.of() : List.copyOf(filtros);
        List<Orden> o = orden == null ? List.of() : List.copyOf(orden);
        int lim = limite == null ? LIMITE_POR_DEFECTO : limite;
        if (lim < 1 || lim > LIMITE_MAXIMO) {
            throw new OlapException("limite debe estar entre 1 y " + LIMITE_MAXIMO);
        }
        Contexto n = new Contexto(f, c, m, fl, o, lim, Boolean.TRUE.equals(subtotales), usarAgregados == null || usarAgregados);
        n.validar();
        return n;
    }

    private void validar() {
        List<String> todos = new ArrayList<>(filas);
        todos.addAll(columnas);
        for (String a : todos) {
            CuboVentas.atributo(a);
        }
        if (new java.util.HashSet<>(todos).size() != todos.size()) {
            throw new OlapException("Un atributo no puede repetirse en filas y columnas");
        }
        for (String m : medidas) {
            CuboVentas.medida(m);
        }
        if (new java.util.HashSet<>(medidas).size() != medidas.size()) {
            throw new OlapException("Hay medidas repetidas");
        }
        for (Filtro f : filtros) {
            CuboVentas.atributo(f.atributo());
            boolean conValores = f.valores() != null && !f.valores().isEmpty();
            boolean conRango = f.desde() != null || f.hasta() != null;
            if (conValores == conRango) {
                throw new OlapException("El filtro de " + f.atributo() + " debe tener valores o un rango (desde/hasta), no ambos ni ninguno");
            }
        }
        for (Orden o : orden) {
            boolean valido = todos.contains(o.campo()) || medidas.contains(o.campo());
            if (!valido) {
                throw new OlapException("Solo se puede ordenar por atributos de filas/columnas o medidas seleccionadas: " + o.campo());
            }
            if (o.direccion() != null && !List.of("asc", "desc").contains(o.direccion().toLowerCase(java.util.Locale.ROOT))) {
                throw new OlapException("La dirección de orden debe ser asc o desc");
            }
        }
        if (subtotales && !columnas.isEmpty()) {
            throw new OlapException("Los subtotales no se combinan con columnas (tabla dinámica)");
        }
    }

    /** Filas y columnas juntas */
    public List<String> atributos() {
        List<String> todos = new ArrayList<>(filas == null ? List.of() : filas);
        if (columnas != null) {
            todos.addAll(columnas);
        }
        return todos;
    }

    public Contexto conFilas(List<String> nuevasFilas) {
        return new Contexto(nuevasFilas, columnas, medidas, filtros, orden, limite, subtotales, usarAgregados);
    }

    public Contexto conEjes(List<String> nuevasFilas, List<String> nuevasColumnas) {
        return new Contexto(nuevasFilas, nuevasColumnas, medidas, filtros, orden, limite, subtotales, usarAgregados);
    }
 
    public Contexto conFiltroIgual(String atributo, String valor) {
        List<Filtro> nuevos = new ArrayList<>();
        for (Filtro f : filtros == null ? List.<Filtro>of() : filtros) {
            if (!f.atributo().equals(atributo)) {
                nuevos.add(f);
            }
        }
        nuevos.add(new Filtro(atributo, List.of(valor), null, null));
        return new Contexto(filas, columnas, medidas, nuevos, orden, limite, subtotales, usarAgregados);
    }
}
