package dev.playforge.run;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkupTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    static String dataUrl(byte[] bytes) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    static Markup markup(Markup.Box... boxes) {
        return new Markup(new Markup.Canvas(480, 320), List.of(boxes));
    }

    @Test
    void boxesConvertToCanvasPixels() {
        int[] p = new Markup.Box(0.25, 0.125, 0.5, 0.25).pixels(new Markup.Canvas(480, 320));
        assertThat(p).containsExactly(120, 40, 360, 120);
    }

    @Test
    void jsonRoundTrip() {
        Markup m = markup(new Markup.Box(0, 0, 1, 1));
        assertThat(Markup.fromJson(m.toJson())).isEqualTo(m);
        assertThat(Markup.fromJson(null)).isNull();
    }

    @Test
    void validationRejectsBadBoxesAndCanvas() {
        assertThat(markup(new Markup.Box(0, 0, 1, 1)).validated()).isNotNull();
        assertThatThrownBy(() -> markup().validated()).hasMessageContaining("1..3");
        Markup.Box ok = new Markup.Box(.1, .1, .1, .1);
        assertThatThrownBy(() -> markup(ok, ok, ok, ok).validated()).hasMessageContaining("1..3");
        assertThatThrownBy(() -> markup(new Markup.Box(.5, .5, .6, .1)).validated()).hasMessageContaining("inside");
        assertThatThrownBy(() -> markup(new Markup.Box(.1, .1, 0, .1)).validated()).hasMessageContaining("inside");
        assertThatThrownBy(() -> markup(new Markup.Box(Double.NaN, .1, .1, .1)).validated()).hasMessageContaining("inside");
        assertThatThrownBy(() -> new Markup(new Markup.Canvas(0, 10), List.of(ok)).validated()).hasMessageContaining("canvas");
        assertThatThrownBy(() -> new Markup(null, List.of(ok)).validated()).hasMessageContaining("canvas");
    }

    @Test
    void decodeAcceptsOnlyPngDataUrls() {
        assertThat(MarkupImages.decode(dataUrl(PNG))).isEqualTo(PNG);
        assertThatThrownBy(() -> MarkupImages.decode("data:image/jpeg;base64,AAAA")).hasMessageContaining("data:image/png");
        assertThatThrownBy(() -> MarkupImages.decode(null)).hasMessageContaining("data:image/png");
        assertThatThrownBy(() -> MarkupImages.decode("data:image/png;base64,%%%")).hasMessageContaining("base64");
        assertThatThrownBy(() -> MarkupImages.decode(dataUrl("<svg onload=x>".getBytes()))).hasMessageContaining("not a PNG");
        byte[] big = new byte[MarkupImages.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> MarkupImages.decode(dataUrl(big))).hasMessageContaining("4 MB");
    }
}
