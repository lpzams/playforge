package dev.playforge.playtest;

import java.util.List;
import java.util.Map;

public record PlaytestReport(boolean passed, List<String> failures, Map<String, Object> details) {}
