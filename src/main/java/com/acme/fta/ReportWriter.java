package com.acme.fta;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class ReportWriter {
    private ReportWriter() {
    }

    public static String write(Path repo, List<AnalysisResult> results, int failedTests, int reportFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("FAILED TEST AUTHOR REPORT").append(System.lineSeparator());
        sb.append("Generated : ")
                .append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append(System.lineSeparator());
        sb.append("Repo      : ").append(repo).append(System.lineSeparator());
        sb.append("Reports   : ").append(reportFiles).append(System.lineSeparator());
        sb.append("Failures  : ").append(failedTests).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        for (AnalysisResult result : results) {
            FailedTestCase failed = result.failedTest();
            sb.append("FAILED TEST : ")
                    .append(failed.fqcn())
                    .append("#")
                    .append(failed.testName())
                    .append(System.lineSeparator());
            sb.append("REPORT FILE : ").append(failed.reportFile()).append(System.lineSeparator());

            if (result.status() == AnalysisResult.Status.SOURCE_NOT_FOUND) {
                sb.append("STATUS      : SOURCE_NOT_FOUND").append(System.lineSeparator());
                sb.append(System.lineSeparator());
                continue;
            }

            sb.append("SOURCE FILE : ").append(repo.relativize(result.sourceFile())).append(System.lineSeparator());

            if (result.status() == AnalysisResult.Status.ERROR) {
                sb.append("STATUS      : ERROR").append(System.lineSeparator());
                sb.append("MESSAGE     : ").append(result.message()).append(System.lineSeparator());
                sb.append(System.lineSeparator());
                continue;
            }

            JavaSourceAnalyzer.RangeMatch range = result.rangeMatch();
            sb.append("MATCH       : ")
                    .append(range.scope())
                    .append(" (")
                    .append(range.confidence())
                    .append(")")
                    .append(System.lineSeparator());
            sb.append("MATCH NAME  : ").append(range.matchedName()).append(System.lineSeparator());
            sb.append("LINES       : ").append(range.startLine()).append("-").append(range.endLine()).append(System.lineSeparator());
            sb.append("AUTHORS     :").append(System.lineSeparator());

            if (result.authors().isEmpty()) {
                sb.append("  - no blame data").append(System.lineSeparator());
            } else {
                int idx = 1;
                for (AuthorContribution author : result.authors()) {
                    sb.append("  ").append(idx++).append(". ")
                            .append(author.name())
                            .append(" <").append(author.email()).append("> - ")
                            .append(String.format(Locale.ROOT, "%.2f%%", author.score() * 100.0))
                            .append(" (")
                            .append(author.lines())
                            .append("/")
                            .append(author.totalLines())
                            .append(" lines)")
                            .append(System.lineSeparator());
                }
            }
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }
}
