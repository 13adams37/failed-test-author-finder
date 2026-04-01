package com.acme.fta;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JunitXmlScanner {
    private final Path repo;
    private final List<PathMatcher> matchers;
    private final Set<Path> matchedReportFiles = new LinkedHashSet<>();
    private final DebugLogger debug;

    public JunitXmlScanner(Path repo, List<String> globs, DebugLogger debug) {
        this.repo = repo;
        this.debug = debug;
        List<PathMatcher> tmp = new ArrayList<>();
        for (String pattern : globs) {
            tmp.add(repo.getFileSystem().getPathMatcher("glob:" + pattern));
            if (pattern.startsWith("**/")) {
                tmp.add(repo.getFileSystem().getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
        this.matchers = List.copyOf(tmp);
    }

    public List<FailedTestCase> scan() throws Exception {
        debug.log("scanning repository for JUnit XML reports");
        List<Path> reportFiles = discoverReportFiles();
        debug.log("discovered %d matching XML report file(s)", reportFiles.size());
        List<FailedTestCase> result = new ArrayList<>();
        for (Path reportFile : reportFiles) {
            result.addAll(readFailures(reportFile));
        }
        return result;
    }

    public Set<Path> getMatchedReportFiles() {
        return matchedReportFiles;
    }

    private List<Path> discoverReportFiles() throws IOException {
        List<Path> reportFiles = new ArrayList<>();
        final int[] visitedFiles = {0};
        final int[] matchedFiles = {0};
        Files.walkFileTree(repo, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path fileName = dir.getFileName();
                if (fileName != null && ".git".equals(fileName.toString())) {
                    debug.log("skipping .git directory: %s", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                visitedFiles[0]++;
                if (visitedFiles[0] % 5000 == 0) {
                    debug.log("file scan progress: visited=%d matchedReports=%d", visitedFiles[0], matchedFiles[0]);
                }
                if (!file.getFileName().toString().endsWith(".xml")) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = repo.relativize(file);
                boolean matches = matchers.stream().anyMatch(m -> m.matches(relative));
                if (matches) {
                    reportFiles.add(file);
                    matchedReportFiles.add(relative);
                    matchedFiles[0]++;
                    debug.log("matched report file: %s", relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                debug.log("failed to visit file %s: %s", file, exc == null ? "unknown error" : exc.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        debug.log("finished report discovery: visited=%d matchedReports=%d", visitedFiles[0], matchedFiles[0]);
        return reportFiles;
    }

    private List<FailedTestCase> readFailures(Path reportFile) throws Exception {
        debug.log("parsing report file: %s", repo.relativize(reportFile));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setNamespaceAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(reportFile.toFile());
        NodeList testCases = document.getElementsByTagName("testcase");
        List<FailedTestCase> failures = new ArrayList<>();

        for (int i = 0; i < testCases.getLength(); i++) {
            Node node = testCases.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if (!hasFailureOrError(element)) {
                continue;
            }
            String fqcn = element.getAttribute("classname");
            String testName = element.getAttribute("name");
            if (fqcn == null || fqcn.isBlank()) {
                debug.log("skipping failed testcase without classname in %s", repo.relativize(reportFile));
                continue;
            }
            FailedTestCase failure = new FailedTestCase(
                    fqcn.trim(),
                    testName == null ? "" : testName.trim(),
                    repo.relativize(reportFile));
            failures.add(failure);
            debug.log("found failed testcase: %s#%s", failure.fqcn(), failure.testName());
        }
        debug.log("report %s yielded %d failed testcase(s)", repo.relativize(reportFile), failures.size());
        return failures;
    }

    private boolean hasFailureOrError(Element testcase) {
        NodeList children = testcase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            String tagName = element.getTagName();
            if ("failure".equals(tagName) || "error".equals(tagName)) {
                return true;
            }
        }
        return false;
    }
}
