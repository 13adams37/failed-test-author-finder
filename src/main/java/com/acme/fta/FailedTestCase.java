package com.acme.fta;

import java.nio.file.Path;

public record FailedTestCase(String fqcn, String testName, Path reportFile) {
}
