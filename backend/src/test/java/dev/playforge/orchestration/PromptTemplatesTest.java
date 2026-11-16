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
                .contains("```js");
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
