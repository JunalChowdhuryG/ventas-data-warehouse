package pe.ventasdw.etl;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import pe.ventasdw.etl.core.EtlResultado;

 
@Component
public class EtlMetricas {

    private final MeterRegistry registro;
    private final AtomicLong ultimaExitosa = new AtomicLong(0);

    public EtlMetricas(MeterRegistry registro) {
        this.registro = registro;
        Gauge.builder("etl.ultima.ejecucion.exitosa.timestamp", ultimaExitosa, AtomicLong::get)
                .description("Instante (segundos desde 1970) de la ultima ejecucion exitosa del ETL")
                .register(registro);
    }

    public void registrar(EtlResultado r) {
        registro.counter("etl.ejecuciones", "estado", r.estado(), "tipo", r.tipoCarga()).increment();
        registro.counter("etl.filas.extraidas").increment(r.filasExtraidas());
        registro.counter("etl.filas.cargadas").increment(r.filasCargadas());
        registro.counter("etl.filas.excluidas").increment(r.filasExcluidas());
        r.excepcionesPorRegla().forEach((claveRegla, n) -> {
            // La clave llega como "REGLA (SEVERIDAD)"
            int i = claveRegla.indexOf(" (");
            String regla = i > 0 ? claveRegla.substring(0, i) : claveRegla;
            String severidad = i > 0 ? claveRegla.substring(i + 2, claveRegla.length() - 1) : "";
            registro.counter("etl.excepciones", "regla", regla, "severidad", severidad).increment(n);
        });
        r.duracionesMs().forEach((fase, ms) -> registro.timer("etl.duracion", "fase", fase).record(Duration.ofMillis(ms)));
        ultimaExitosa.set(Instant.now().getEpochSecond());
    }

    public void registrarFallo() {
        registro.counter("etl.ejecuciones", "estado", "FALLIDA", "tipo", "DESCONOCIDO").increment();
    }
}
