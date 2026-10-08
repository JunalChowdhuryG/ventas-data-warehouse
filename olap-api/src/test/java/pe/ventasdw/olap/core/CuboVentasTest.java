package pe.ventasdw.olap.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
 
class CuboVentasTest {

    @Test
    void cadaNivelDeCadaJerarquiaEsUnAtributoDeSuDimension() {
        CuboVentas.dimensiones().values().forEach(d -> {
            assertThat(d.jerarquia()).isNotEmpty();
            d.jerarquia().forEach(id -> assertThat(CuboVentas.atributo(id).dimension()).isEqualTo(d.id()));
        });
    }

    @Test
    void todoAtributoPerteneceAUnaJerarquia() {
        Set<String> enJerarquias = new HashSet<>();
        CuboVentas.dimensiones().values().forEach(d -> enJerarquias.addAll(d.jerarquia()));
        assertThat(enJerarquias).containsExactlyInAnyOrderElementsOf(CuboVentas.atributos().keySet());
    }

    @Test
    void losAliasDeLasTablasDeDimensionSonUnicosYLasExpresionesLosUsan() {
        Set<String> alias = new HashSet<>();
        CuboVentas.dimensiones().values().forEach(d -> assertThat(alias.add(d.alias())).as("alias " + d.alias()).isTrue());
        CuboVentas.atributos().values().forEach(a ->
                assertThat(a.expresion()).startsWith(CuboVentas.dimension(a.dimension()).alias() + "."));
    }

    @Test
    void losAgregadosSoloUsanAtributosYMedidasAditivasDelCubo() {
        CuboVentas.agregados().forEach(ag -> {
            ag.columnasPorAtributo().keySet().forEach(CuboVentas::atributo);
            ag.medidasSql().keySet().forEach(m -> assertThat(CuboVentas.medida(m).aditiva()).as("medida " + m).isTrue());
        });
    }

    @Test
    void lasMedidasSonUnicasYLasDesconocidasSeRechazan() {
        assertThat(CuboVentas.medidas()).containsKeys("ventas", "unidades", "pedidos", "lineas", "ticket_promedio");
        assertThatThrownBy(() -> CuboVentas.medida("margen")).isInstanceOf(OlapException.class).hasMessageContaining("Medida desconocida");
        assertThatThrownBy(() -> CuboVentas.atributo("fecha.siglo")).isInstanceOf(OlapException.class).hasMessageContaining("Atributo desconocido");
        assertThatThrownBy(() -> CuboVentas.dimension("tienda")).isInstanceOf(OlapException.class);
    }
}
