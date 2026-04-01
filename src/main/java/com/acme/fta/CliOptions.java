package com.acme.fta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record CliOptions(Path repo, List<String> reportGlobs, int top, Path outFile, boolean help) {
    private static final String DEFAULT_REPORTS = "**/build/test-results/**/*.xml";

    public static CliOptions parse(String[] args) {
        Path repo = Path.of(".");
        List<String> reportGlobs = new ArrayList<>(List.of(DEFAULT_REPORTS));
        int top = 3;
        Path outFile = null;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--repo" -> repo = Path.of(requireValue(args, ++i, arg));
                case "--reports" -> reportGlobs = splitCsv(requireValue(args, ++i, arg));
                case "--top" -> top = parseTop(requireValue(args, ++i, arg));
                case "--out" -> outFile = Path.of(requireValue(args, ++i, arg));
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        return new CliOptions(repo, reportGlobs, top, outFile, help);
    }

    private static int parseTop(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("--top must be > 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for --top: " + value);
        }
    }

    private static String requireValue(String[] args, int index, String optionName) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + optionName);
        }
        return args[index];
    }

    private static List<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static String helpText() {
        return """
                failed-test-author-finder

                Usage:
                  java -jar failed-test-author-finder.jar [options]

                Options:
                  --repo <path>      Git repository path. Default: .
                  --reports <globs>  Comma-separated glob patterns for JUnit XML reports.
                                     Default: **/build/test-results/**/*.xml
                  --top <N>          Number of top authors to print. Default: 3
                  --out <file>       Write text report to file instead of stdout
                  --help, -h         Show this help
                """;
    }
}
