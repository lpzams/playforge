package dev.playforge.api.dto;

import java.util.List;

public record ProjectView(String id, String title, String prompt, List<RunView> runs) {}
