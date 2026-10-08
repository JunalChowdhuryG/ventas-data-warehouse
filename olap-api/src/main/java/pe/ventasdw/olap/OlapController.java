package pe.ventasdw.olap;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ventasdw.olap.core.Contexto;
import pe.ventasdw.olap.core.CuboVentas;
import pe.ventasdw.olap.core.Json;
import pe.ventasdw.olap.core.OlapException;
import pe.ventasdw.olap.core.OlapService;
import pe.ventasdw.olap.core.Resultado;

 
@RestController
@RequestMapping("/api/v1/olap")
public class OlapController {

    private final OlapService olap;
    private final CacheOlap cache;
    private final MeterRegistry metricas;

    public OlapController(OlapService olap, CacheOlap cache, MeterRegistry metricas) {
        this.olap = olap;
        this.cache = cache;
        this.metricas = metricas;
    }

    //   solicitudes

    public record DrillSolicitud(Contexto contexto, String dimension, Map<String, String> miembro) {
    }

    public record AtributoSolicitud(Contexto contexto, String atributo) {
    }

    public record PivotSolicitud(Contexto contexto, List<String> filas, List<String> columnas) {
    }

    public record PaginaSolicitud(Contexto contexto, String atributo, String valor) {
    }

    //   operaciones

    @PostMapping(value = "/consulta", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> consulta(@RequestBody Contexto contexto,
                                           @RequestParam(defaultValue = "false") boolean sql) {
        Contexto c = contexto.normalizado();
        return responder("consulta", c, sql, () -> olap.consultar(c));
    }

    @PostMapping(value = "/drill-down", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> drillDown(@RequestBody DrillSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("drill-down", s, sql, () -> olap.drillDown(requerido(s.contexto()), s.dimension(), s.miembro()));
    }

    @PostMapping(value = "/drill-up", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> drillUp(@RequestBody DrillSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("drill-up", s, sql, () -> olap.drillUp(requerido(s.contexto()), s.dimension()));
    }

    @PostMapping(value = "/drill-across", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> drillAcross(@RequestBody AtributoSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("drill-across", s, sql, () -> olap.drillAcross(requerido(s.contexto()), s.atributo()));
    }

    @PostMapping(value = "/roll-across", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rollAcross(@RequestBody AtributoSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("roll-across", s, sql, () -> olap.rollAcross(requerido(s.contexto()), s.atributo()));
    }

    @PostMapping(value = "/pivot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pivot(@RequestBody PivotSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("pivot", s, sql, () -> olap.pivot(requerido(s.contexto()), s.filas(), s.columnas()));
    }

    @PostMapping(value = "/page", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> page(@RequestBody PaginaSolicitud s, @RequestParam(defaultValue = "false") boolean sql) {
        return responder("page", s, sql, () -> olap.page(requerido(s.contexto()), s.atributo(), s.valor()));
    }

    //   metadatos
 
    @GetMapping(value = "/metadatos", produces = MediaType.APPLICATION_JSON_VALUE)
    public String metadatos() {
        List<Object> dimensiones = new java.util.ArrayList<>();
        CuboVentas.dimensiones().values().forEach(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id());
            dm.put("etiqueta", d.etiqueta());
            List<Object> niveles = new java.util.ArrayList<>();
            for (String id : d.jerarquia()) {
                CuboVentas.Atributo a = CuboVentas.atributo(id);
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("atributo", a.id());
                am.put("etiqueta", a.etiqueta());
                am.put("tipo", a.tipo());
                niveles.add(am);
            }
            dm.put("jerarquia", niveles);
            dimensiones.add(dm);
        });
        List<Object> medidas = new java.util.ArrayList<>();
        CuboVentas.medidas().values().forEach(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id());
            mm.put("etiqueta", m.etiqueta());
            mm.put("descripcion", m.descripcion());
            mm.put("aditiva", m.aditiva());
            medidas.add(mm);
        });
        List<Object> agregados = new java.util.ArrayList<>();
        CuboVentas.agregados().forEach(a -> {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("vista", a.tabla());
            gm.put("atributos", a.columnasPorAtributo().keySet().stream().sorted().toList());
            gm.put("medidas", a.medidasSql().keySet().stream().sorted().toList());
            agregados.add(gm);
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cubo", "ventas");
        m.put("dimensiones", dimensiones);
        m.put("medidas", medidas);
        m.put("agregados", agregados);
        return Json.escribir(m);
    }
 
    @GetMapping(value = "/metadatos/valores", produces = MediaType.APPLICATION_JSON_VALUE)
    public String valores(@RequestParam String atributo, @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "100") int limite) {
        return Json.escribir(Map.of("atributo", atributo, "valores", olap.valoresDe(atributo, q, limite)));
    }

    //   errores

    @ExceptionHandler(OlapException.class)
    public ResponseEntity<String> solicitudInvalida(OlapException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> falloInterno(IllegalStateException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private static ResponseEntity<String> error(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_JSON)
                .body(Json.escribir(Map.of("error", String.valueOf(mensaje))));
    }

    //   comunes

    private static Contexto requerido(Contexto c) {
        if (c == null) {
            throw new OlapException("Falta el contexto de la consulta");
        }
        return c;
    }
 
    private ResponseEntity<String> responder(String operacion, Object solicitud, boolean incluirSql, Supplier<Resultado> calculo) {
        String clave = cache.activa() && !incluirSql ? cache.clave(operacion, solicitud) : null;
        if (clave != null) {
            String guardado = cache.obtener(clave);
            if (guardado != null) {
                metricas.counter("olap.cache.aciertos", "operacion", operacion).increment();
                return ResponseEntity.ok().header("X-Cache", "HIT").contentType(MediaType.APPLICATION_JSON).body(guardado);
            }
            metricas.counter("olap.cache.fallos", "operacion", operacion).increment();
        }
        long inicio = System.nanoTime();
        Resultado r = calculo.get();
        metricas.timer("olap.consulta.duracion", "operacion", operacion, "origen", r.origen())
                .record(Duration.ofNanos(System.nanoTime() - inicio));
        metricas.counter("olap.consultas", "operacion", operacion).increment();
        String json = r.aJson(incluirSql);
        if (clave != null) {
            cache.guardar(clave, json);
        }
        return ResponseEntity.ok().header("X-Cache", clave != null ? "MISS" : "BYPASS").contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
