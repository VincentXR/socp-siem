import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Parse request signatures without resolving dependencies or running annotation processors. */
public class RequestBodyContracts {
    private static final Map<String, String> MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE");

    public static void main(String[] paths) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Request contracts require a JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"),
                    null, files.getJavaFileObjects(paths));
            var units = task.parse();
            for (var unit : units) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        var bodies = method.getParameters().stream()
                                .filter(parameter -> annotation(parameter.getModifiers().getAnnotations(),
                                        "RequestBody") != null).toList();
                        if (bodies.isEmpty()) return null;
                        var mappings = method.getModifiers().getAnnotations().stream()
                                .filter(value -> MAPPINGS.containsKey(simple(value.getAnnotationType())))
                                .toList();
                        if (mappings.size() != 1 || bodies.size() != 1) {
                            throw new IllegalArgumentException("Expected one HTTP mapping and request body: "
                                    + unit.getSourceFile().getName() + ":" + method.getName());
                        }
                        var mapping = mappings.get(0);
                        var paths = literalValues(argument(mapping, "value", argument(mapping, "path", null)));
                        if (paths.isEmpty()) throw new IllegalArgumentException("Missing literal HTTP path");
                        var parameter = bodies.get(0);
                        var body = annotation(parameter.getModifiers().getAnnotations(), "RequestBody");
                        var required = argument(body, "required", null);
                        boolean isRequired = required == null || Boolean.parseBoolean(literal(required));
                        boolean valid = annotation(parameter.getModifiers().getAnnotations(), "Valid") != null;
                        Tree type = parameter.getType();
                        if (type instanceof ParameterizedTypeTree generic) type = generic.getType();
                        for (var path : paths) {
                            System.out.println(MAPPINGS.get(simple(mapping.getAnnotationType())) + "\t" + path
                                    + "\t" + simple(type) + "\t" + valid + "\t" + isRequired);
                        }
                        return null;
                    }
                }.scan(unit, null);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalArgumentException("Invalid Java source: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String simple(Tree type) {
        String name = type.toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static AnnotationTree annotation(List<? extends AnnotationTree> annotations, String name) {
        return annotations.stream().filter(value -> simple(value.getAnnotationType()).equals(name))
                .findFirst().orElse(null);
    }

    private static ExpressionTree argument(AnnotationTree annotation, String name, ExpressionTree fallback) {
        for (var value : annotation.getArguments()) {
            if (value instanceof AssignmentTree assignment) {
                if (assignment.getVariable().toString().equals(name)) return assignment.getExpression();
            } else if (name.equals("value")) return value;
        }
        return fallback;
    }

    private static List<String> literalValues(ExpressionTree value) {
        if (value == null) return List.of();
        if (value instanceof NewArrayTree array) {
            var result = new ArrayList<String>();
            for (var item : array.getInitializers()) result.add(literal(item));
            return result;
        }
        return List.of(literal(value));
    }

    private static String literal(ExpressionTree expression) {
        if (!(expression instanceof LiteralTree value) || value.getValue() == null) {
            throw new IllegalArgumentException("HTTP mapping/body options must be literal: " + expression);
        }
        return value.getValue().toString();
    }
}
