package pe.ventasdw.generator.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModeloVentasTest {

    @Test
    void diciembreVendeMasQueFebreroYElFinDeSemanaMasQueElLunes() {
        LocalDate sabadoDiciembre = LocalDate.of(2026, 12, 12);
        LocalDate lunesFebrero = LocalDate.of(2026, 2, 9);
        assertThat(ModeloVentas.factorEstacional(sabadoDiciembre))
                .isGreaterThan(ModeloVentas.factorEstacional(lunesFebrero));
        assertThat(ModeloVentas.factorEstacional(LocalDate.of(2026, 3, 14)))   // sábado
                .isGreaterThan(ModeloVentas.factorEstacional(LocalDate.of(2026, 3, 16)));  // lunes
    }

    @Test
    void siempreHayAlMenosUnPedidoPorDia() {
        Random r = new Random(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(ModeloVentas.pedidosDelDia(r, 1, LocalDate.of(2026, 2, 9))).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void elVolumenPromedioSigueLaBaseYLaEstacionalidad() {
        Random r = new Random(7);
        LocalDate dia = LocalDate.of(2026, 12, 15);   // martes de diciembre
        double suma = 0;
        int n = 2_000;
        for (int i = 0; i < n; i++) {
            suma += ModeloVentas.pedidosDelDia(r, 150, dia);
        }
        double esperado = 150 * ModeloVentas.factorEstacional(dia);
        assertThat(suma / n).isBetween(esperado * 0.97, esperado * 1.03);
    }

    @Test
    void lasVariantesDeEntregadoSeNormalizanAUnSoloCodigoEnElEtl() {
        Random r = new Random(3);
        Set<String> vistas = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            vistas.add(ModeloVentas.estadoEntregado(r));
        }
        assertThat(vistas).containsExactlyInAnyOrder("ENTREGADO", "ENT", "E", "Entregado");
    }

    @Test
    void descuentosYCantidadesEstanEnLosValoresEsperados() {
        Random r = new Random(5);
        for (int i = 0; i < 500; i++) {
            assertThat(ModeloVentas.descuento(r)).isIn(0.0, 0.05, 0.10, 0.15, 0.20);
            assertThat(ModeloVentas.cantidad(r)).isIn(1, 2, 3, 4, 6, 12);
            assertThat(ModeloVentas.lineasPorPedido(r)).isBetween(1, 6);
        }
    }
}
