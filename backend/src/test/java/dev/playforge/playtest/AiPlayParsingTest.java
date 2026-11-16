package dev.playforge.playtest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiPlayParsingTest {

    // ---------- AiPlayResultParser ----------

    @Test
    void parsesTheLastAiplayLine() {
        List<String> out = List.of("noise", "{\"aiplay\":1,\"ran\":true,\"error\":null,"
                + "\"assertions\":[{\"message\":\"score goes up\",\"passed\":true},{\"message\":\"game ends\",\"passed\":false}],"
                + "\"shots\":[{\"file\":\"playtest/01-start.png\",\"label\":\"start\"}],\"log\":[\"caught a coin\"],\"durationMs\":1200}");
        AiPlayResult r = AiPlayResultParser.parse(out, "");
        assertThat(r.ran()).isTrue();
        assertThat(r.assertions()).extracting(AiPlayResult.Assertion::message).containsExactly("score goes up", "game ends");
        assertThat(r.shots()).extracting(AiPlayResult.Shot::file).containsExactly("playtest/01-start.png");
        assertThat(r.log()).containsExactly("caught a coin");
        assertThat(r.inconclusive()).isFalse();
        assertThat(r.allPassed()).isFalse();
        assertThat(r.failures()).containsExactly("ai-play: game ends");
    }

    @Test
    void missingReportIsInconclusiveNotAFailure() {
        AiPlayResult r = AiPlayResultParser.parse(List.of("garbage"), "SyntaxError: bad script");
        assertThat(r.ran()).isFalse();
        assertThat(r.error()).contains("SyntaxError");
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aRunWithoutAssertionsIsInconclusive() {
        AiPlayResult r = AiPlayResultParser.parse(List.of("{\"aiplay\":1,\"ran\":true,\"assertions\":[],\"shots\":[],\"log\":[]}"), "");
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.allPassed()).isFalse();
    }

    @Test
    void aScriptErrorIsInconclusiveEvenWithSomeAssertions() {
        AiPlayResult r = AiPlayResultParser.parse(List.of("{\"aiplay\":1,\"ran\":false,\"error\":\"t.foo is not a function\","
                + "\"assertions\":[{\"message\":\"x\",\"passed\":false}],\"shots\":[],\"log\":[]}"), "");
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.failures()).isEmpty();
    }

    // ---------- FunScoreParser ----------

    @Test
    void parsesScoresInsideAFence() {
        FunScore s = FunScoreParser.parse("Here you go:\n```json\n{\"scores\":{\"fit\":4,\"difficulty\":3,\"visuals\":2,\"feedback\":5},"
                + "\"summary\":\"能玩，画面简单\",\"suggestions\":[\"加点音效\"]}\n```");
        assertThat(s.parsed()).isTrue();
        assertThat(s.scores()).containsEntry("fit", 4).containsEntry("visuals", 2).hasSize(4);
        assertThat(s.summary()).isEqualTo("能玩，画面简单");
        assertThat(s.suggestions()).containsExactly("加点音效");
    }

    @Test
    void scoresAreClampedAndUnknownDimensionsDropped() {
        FunScore s = FunScoreParser.parse("{\"scores\":{\"fit\":9,\"difficulty\":0,\"mood\":3},\"summary\":\"x\"}");
        assertThat(s.scores()).containsEntry("fit", 5).containsEntry("difficulty", 1).doesNotContainKey("mood");
        assertThat(s.suggestions()).isEmpty();
    }

    @Test
    void unparseableJudgeReplyIsMarked() {
        assertThat(FunScoreParser.parse("I think it's fun!").parsed()).isFalse();
        assertThat(FunScoreParser.parse(null).parsed()).isFalse();
    }

    // ---------- PlayScripts ----------

    @Test
    void extractsTheFencedModule() {
        String reply = "Sure:\n```js\nexport default async function play(t) {\n  t.expect(true, 'ok');\n}\n```\nDone.";
        assertThat(PlayScripts.extract(reply)).hasValue("export default async function play(t) {\n  t.expect(true, 'ok');\n}");
    }

    @Test
    void acceptsABareModuleAndOtherFenceLanguages() {
        assertThat(PlayScripts.extract("export default async function play(t) {}")).isPresent();
        assertThat(PlayScripts.extract("```javascript\nexport default async (t) => {}\n```")).hasValue("export default async (t) => {}");
    }

    @Test
    void rejectsRepliesWithoutADefaultExportOrTooLong() {
        assertThat(PlayScripts.extract("```js\nfunction play() {}\n```")).isEmpty();
        assertThat(PlayScripts.extract(null)).isEmpty();
        assertThat(PlayScripts.extract("export default 1;" + "x".repeat(PlayScripts.MAX_CHARS))).isEmpty();
    }
}
