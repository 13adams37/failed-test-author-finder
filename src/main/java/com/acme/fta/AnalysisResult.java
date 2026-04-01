package com.acme.fta;

import java.nio.file.Path;
import java.util.List;

public record AnalysisResult(
        FailedTestCase failedTest,
        Path sourceFile,
        Status status,
        String message,
        JavaSourceAnalyzer.RangeMatch rangeMatch,
        List<AuthorContribution> authors) {

    public enum Status {
        OK,
        SOURCE_NOT_FOUND,
        ERROR
    }

    public static AnalysisResult success(
            FailedTestCase failedTest,
            Path sourceFile,
            JavaSourceAnalyzer.RangeMatch rangeMatch,
            List<AuthorContribution> authors) {
        return new AnalysisResult(failedTest, sourceFile, Status.OK, null, rangeMatch, authors);
    }

    public static AnalysisResult sourceNotFound(FailedTestCase failedTest) {
        return new AnalysisResult(failedTest, null, Status.SOURCE_NOT_FOUND, "Source file not found", null, List.of());
    }

    public static AnalysisResult analysisError(FailedTestCase failedTest, Path sourceFile, String message) {
        return new AnalysisResult(failedTest, sourceFile, Status.ERROR, message, null, List.of());
    }
}
