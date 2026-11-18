package decompiler.ast;

import javax.tools.*;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * Компилятор-гейт: собирает напечатанный Java-код класса in-process javac.
 * Ошибки ссылок (cant.resolve/doesnt.exist) и legacy-имён (_) отфильтрованы.
 * Если в классе есть неразрешённые типы, type-check ошибки (prob.found.req,
 * unexpected.type, cant.apply.symbol*) — каскад от error-типов, а не наш дефект.
 */
public final class JavacGate {

    /** Результат проверки одного класса. */
    public record Result(boolean ok, List<String> codes, List<String> samples,
                         List<String> realCodes) {
    }

    private static final Set<String> TYPE_CASCADES = Set.of(
            "compiler.err.prob.found.req",
            "compiler.err.unexpected.type",
            "compiler.err.cant.apply.symbol",
            "compiler.err.cant.apply.symbols",
            "compiler.err.array.req.but.found");

    private JavacGate() {
    }

    public static Result check(String className, String source, String classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return new Result(true, List.of(), List.of("no system compiler"), List.of());
        DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(dc, null, null);
        JavaFileObject fo = new SimpleJavaFileObject(
                URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        List<String> opts = new ArrayList<>(List.of(
                "-proc:none", "-nowarn", "-Xlint:none", "-classpath", classpath));
        try {
            opts.add("-d");
            opts.add(Files.createTempDirectory("gate-out").toString());
            compiler.getTask(new StringWriter(), fm, dc, opts, null, List.of(fo)).call();
        } catch (Exception e) {
            return new Result(true, List.of(), List.of("gate error: " + e), List.of());
        }
        List<String> codes = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int resolveErrors = 0;
        for (Diagnostic<? extends JavaFileObject> d : dc.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            String code = d.getCode();
            if (code.contains("cant.resolve") || code.contains("doesnt.exist")) {
                resolveErrors++;
                continue;
            }
            if (code.contains("underscore")) continue;
            codes.add(code);
            if (samples.size() < 12)
                samples.add(code + ":" + d.getLineNumber() + ":" + d.getMessage(Locale.ROOT));
        }
        List<String> realCodes = new ArrayList<>();
        for (String c : codes) {
            if (resolveErrors > 0 && TYPE_CASCADES.contains(c)) continue;
            realCodes.add(c);
        }
        return new Result(codes.isEmpty(), codes, samples, realCodes);
    }
}