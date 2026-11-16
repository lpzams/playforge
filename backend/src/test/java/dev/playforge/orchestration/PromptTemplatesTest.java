package dev.playforge.orchestration;

import dev.playforge.playtest.AiPlayResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplatesTest {

    @Test
    void reviewTruncatesHugeDiff() {
        String review = PromptTemplates.review("p", "+x\n".repeat(100_000));
        assertThat(review.length()).isLessThan(70_000);
        assertThat(review).contains("[diff truncated]");
    }

    @Test
    void codePromptMentionsTemplateAndProbeSdk() {
        String p = PromptTemplates.code("x");
        assertThat(p).contains("src/probe-sdk.js").contains("installProbe").contains("base: './'");
    }

    @Test
    void markupSectionGivesBoxesInCanvasPixelsAndTheImage() {
        dev.playforge.run.Markup m = new dev.playforge.run.Markup(new dev.playforge.run.Markup.Canvas(480, 320),
                java.util.List.of(new dev.playforge.run.Markup.Box(0.25, 0.125, 0.5, 0.25), new dev.playforge.run.Markup.Box(0, 0, 0.1, 0.1)));
        String p = PromptTemplates.markup(m, "/tmp/playforge/marked.png");
        assertThat(p).contains("480×320").contains("Box 1: x 120–360, y 40–120").contains("Box 2: x 0–48, y 0–32")
                .contains("/tmp/playforge/marked.png").contains("Read tool");
        assertThat(PromptTemplates.markup(m, null)).contains("could not be provided").doesNotContain("Read tool");
    }

    @Test
    void codePromptExplainsPlaytestEnvironmentLimits() {
        String p = PromptTemplates.code("x");
        assertThat(p).contains("window.addEventListener").contains("localStorage")
                .contains("Only list in-game controls");
    }

    @Test
    void reviewKeepsSmallDiffIntact() {
        String review = PromptTemplates.review("p", "+console.log('hi')");
        assertThat(review).contains("+console.log('hi')").doesNotContain("[diff truncated]");
    }

    @Test
    void playerPromptDescribesTheScriptApiAndForbidsEdits() {
        String p = PromptTemplates.player("接金币的小游戏");
        assertThat(p).contains("接金币的小游戏")
                .contains("export default async function play(t)")
                .contains("t.expect(").contains("t.waitFor(").contains("t.shot(")
                .contains("Do NOT modify any files")
                .contains("```js")
                .contains("Simplified Chinese");
    }

    @Test
    void judgePromptListsScreenshotsAndAsksForChineseJson() {
        String p = PromptTemplates.judge("躲陨石",
                List.of(new AiPlayResult.Shot("playtest/01-start.png", "start"), new AiPlayResult.Shot("playtest/02-hit.png", "hit")),
                List.of(new AiPlayResult.Assertion("ship moves left", true), new AiPlayResult.Assertion("game ends on hit", false)),
                List.of("dodged 3 rocks"));
        assertThat(p).contains("躲陨石")
                .contains("/workspace/dist/playtest/01-start.png (start)")
                .contains("/workspace/dist/playtest/02-hit.png (hit)")
                .contains("[passed] ship moves left").contains("[failed] game ends on hit")
                .contains("dodged 3 rocks")
                .contains("\"fit\"").contains("\"difficulty\"").contains("\"visuals\"").contains("\"feedback\"")
                .contains("Simplified Chinese")
                .contains("Do NOT modify any files");
    }
}
