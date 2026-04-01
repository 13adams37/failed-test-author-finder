package com.acme.fta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Main {
    public static void main(String[] args) {
        DebugLogger debug = new DebugLogger(false);
        try {
            CliOptions options = CliOptions.parse(args);
            debug = new DebugLogger(options.debug());
            if (options.help()) {
                System.out.println(CliOptions.helpText());
                return;
            }

            Path repo = options.repo().toAbsolutePath().normalize();
            debug.log("starting analysis");
            debug.log("repo=%s", repo);
            debug.log("report globs=%s", options.reportGlobs());
            debug.log("top=%d", options.top());
            if (options.outFile() != null) {
                debug.log("report output file=%s", options.outFile().toAbsolutePath().normalize());
            }

            validateRepo(repo, debug);

            JunitXmlScanner scanner = new JunitXmlScanner(repo, options.reportGlobs(), debug);
            List<FailedTestCase> failedTests = scanner.scan();
            debug.log("scan complete: matched report files=%d, failed tests=%d", scanner.getMatchedReportFiles().size(), failedTests.size());
            if (failedTests.isEmpty()) {
                String text = "No failed tests found in XML reports under: " + repo + System.lineSeparator();
                writeReport(options, text, debug);
                return;
            }

            GitSourceIndex sourceIndex = GitSourceIndex.build(repo, debug);
            JavaSourceAnalyzer sourceAnalyzer = new JavaSourceAnalyzer(debug);
            GitBlameRunner blameRunner = new GitBlameRunner(repo, debug);

            List<AnalysisResult> results = new ArrayList<>();
            for (int i = 0; i < failedTests.size(); i++) {
                FailedTestCase failedTest = failedTests.get(i);
                debug.log("analyzing failed test %d/%d: %s#%s", i + 1, failedTests.size(), failedTest.fqcn(), failedTest.testName());
                results.add(analyzeOne(sourceIndex, sourceAnalyzer, blameRunner, failedTest, options.top(), debug));
            }

            results.sort(Comparator
                    .comparing((AnalysisResult r) -> r.failedTest().fqcn())
                    .thenComparing(r -> r.failedTest().testName()));

            String report = ReportWriter.write(repo, results, failedTests.size(), scanner.getMatchedReportFiles().size());
            writeReport(options, report, debug);
            debug.log("analysis finished successfully");
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
            int top,
            DebugLogger debug) {

        Path sourceFile = sourceIndex.findSourceFile(failedTest.fqcn()).orElse(null);
        if (sourceFile == null) {
            debug.log("source file not found for %s", failedTest.fqcn());
            return AnalysisResult.sourceNotFound(failedTest);
        }
        debug.log("resolved source file: %s", sourceFile);

        JavaSourceAnalyzer.RangeMatch rangeMatch;
        try {
            rangeMatch = sourceAnalyzer.findBestRange(sourceFile, failedTest.fqcn(), failedTest.testName());
        } catch (Exception e) {
            debug.log("source analysis failed for %s: %s", sourceFile, e.getMessage());
            return AnalysisResult.analysisError(failedTest, sourceFile, "Failed to parse source: " + e.getMessage());
        }

        if (rangeMatch == null) {
            debug.log("range resolution failed for %s", sourceFile);
            return AnalysisResult.analysisError(failedTest, sourceFile, "Could not resolve class or method range");
        }

        List<AuthorContribution> authors;
        try {
            authors = blameRunner.blame(sourceFile, rangeMatch.startLine(), rangeMatch.endLine(), top);
        } catch (Exception e) {
            debug.log("git blame failed for %s:%d-%d: %s", sourceFile, rangeMatch.startLine(), rangeMatch.endLine(), e.getMessage());
            return AnalysisResult.analysisError(failedTest, sourceFile, "git blame failed: " + e.getMessage());
        }

        debug.log("analysis result for %s#%s: scope=%s confidence=%s authors=%d",
                failedTest.fqcn(),
                failedTest.testName(),
                rangeMatch.scope(),
                rangeMatch.confidence(),
                authors.size());
        return AnalysisResult.success(failedTest, sourceFile, rangeMatch, authors);
    }

    private static void validateRepo(Path repo, DebugLogger debug) {
        debug.log("validating repository path");
        if (!Files.exists(repo)) {
            throw new IllegalArgumentException("Repo path does not exist: " + repo);
        }
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("Repo path is not a directory: " + repo);
        }
        if (!Files.exists(repo.resolve(".git"))) {
            throw new IllegalArgumentException("Repo path is not a git checkout (missing .git): " + repo);
        }
        debug.log("repository path is valid");
    }

    private static void writeReport(CliOptions options, String report, DebugLogger debug) throws IOException {
        if (options.outFile() == null) {
            debug.log("writing report to stdout");
            System.out.print(report);
            return;
        }
        Path parent = options.outFile().toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        debug.log("writing report to file: %s", options.outFile().toAbsolutePath().normalize());
        Files.writeString(options.outFile(), report);
        System.out.println("Report written to " + options.outFile().toAbsolutePath().normalize());
    }
}
