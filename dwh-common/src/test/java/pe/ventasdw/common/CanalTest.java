package pe.ventasdw.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanalTest {

    @Test
    void resuelveCodigosIgnorandoMayusculasYEspacios() {
        assertThat(Canal.desdeCodigo(" online ")).isEqualTo(Canal.ONLINE);
        assertThat(Canal.desdeCodigo("TIENDA")).isEqualTo(Canal.TIENDA);
    }

    @Test
    void codigosNulosOInexistentesSonDesconocido() {
        assertThat(Canal.desdeCodigo(null)).isEqualTo(Canal.DESCONOCIDO);
        assertThat(Canal.desdeCodigo("MARKETPLACE")).isEqualTo(Canal.DESCONOCIDO);
    }

    @Test
    void lasClavesCoincidenConDimCanal() {
        assertThat(Canal.TIENDA.clave()).isEqualTo((short) 1);
        assertThat(Canal.ONLINE.clave()).isEqualTo((short) 2);
        assertThat(Canal.DESCONOCIDO.clave()).isEqualTo((short) -1);
    }
}
