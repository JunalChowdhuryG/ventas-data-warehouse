package pe.ventasdw.etl.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lector CSV minimo (RFC 4180) 
 */
public final class CsvLector {

    private CsvLector() {
    } 
    public static void leer(Path archivo, Consumer<String[]> alRegistro) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            StringBuilder celda = new StringBuilder();
            List<String> fila = new ArrayList<>();
            boolean entreComillas = false;
            boolean celdaConComillas = false;
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (entreComillas) {
                    if (ch == '"') {
                        r.mark(1);
                        int sig = r.read();
                        if (sig == '"') {
                            celda.append('"');
                        } else {
                            entreComillas = false;
                            if (sig != -1) {
                                r.reset();
                            }
                        }
                    } else {
                        celda.append(ch);
                    }
                    continue;
                }
                switch (ch) {
                    case '"' -> {
                        entreComillas = true;
                        celdaConComillas = true;
                    }
                    case ',' -> {
                        fila.add(celda.toString());
                        celda.setLength(0);
                        celdaConComillas = false;
                    }
                    case '\r' -> {  }
                    case '\n' -> {
                        entregar(fila, celda, celdaConComillas, alRegistro);
                        fila = new ArrayList<>();
                        celda.setLength(0);
                        celdaConComillas = false;
                    }
                    default -> celda.append(ch);
                }
            }
            if (entreComillas) {
                throw new IOException("Comillas sin cerrar en " + archivo.getFileName());
            }
            if (celda.length() > 0 || !fila.isEmpty() || celdaConComillas) {
                entregar(fila, celda, celdaConComillas, alRegistro);
            }
        }
    }

    private static void entregar(List<String> fila, StringBuilder celda, boolean conComillas, Consumer<String[]> alRegistro) {
        fila.add(celda.toString());
        boolean vacia = fila.size() == 1 && fila.get(0).isEmpty() && !conComillas;
        if (!vacia) {
            alRegistro.accept(fila.toArray(new String[0]));
        }
    }
}
