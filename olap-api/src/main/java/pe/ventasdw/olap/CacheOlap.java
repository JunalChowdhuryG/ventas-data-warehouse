package pe.ventasdw.olap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

 
@Component
public class CacheOlap {

    private static final Logger LOG = LoggerFactory.getLogger(CacheOlap.class);
    private static final long VIGENCIA_VERSION_MS = 5_000;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final OlapProperties props;

    private volatile long versionCache = 0;
    private final AtomicLong versionLeidaEn = new AtomicLong(0);

    public CacheOlap(StringRedisTemplate redis, JdbcTemplate jdbc, OlapProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
    }

    public boolean activa() {
        return props.cacheActiva();
    }

    /** Clave estable para una solicitud: operación, última carga del ETL y huella de la solicitud normalizada. */
    public String clave(String operacion, Object solicitudNormalizada) {
        return "olap:" + operacion + ":" + versionDatos() + ":" + sha256(String.valueOf(solicitudNormalizada));
    }

    public String obtener(String clave) {
        try {
            return redis.opsForValue().get(clave);
        } catch (RuntimeException e) {
            LOG.warn("Redis no disponible al leer la caché: {}", e.getMessage());
            return null;
        }
    }

    public void guardar(String clave, String json) {
        try {
            redis.opsForValue().set(clave, json, Duration.ofSeconds(props.cacheTtlSegundos()));
        } catch (RuntimeException e) {
            LOG.warn("Redis no disponible al guardar en la caché: {}", e.getMessage());
        }
    }

    /** Identificador de la última carga exitosa del ETL; se relee cada pocos segundos. */
    long versionDatos() {
        long ahora = System.currentTimeMillis();
        if (ahora - versionLeidaEn.get() > VIGENCIA_VERSION_MS) {
            try {
                Long v = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM etl.ejecucion WHERE estado = 'EXITOSA'", Long.class);
                versionCache = v == null ? 0 : v;
            } catch (RuntimeException e) {
                LOG.warn("No se pudo leer la versión de datos del ETL: {}", e.getMessage());
            }
            versionLeidaEn.set(ahora);
        }
        return versionCache;
    }

    private static String sha256(String texto) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
