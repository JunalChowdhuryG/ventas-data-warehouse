package pe.ventasdw.olap.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextoTest {

    private static Contexto con(List<String> filas, List<String> columnas, List<Contexto.Filtro> filtros) {
        return new Contexto(filas, columnas, null, filtros, null, null, null, null);
    }

    @Test
    void losValoresOmitidosTomanSusDefectos() {
        Contexto c = new Contexto(null, null, null, null, null, null, null, null).normalizado();
        assertThat(c.filas()).isEmpty();
        assertThat(c.columnas()).isEmpty();
        assertThat(c.medidas()).containsExactly("ventas");
        assertThat(c.limite()).isEqualTo(Contexto.LIMITE_POR_DEFECTO);
        assertThat(c.subtotales()).isFalse();
        assertThat(c.usarAgregados()).isTrue();
    }

    @Test
    void rechazaAtributosYMedidasDesconocidos() {
        assertThatThrownBy(() -> con(List.of("fecha.siglo"), null, null).normalizado()).isInstanceOf(OlapException.class);
        assertThatThrownBy(() -> new Contexto(List.of(), null, List.of("margen"), null, null, null, null, null).normalizado())
                .isInstanceOf(OlapException.class);
    }

    @Test
    void unAtributoNoPuedeRepetirseNiEstarEnFilasYColumnas() {
        assertThatThrownBy(() -> con(List.of("fecha.anio", "fecha.anio"), null, null).normalizado()).isInstanceOf(OlapException.class);
        assertThatThrownBy(() -> con(List.of("fecha.anio"), List.of("fecha.anio"), null).normalizado()).isInstanceOf(OlapException.class);
    }

    @Test
    void elFiltroDebeTenerValoresOUnRangoPeroNoAmbos() {
        var vacio = new Contexto.Filtro("fecha.anio", null, null, null);
        var ambos = new Contexto.Filtro("fecha.anio", List.of("2026"), "2025", null);
        assertThatThrownBy(() -> con(List.of(), null, List.of(vacio)).normalizado()).isInstanceOf(OlapException.class);
        assertThatThrownBy(() -> con(List.of(), null, List.of(ambos)).normalizado()).isInstanceOf(OlapException.class);
        con(List.of(), null, List.of(new Contexto.Filtro("fecha.anio", null, "2025", "2026"))).normalizado();
    }

    @Test
    void validaLimiteOrdenYSubtotales() {
        assertThatThrownBy(() -> new Contexto(null, null, null, null, null, 0, null, null).normalizado()).isInstanceOf(OlapException.class);
        assertThatThrownBy(() -> new Contexto(null, null, null, null, null, 10_001, null, null).normalizado()).isInstanceOf(OlapException.class);
        var ordenInvalido = List.of(new Contexto.Orden("unidades", "asc"));    // unidades no está entre las medidas
        assertThatThrownBy(() -> new Contexto(List.of("fecha.anio"), null, null, null, ordenInvalido, null, null, null).normalizado())
                .isInstanceOf(OlapException.class).hasMessageContaining("ordenar");
        assertThatThrownBy(() -> new Contexto(List.of("fecha.anio"), List.of("canal.canal"), null, null, null, null, true, null).normalizado())
                .isInstanceOf(OlapException.class).hasMessageContaining("subtotales");
    }

    @Test
    void conFiltroIgualReemplazaElFiltroDelMismoAtributo() {
        Contexto c = con(List.of("fecha.anio"), null,
                List.of(new Contexto.Filtro("fecha.anio", List.of("2025"), null, null), new Contexto.Filtro("canal.canal", List.of("Online"), null, null)))
                .normalizado().conFiltroIgual("fecha.anio", "2026");
        assertThat(c.filtros()).hasSize(2);
        assertThat(c.filtros()).anyMatch(f -> f.atributo().equals("fecha.anio") && f.valores().equals(List.of("2026")));
    }
}
