package com.acme.fta;

public record AuthorContribution(String name, String email, int lines, int totalLines, double score) {
}
