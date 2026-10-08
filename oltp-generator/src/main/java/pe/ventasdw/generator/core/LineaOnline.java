package pe.ventasdw.generator.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Una fila del CSV del canal online. Los campos pueden ser nulos para simular datos sucios. */
public record LineaOnline(
        long pedidoOnlineId,
        int linea,
        LocalDate fechaPedido,
        String estado,
        int clienteId,
        String razonSocial,
        String email,
        int ciudadId,
        Integer productoId,
        String descripcion,
        String categoria,
        Integer cantidad,
        BigDecimal precioUnitario,
        BigDecimal descuento,
        String moneda,
        OffsetDateTime updatedAt) {
}
