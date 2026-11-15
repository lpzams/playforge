package dev.playforge.api.dto;

import java.time.Instant;
import java.util.List;

/** @param previewAvailable a playtested build of this project can be played at /preview/{id}/ */
public record ProjectView(String id, String title, String prompt, Instant createdAt, boolean previewAvailable,
                          List<RunView> runs) {}
