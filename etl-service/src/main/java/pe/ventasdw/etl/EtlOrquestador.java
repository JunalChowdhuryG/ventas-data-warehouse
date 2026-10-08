package pe.ventasdw.etl;

import org.springframework.stereotype.Service;
import pe.ventasdw.etl.core.EtlResultado;
import pe.ventasdw.etl.core.EtlService;

/** Punto único de entrada para ejecutar el ETL (API y scheduler): corre el proceso y registra las métricas. */
@Service
public class EtlOrquestador {

    private final EtlService etl;
    private final EtlMetricas metricas;

    public EtlOrquestador(EtlService etl, EtlMetricas metricas) {
        this.etl = etl;
        this.metricas = metricas;
    }

    public EtlResultado ejecutar(EtlService.Modo modo) {
        try {
            EtlResultado resultado = etl.ejecutar(modo);
            metricas.registrar(resultado);
            return resultado;
        } catch (RuntimeException e) {
            metricas.registrarFallo();
            throw e;
        }
    }
}
