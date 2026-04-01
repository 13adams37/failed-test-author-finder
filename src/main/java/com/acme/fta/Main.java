package com.acme.fta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Main {
    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.help()) {
                System.out.println(CliOptions.helpText());
                return;
            }

            Path repo = options.repo().toAbsolutePath().normalize();
            validateRepo(repo);

            JunitXmlScanner scanner = new JunitXmlScanner(repo, options.reportGlobs());
            List<FailedTestCase> failedTests = scanner.scan();
            if (failedTests.isEmpty()) {
                String text = "No failed tests found in XML reports under: " + repo + System.lineSeparator();
                writeReport(options, text);
                return;
            }

            GitSourceIndex sourceIndex = GitSourceIndex.build(repo);
            JavaSourceAnalyzer sourceAnalyzer = new JavaSourceAnalyzer();
            GitBlameRunner blameRunner = new GitBlameRunner(repo);

            List<AnalysisResult> results = new ArrayList<>();
            for (FailedTestCase failedTest : failedTests) {
                results.add(analyzeOne(sourceIndex, sourceAnalyzer, blameRunner, failedTest, options.top()));
            }

            results.sort(Comparator
                    .comparing((AnalysisResult r) -> r.failedTest().fqcn())
                    .thenComparing(r -> r.failedTest().testName()));

            String report = ReportWriter.write(repo, results, failedTests.size(), scanner.getMatchedReportFiles().size());
            writeReport(options, report);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            System.err.println();
            System.err.println(CliOptions.helpText());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static AnalysisResult analyzeOne(
            GitSourceIndex sourceIndex,
            JavaSourceAnalyzer sourceAnalyzer,
            GitBlameRunner blameRunner,
            FailedTestCase failedTest,
            int top) {

        Path sourceFile = sourceIndex.findSourceFile(failedTest.fqcn()).orElse(null);
        if (sourceFile == null) {
            return AnalysisResult.sourceNotFound(failedTest);
        }

        JavaSourceAnalyzer.RangeMatch rangeMatch;
        try {
            rangeMatch = sourceAnalyzer.findBestRange(sourceFile, failedTest.fqcn(), failedTest.testName());
        } catch (Exception e) {
            return AnalysisResult.analysisError(failedTest, sourceFile, "Failed to parse source: " + e.getMessage());
        }

        if (rangeMatch == null) {
            return AnalysisResult.analysisError(failedTest, sourceFile, "Could not resolve class or method range");
        }

        List<AuthorContribution> authors;
        try {
            authors = blameRunner.blame(sourceFile, rangeMatch.startLine(), rangeMatch.endLine(), top);
        } catch (Exception e) {
            return AnalysisResult.analysisError(failedTest, sourceFile, "git blame failed: " + e.getMessage());
        }

        return AnalysisResult.success(failedTest, sourceFile, rangeMatch, authors);
    }

    private static void validateRepo(Path repo) {
        if (!Files.exists(repo)) {
            throw new IllegalArgumentException("Repo path does not exist: " + repo);
        }
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("Repo path is not a directory: " + repo);
        }
        if (!Files.exists(repo.resolve(".git"))) {
            throw new IllegalArgumentException("Repo path is not a git checkout (missing .git): " + repo);
        }
    }

    private static void writeReport(CliOptions options, String report) throws IOException {
        if (options.outFile() == null) {
            System.out.print(report);
            return;
        }
        Path parent = options.outFile().toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(options.outFile(), report);
        System.out.println("Report written to " + options.outFile().toAbsolutePath().normalize());
    }
}
