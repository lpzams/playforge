package dev.playforge.api.dto;

import dev.playforge.run.Markup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** A follow-up request; {@code markup} optionally points at areas of a game screenshot. */
public record CreateRunRequest(@NotBlank @Size(max = 4000) String instruction, MarkupRequest markup) {

    public CreateRunRequest(String instruction) {
        this(instruction, null);
    }

    /** @param image the screenshot with the numbered boxes drawn on, as a data:image/png;base64 URL */
    public record MarkupRequest(String image, Markup.Canvas canvas, List<Markup.Box> boxes) {
        public Markup markup() {
            return new Markup(canvas, boxes).validated();
        }
    }
}
