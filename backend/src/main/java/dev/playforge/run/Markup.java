package dev.playforge.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Areas the player marked on a screenshot of the game, for a follow-up request.
 *
 * @param canvas logical size of the game canvas (the coordinate space of the game code)
 * @param boxes  1 to {@value #MAX_BOXES} rectangles, normalised to 0..1 of the canvas
 */
public record Markup(Canvas canvas, List<Box> boxes) {

    public static final int MAX_BOXES = 3;
    private static final int MAX_CANVAS = 8192;
    private static final double EPS = 1e-6;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Canvas(int width, int height) {}

    public record Box(double x, double y, double w, double h) {
        /** In canvas pixels: {left, top, right, bottom}. */
        public int[] pixels(Canvas c) {
            return new int[] {
                    (int) Math.round(x * c.width()), (int) Math.round(y * c.height()),
                    (int) Math.round((x + w) * c.width()), (int) Math.round((y + h) * c.height())};
        }
    }

    /** @throws IllegalArgumentException when the markup is malformed */
    public Markup validated() {
        if (canvas == null || canvas.width() < 1 || canvas.height() < 1
                || canvas.width() > MAX_CANVAS || canvas.height() > MAX_CANVAS) {
            throw new IllegalArgumentException("markup.canvas must be 1.." + MAX_CANVAS + " pixels each way");
        }
        if (boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES) {
            throw new IllegalArgumentException("markup.boxes must hold 1.." + MAX_BOXES + " boxes");
        }
        for (Box b : boxes) {
            if (b == null || !(b.w() > 0) || !(b.h() > 0) || !(b.x() >= -EPS) || !(b.y() >= -EPS)
                    || !(b.x() + b.w() <= 1 + EPS) || !(b.y() + b.h() <= 1 + EPS)) {
                throw new IllegalArgumentException("markup.boxes must lie inside 0..1 with a positive size");
            }
        }
        return this;
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null for null/blank input (a run without markup). */
    public static Markup fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, Markup.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored markup is unreadable", e);
        }
    }
}
