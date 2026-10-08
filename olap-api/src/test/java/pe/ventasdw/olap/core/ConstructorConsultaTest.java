package pe.ventasdw.olap.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstructorConsultaTest {

    private static ConstructorConsulta.Consulta sql(List<String> filas, List<String> medidas, List<Contexto.Filtro> filtros, boolean subtotales, boolean agregados) {
        return ConstructorConsulta.construir(new Contexto(filas, null, medidas, filtros, null, null, subtotales, agregados).normalizado());
    }

    @Test
    void soloUneLasDimensionesQueNecesita() {
        var q = sql(List.of("canal.canal"), List.of("ventas", "lineas"), null, false, false);
        assertThat(q.sql()).contains("FROM dwh.fact_ventas f JOIN dwh.dim_canal k ON k.canal_key = f.canal_key");
        assertThat(q.sql()).doesNotContain("dim_fecha").doesNotContain("dim_producto");
        assertThat(q.origen()).isEqualTo("fact_ventas");
    }

    @Test
    void unFiltroTambienExigeLaUnionDeSuDimension() {
        var q = sql(List.of("canal.canal"), List.of("lineas"), List.of(new Contexto.Filtro("fecha.anio", List.of("2026"), null, null)), false, false);
        assertThat(q.sql()).contains("JOIN dwh.dim_fecha d ON d.fecha_key = f.fecha_key").contains("d.anio IN (?)");
    }

    @Test
    void usaElAgregadoSoloCuandoEsEquivalente() {
        assertThat(sql(List.of("fecha.anio", "producto.categoria"), List.of("ventas", "unidades"), null, false, true).origen())
                .isEqualTo("mv_ventas_mensual_categoria");
        assertThat(sql(List.of("geografia.departamento"), List.of("ventas"),
                List.of(new Contexto.Filtro("canal.canal", List.of("Online"), null, null)), false, true).origen())
                .isEqualTo("mv_ventas_mensual_departamento");
        assertThat(sql(List.of("fecha.anio"), List.of("pedidos"), null, false, true).origen()).isEqualTo("fact_ventas");
        assertThat(sql(List.of("producto.categoria", "geografia.departamento"), List.of("ventas"), null, false, true).origen()).isEqualTo("fact_ventas");
        assertThat(sql(List.of("fecha.anio"), List.of("ventas"), null, false, false).origen()).isEqualTo("fact_ventas");
        assertThat(sql(List.of("fecha.anio"), List.of("ventas"), List.of(new Contexto.Filtro("fecha.trimestre", List.of("1"), null, null)), false, true).origen())
                .isEqualTo("fact_ventas");
    }

    @Test
    void losValoresDeLosFiltrosNuncaVanEnElTextoDelSql() {
        String malicioso = "x'; DROP TABLE dwh.fact_ventas; --";
        var q = sql(List.of("producto.categoria"), List.of("ventas"), List.of(new Contexto.Filtro("producto.categoria", List.of(malicioso, "Snacks"), null, null)), false, false);
        assertThat(q.sql()).doesNotContain("DROP").doesNotContain("Snacks").contains("p.categoria IN (?, ?)");
        assertThat(q.parametros()).containsExactly(malicioso, "Snacks");
    }

    @Test
    void convierteLosParametrosAlTipoDelAtributo() {
        var q = sql(List.of("fecha.anio"), List.of("ventas"), List.of(
                new Contexto.Filtro("fecha.anio", List.of("2025", "2026"), null, null),
                new Contexto.Filtro("fecha.dia", null, "2026-01-01", "2026-03-31")), false, false);
        assertThat(q.parametros()).containsExactly(2025, 2026, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertThat(q.sql()).contains("d.fecha >= ?").contains("d.fecha <= ?");
    }

    @Test
    void agregaRollupYGroupingCuandoSePidenSubtotales() {
        var q = sql(List.of("fecha.anio", "canal.canal"), List.of("ventas"), null, true, false);
        assertThat(q.sql()).contains("GROUP BY ROLLUP (d.anio, k.canal)").contains("GROUPING(d.anio) AS g0").contains("GROUPING(k.canal) AS g1");
        assertThat(q.conSubtotales()).isTrue();
    }

    @Test
    void ordenaPorLosAtributosPorDefectoYAplicaElLimiteMasUno() {
        var q = sql(List.of("fecha.anio", "canal.canal"), List.of("ventas"), null, false, false);
        assertThat(q.sql()).contains("ORDER BY a0, a1").endsWith("LIMIT 1001");
        var conOrden = ConstructorConsulta.construir(new Contexto(List.of("producto.producto"), null, List.of("ventas"), null,
                List.of(new Contexto.Orden("ventas", "desc")), 3, null, false).normalizado());
        assertThat(conOrden.sql()).contains("ORDER BY m0 DESC").endsWith("LIMIT 4");
    }

    @Test
    void sinAtributosCalculaElTotalGeneral() {
        var q = sql(List.of(), List.of("ventas", "pedidos"), null, false, false);
        assertThat(q.sql()).doesNotContain("GROUP BY").doesNotContain("ORDER BY").doesNotContain("JOIN");
    }

    @Test
    void unValorDeTipoIncorrectoSeRechazaAntesDeEjecutar() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sql(List.of(), List.of("ventas"), List.of(new Contexto.Filtro("fecha.anio", List.of("dos mil"), null, null)), false, false))
                .isInstanceOf(OlapException.class).hasMessageContaining("fecha.anio");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sql(List.of(), List.of("ventas"), List.of(new Contexto.Filtro("fecha.dia", List.of("ayer"), null, null)), false, false))
                .isInstanceOf(OlapException.class);
    }
}
