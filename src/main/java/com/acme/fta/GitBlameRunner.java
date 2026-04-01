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

public final class GitBlameRunner {
    private final Path repo;
    private final Path ignoreRevsFile;

    public GitBlameRunner(Path repo) {
        this.repo = repo;
        Path candidate = repo.resolve(".git-blame-ignore-revs");
        this.ignoreRevsFile = Files.exists(candidate) ? candidate : null;
    }

    public List<AuthorContribution> blame(Path sourceFile, int startLine, int endLine, int top) throws IOException, InterruptedException {
        Path relative = repo.relativize(sourceFile);
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.add("blame");
        cmd.add("--line-porcelain");
        cmd.add("-M");
        cmd.add("-C");
        cmd.add("-C");
        if (ignoreRevsFile != null) {
            cmd.add("--ignore-revs-file");
            cmd.add(ignoreRevsFile.toString());
        }
        cmd.add("-L");
        cmd.add(startLine + "," + endLine);
        cmd.add("--");
        cmd.add(relative.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Map<AuthorKey, Integer> linesByAuthor = new HashMap<>();
        String currentAuthor = "unknown";
        String currentEmail = "unknown";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("author ")) {
                    currentAuthor = line.substring("author ".length()).trim();
                } else if (line.startsWith("author-mail ")) {
                    currentEmail = sanitizeEmail(line.substring("author-mail ".length()).trim());
                } else if (line.startsWith("\t")) {
                    AuthorKey key = new AuthorKey(currentAuthor, currentEmail);
                    linesByAuthor.merge(key, 1, Integer::sum);
                }
            }
        }

        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git blame returned non-zero exit code: " + exit);
        }

        int totalLines = linesByAuthor.values().stream().mapToInt(Integer::intValue).sum();
        if (totalLines == 0) {
            return List.of();
        }

        return linesByAuthor.entrySet().stream()
                .map(entry -> new AuthorContribution(
                        entry.getKey().name(),
                        entry.getKey().email(),
                        entry.getValue(),
                        totalLines,
                        entry.getValue() / (double) totalLines))
                .sorted(Comparator.comparingDouble(AuthorContribution::score).reversed()
                        .thenComparing(AuthorContribution::name))
                .limit(top)
                .toList();
    }

    private String sanitizeEmail(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return raw.replace("<", "").replace(">", "").trim();
    }

    private record AuthorKey(String name, String email) {
    }
}
