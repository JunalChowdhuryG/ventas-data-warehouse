package pe.ventasdw.generator;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ventasdw.generator.core.GeneradorVentas;

/** Disparo manual del flujo diario, útil para demos y para probar la carga incremental del ETL. */
@RestController
@RequestMapping("/api/v1/generador")
public class GeneradorController {

    private final GeneradorVentas generador;

    public GeneradorController(GeneradorVentas generador) {
        this.generador = generador;
    }

    /** POST /api/v1/generador/flujo-diario?fecha=2026-10-03 (sin fecha usa hoy, hora de Lima). */
    @PostMapping("/flujo-diario")
    public GeneradorVentas.Resumen flujoDiario(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        LocalDate dia = fecha != null ? fecha : LocalDate.now(ZoneId.of("America/Lima"));
        return generador.generarDia(dia);
    }
}
