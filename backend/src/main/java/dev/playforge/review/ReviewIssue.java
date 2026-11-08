package dev.playforge.review;

public record ReviewIssue(Severity severity, String file, Integer line, String message) {}
