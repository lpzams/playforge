package dev.playforge.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewParserTest {

    @Test
    void parsesPlainJson() {
        ReviewReport r = ReviewParser.parse("{\"issues\":[{\"severity\":\"BLOCKER\",\"file\":\"a.js\",\"line\":3,\"message\":\"x\"}]}");
        assertThat(r.parsed()).isTrue();
        assertThat(r.issues()).containsExactly(new ReviewIssue(Severity.BLOCKER, "a.js", 3, "x"));
        assertThat(r.hasBlockers()).isTrue();
    }

    @Test
    void parsesFencedJsonWithProse() {
        ReviewReport r = ReviewParser.parse("看完了：\n```json\n{\"issues\":[{\"severity\":\"SUGGESTION\",\"file\":\"b.js\",\"message\":\"y\"}]}\n```\n以上");
        assertThat(r.parsed()).isTrue();
        assertThat(r.hasBlockers()).isFalse();
        assertThat(r.issues()).singleElement().satisfies(i -> {
            assertThat(i.line()).isNull();
            assertThat(i.file()).isEqualTo("b.js");
        });
    }

    @Test
    void severityCaseInsensitive() {
        ReviewReport r = ReviewParser.parse("{\"issues\":[{\"severity\":\"blocker\",\"file\":\"a.js\",\"message\":\"x\"}]}");
        assertThat(r.issues().getFirst().severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    void garbageIsUnparsed() {
        ReviewReport r = ReviewParser.parse("LGTM!");
        assertThat(r.parsed()).isFalse();
        assertThat(r.issues()).isEmpty();
        assertThat(r.hasBlockers()).isFalse();
    }

    @Test
    void nullTextIsUnparsed() {
        assertThat(ReviewParser.parse(null).parsed()).isFalse();
    }

    @Test
    void unknownSeverityTreatedAsSuggestion() {
        ReviewReport r = ReviewParser.parse("{\"issues\":[{\"severity\":\"MAJOR\",\"file\":\"a.js\",\"message\":\"x\"}]}");
        assertThat(r.issues().getFirst().severity()).isEqualTo(Severity.SUGGESTION);
    }
}
