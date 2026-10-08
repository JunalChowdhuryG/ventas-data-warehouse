package pe.ventasdw.olap.core; 
public class OlapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OlapException(String mensaje) {
        super(mensaje);
    }
}
