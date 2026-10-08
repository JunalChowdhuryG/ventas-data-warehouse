package pe.ventasdw.olap.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escribeTiposBasicosYNulos() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("texto", "hola");
        m.put("entero", 7);
        m.put("largo", 9_000_000_000L);
        m.put("booleano", true);
        m.put("nulo", null);
        m.put("fecha", LocalDate.of(2026, 3, 10));
        assertThat(Json.escribir(m))
                .isEqualTo("{\"texto\":\"hola\",\"entero\":7,\"largo\":9000000000,\"booleano\":true,\"nulo\":null,\"fecha\":\"2026-03-10\"}");
    }

    @Test
    void losDecimalesSeEscribenSinNotacionCientifica() {
        assertThat(Json.escribir(new BigDecimal("1.0E+3"))).isEqualTo("1000");
        assertThat(Json.escribir(new BigDecimal("12345678.90"))).isEqualTo("12345678.90");
        assertThat(Json.escribir(Double.NaN)).isEqualTo("null");
    }

    @Test
    void escapaComillasBarrasSaltosYCaracteresDeControl() {
        assertThat(Json.escribir("a\"b\\c\nd\te\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001\"");
    }

    @Test
    void conservaTildesYEnies() {
        assertThat(Json.escribir("Año Áncash Perú")).isEqualTo("\"Año Áncash Perú\"");
    }

    @Test
    void escribeListasYMapasAnidados() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", List.of(1, 2, List.of("x")));
        m.put("b", Map.of("k", Arrays.asList(1, null)));
        assertThat(Json.escribir(m)).isEqualTo("{\"a\":[1,2,[\"x\"]],\"b\":{\"k\":[1,null]}}");
    }
}
