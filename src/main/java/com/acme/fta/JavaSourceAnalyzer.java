package com.acme.fta;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class JavaSourceAnalyzer {
    private final DebugLogger debug;

    public JavaSourceAnalyzer(DebugLogger debug) {
        this.debug = debug;
    }

    public RangeMatch findBestRange(Path sourceFile, String fqcn, String testName) throws IOException {
        debug.log("parsing Java source: %s", sourceFile);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is not available. Run on a full JDK, not a JRE.");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(sourceFile.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null,
                    List.of("-proc:none", "-XDshould-stop.at=FLOW"), null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            CompilationUnitTree unit = parsed.iterator().next();
            Trees trees = Trees.instance(task);

            String normalizedRequestedFqcn = fqcn.replace('$', '.');
            Collector collector = new Collector(trees, unit, normalizedRequestedFqcn);
            collector.scan(unit, null);
            RangeMatch match = collector.pickBestMatch(testName);
            if (match == null) {
                debug.log("no source range match for %s#%s", fqcn, testName);
            } else {
                debug.log("source range match for %s#%s => scope=%s confidence=%s lines=%d-%d matchedName=%s methods=%d",
                        fqcn,
                        testName,
                        match.scope(),
                        match.confidence(),
                        match.startLine(),
                        match.endLine(),
                        match.matchedName(),
                        collector.methodCount());
            }
            return match;
        }
    }

    public enum Scope {
        METHOD,
        CLASS
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public record RangeMatch(int startLine, int endLine, Scope scope, Confidence confidence, String matchedName) {
    }

    private static final class Collector extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final CompilationUnitTree unit;
        private final String requestedFqcn;
        private final Deque<String> classStack = new ArrayDeque<>();
        private final List<MethodEntry> methods = new ArrayList<>();
        private RangeMatch classRange;
        private boolean insideTargetClass;

        private Collector(Trees trees, CompilationUnitTree unit, String requestedFqcn) {
            this.trees = trees;
            this.unit = unit;
            this.requestedFqcn = requestedFqcn;
        }

        private int methodCount() {
            return methods.size();
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            classStack.addLast(node.getSimpleName().toString());
            boolean previousInsideTargetClass = insideTargetClass;

            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            String fqcn = buildFqcn(pkg, classStack);
            if (requestedFqcn.equals(fqcn)) {
                insideTargetClass = true;
                long start = trees.getSourcePositions().getStartPosition(unit, node);
                long end = trees.getSourcePositions().getEndPosition(unit, node);
                classRange = new RangeMatch(
                        (int) unit.getLineMap().getLineNumber(start),
                        (int) unit.getLineMap().getLineNumber(end),
                        Scope.CLASS,
                        Confidence.LOW,
                        node.getSimpleName().toString());
            }

            super.visitClass(node, unused);
            insideTargetClass = previousInsideTargetClass;
            classStack.removeLast();
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (insideTargetClass) {
                long start = trees.getSourcePositions().getStartPosition(unit, node);
                long end = trees.getSourcePositions().getEndPosition(unit, node);
                if (start >= 0 && end >= 0) {
                    String displayName = extractDisplayName(node.getModifiers().getAnnotations());
                    methods.add(new MethodEntry(
                            node.getName().toString(),
                            displayName,
                            (int) unit.getLineMap().getLineNumber(start),
                            (int) unit.getLineMap().getLineNumber(end)));
                }
            }
            return super.visitMethod(node, unused);
        }

        private String buildFqcn(String pkg, Deque<String> classStack) {
            String joined = String.join(".", classStack);
            if (pkg == null || pkg.isBlank()) {
                return joined;
            }
            return pkg + "." + joined;
        }

        private RangeMatch pickBestMatch(String testName) {
            if (methods.isEmpty()) {
                return classRange;
            }

            String trimmed = testName == null ? "" : testName.trim();
            String normalized = normalize(trimmed);

            for (MethodEntry method : methods) {
                if (trimmed.equals(method.methodName()) || trimmed.equals(method.methodName() + "()") || trimmed.equals(method.displayName())) {
                    return method.toRangeMatch(Scope.METHOD, Confidence.HIGH, method.displayName() != null && trimmed.equals(method.displayName()) ? method.displayName() : method.methodName());
                }
            }

            for (MethodEntry method : methods) {
                if (normalized.equals(normalize(method.methodName())) || normalized.equals(normalize(method.displayName()))) {
                    return method.toRangeMatch(Scope.METHOD, Confidence.MEDIUM, method.displayName() != null ? method.displayName() : method.methodName());
                }
            }

            return classRange;
        }

        private String extractDisplayName(List<? extends AnnotationTree> annotations) {
            for (AnnotationTree annotation : annotations) {
                String type = annotation.getAnnotationType().toString();
                if (!type.endsWith("DisplayName")) {
                    continue;
                }
                for (ExpressionTree arg : annotation.getArguments()) {
                    if (arg instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                        return value;
                    }
                    if (arg instanceof AssignmentTree assignment && assignment.getExpression() instanceof LiteralTree literal
                            && literal.getValue() instanceof String value) {
                        return value;
                    }
                }
            }
            return null;
        }

        private String normalize(String value) {
            if (value == null) {
                return "";
            }
            String s = value.trim();
            s = s.replaceAll("\\[[^]]*]$", "");
            s = s.replaceAll("\\(.*\\)$", "");
            s = s.replaceAll("\\s+", " ");
            return s.toLowerCase(Locale.ROOT).trim();
        }
    }

    private record MethodEntry(String methodName, String displayName, int startLine, int endLine) {
        private RangeMatch toRangeMatch(Scope scope, Confidence confidence, String matchedName) {
            return new RangeMatch(startLine, endLine, scope, confidence, matchedName);
        }
    }
}
