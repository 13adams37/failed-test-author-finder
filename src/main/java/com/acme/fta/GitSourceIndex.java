package com.acme.fta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GitSourceIndex {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_$.]+)\\s*;");

    private final Path repo;
    private final Map<String, List<Path>> bySimpleName;
    private final Map<Path, String> packageCache = new HashMap<>();
    private final DebugLogger debug;

    private GitSourceIndex(Path repo, Map<String, List<Path>> bySimpleName, DebugLogger debug) {
        this.repo = repo;
        this.bySimpleName = bySimpleName;
        this.debug = debug;
    }

    public static GitSourceIndex build(Path repo, DebugLogger debug) throws IOException, InterruptedException {
        debug.log("building Java source index");
        List<Path> javaFiles = listJavaFiles(repo, debug);
        debug.log("java files discovered: %d", javaFiles.size());
        Map<String, List<Path>> index = new HashMap<>();
        for (Path relative : javaFiles) {
            String fileName = relative.getFileName().toString();
            String simpleName = fileName.substring(0, fileName.length() - ".java".length());
            index.computeIfAbsent(simpleName, key -> new ArrayList<>()).add(relative);
        }
        debug.log("source index built: unique simple names=%d", index.size());
        return new GitSourceIndex(repo, index, debug);
    }

    public Optional<Path> findSourceFile(String fqcn) {
        String normalized = fqcn.replace('$', '.');
        String simpleName = normalized.substring(normalized.lastIndexOf('.') + 1);
        List<Path> candidates = bySimpleName.getOrDefault(simpleName, List.of());
        debug.log("looking up source file for %s: simpleName=%s candidates=%d", fqcn, simpleName, candidates.size());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        String packageName = packageNameOf(normalized);
        return candidates.stream()
                .sorted(Comparator.comparingInt((Path path) -> scoreCandidate(path, packageName)).reversed())
                .map(repo::resolve)
                .findFirst()
                .map(path -> {
                    debug.log("selected source file for %s: %s", fqcn, path);
                    return path;
                });
    }

    private int scoreCandidate(Path relative, String packageName) {
        int score = 0;
        String unixPath = relative.toString().replace('\\', '/');
        if (unixPath.contains("/src/test/java/") || unixPath.contains("/src/integrationTest/java/") || unixPath.contains("/src/functionalTest/java/")) {
            score += 100;
        }
        if (unixPath.endsWith(packageName.replace('.', '/') + "/" + relative.getFileName())) {
            score += 50;
        }
        String actualPackage = packageCache.computeIfAbsent(relative, this::readPackage);
        if (packageName.equals(actualPackage)) {
            score += 1000;
        }
        return score;
    }

    private String readPackage(Path relative) {
        Path absolute = repo.resolve(relative);
        try {
            String content = Files.readString(absolute, StandardCharsets.UTF_8);
            Matcher matcher = PACKAGE_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException ignored) {
            debug.log("failed to read package from %s: %s", absolute, ignored.getMessage());
        }
        return "";
    }

    private static String packageNameOf(String fqcn) {
        int index = fqcn.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        return fqcn.substring(0, index);
    }

    private static List<Path> listJavaFiles(Path repo, DebugLogger debug) throws IOException, InterruptedException {
        List<String> command = List.of("git", "-C", repo.toString(), "ls-files", "*.java");
        debug.log("listing tracked Java files using command: %s", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<Path> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    result.add(Path.of(line));
                    if (result.size() % 5000 == 0) {
                        debug.log("git ls-files progress: %d Java file(s)", result.size());
                    }
                }
            }
        }
        int exit = process.waitFor();
        debug.log("git ls-files finished with exit=%d and %d Java file(s)", exit, result.size());
        if (exit == 0 && !result.isEmpty()) {
            return result;
        }

        debug.log("falling back to filesystem walk for Java file discovery");
        try (Stream<Path> stream = Files.walk(repo)) {
            List<Path> fallback = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains(repo.resolve(".git").toString()))
                    .map(repo::relativize)
                    .toList();
            debug.log("filesystem walk discovered %d Java file(s)", fallback.size());
            return fallback;
        }
    }
}
