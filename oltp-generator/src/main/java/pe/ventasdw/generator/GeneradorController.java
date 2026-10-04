package pe.ventasdw.generator;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ventasdw.generator.core.GeneradorVentas;

@RestController
@RequestMapping("/api/v1/generador")
public class GeneradorController {

    private final GeneradorVentas generador;

    public GeneradorController(GeneradorVentas generador) {
        this.generador = generador;
    }

    @PostMapping("/flujo-diario")
    public GeneradorVentas.Resumen flujoDiario(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        LocalDate dia = fecha != null ? fecha : LocalDate.now(ZoneId.of("America/Lima"));
        return generador.generarDia(dia);
    }
}
