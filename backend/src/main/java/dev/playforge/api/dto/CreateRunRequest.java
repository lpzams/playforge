package dev.playforge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(@NotBlank @Size(max = 4000) String instruction) {}
