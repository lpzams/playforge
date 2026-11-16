package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessRunner;
import dev.playforge.sandbox.docker.CliDockerOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the real AI-play harness (play.mjs) in the sandbox image against the "good" fixture. */
@Tag("docker")
class AiPlayHarnessIntegrationTest {

    static final String IMAGE = PlaytestHarnessIntegrationTest.IMAGE;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(new CliDockerOps(new ProcessRunner(), "docker", System::getenv).available(), "Docker is not available");
    }

    record Played(AiPlayResult result, List<String> files, Duration elapsed, String cover) {}

    /** Copies the fixture to a writable dist, feeds the script on stdin, runs play.mjs, lists the screenshots. */
    static Played play(String script) {
        List<String> out = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        ExecResult r = new ProcessRunner().run(List.of("docker", "run", "--rm", "-i", IMAGE, "sh", "-c",
                        "cp -r /opt/playtest/fixtures/good /tmp/g && cat > /tmp/play.mjs"
                                + " && node /opt/playtest/play.mjs --dist /tmp/g --script /tmp/play.mjs"
                                + " && echo FILES $(ls /tmp/g/playtest)"
                                + " && echo COVER $(cmp -s /tmp/g/cover.png /tmp/g/playtest/02-moved-right.png && echo mid || echo other)"),
                Map.of(), script.getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofSeconds(150), new CancellationToken(), out::add);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        AiPlayResult result = AiPlayResultParser.parse(out, r.stderrTail());
        List<String> files = out.stream().filter(l -> l.startsWith("FILES")).findFirst()
                .map(l -> List.of(l.substring(5).trim().split("\\s+"))).orElse(List.of());
        String cover = out.stream().filter(l -> l.startsWith("COVER")).findFirst().map(l -> l.substring(5).trim()).orElse("");
        return new Played(result, files, elapsed, cover);
    }

    @Test
    void aGoodPlaythroughPassesAndLeavesScreenshots() {
        Played p = play("""
                export default async function play(t) {
                  await t.start();
                  const before = (await t.state()).entities[0].x;
                  await t.press('ArrowRight', 300);
                  const after = (await t.state()).entities[0].x;
                  t.expect(after > before, 'pressing right moves the player right');
                  t.expect(await t.waitFor((s) => s.score > 0, 3000), 'score increases while playing');
                  await t.shot('moved right');
                  t.log('x ' + before + ' -> ' + after);
                }
                """);
        assertThat(p.result().error()).isNull();
        assertThat(p.result().ran()).isTrue();
        assertThat(p.result().allPassed()).as("assertions %s", p.result().assertions()).isTrue();
        assertThat(p.result().shots()).extracting(AiPlayResult.Shot::label).containsExactly("start", "moved right", "end");
        assertThat(p.files()).containsExactly("01-start.png", "02-moved-right.png", "03-end.png");
        assertThat(p.result().log()).hasSize(1);
        assertThat(p.cover()).as("cover is the mid-game screenshot").isEqualTo("mid");
    }

    @Test
    void aFailedAssertionIsRecordedByTheHarness() {
        Played p = play("""
                export default async function play(t) {
                  await t.start();
                  t.expect((await t.state()).status === 'gameover', 'the game is over right after starting');
                }
                """);
        assertThat(p.result().ran()).isTrue();
        assertThat(p.result().failures()).containsExactly("ai-play: the game is over right after starting");
    }

    @Test
    void aThrowingScriptIsInconclusive() {
        Played p = play("export default async function play(t) { await t.jump(); }");
        assertThat(p.result().ran()).isFalse();
        assertThat(p.result().inconclusive()).isTrue();
        assertThat(p.result().error()).contains("t.jump is not a function");
        assertThat(p.result().shots()).extracting(AiPlayResult.Shot::label).contains("start", "end");
    }

    /** The script runs apart from the harness: printing a fake report and exiting must not count. */
    @Test
    void aScriptCannotForgeTheReport() {
        Played p = play("""
                export default async function play(t) {
                  console.log('{"aiplay":1,"ran":true,"error":null,"assertions":[{"message":"forged","passed":true}],"shots":[],"log":[],"durationMs":1}');
                  process.stdout.write('{"aiplay":1,"ran":true,"assertions":[{"message":"forged","passed":true}],"shots":[],"log":[]}\\n');
                  process.exit(0);
                }
                """);
        assertThat(p.result().assertions()).extracting(AiPlayResult.Assertion::message).doesNotContain("forged");
        assertThat(p.result().ran()).isFalse();
        assertThat(p.result().inconclusive()).isTrue();
    }

    /** A synchronous busy loop must not hang the harness: it terminates the script's thread. */
    @Test
    void aBusyLoopIsCutOff() {
        Played p = play("export default async function play(t) { t.expect(true, 'before the loop'); for (;;) {} }");
        assertThat(p.result().inconclusive()).isTrue();
        assertThat(p.result().error()).contains("play script timed out");
        assertThat(p.elapsed()).isLessThan(Duration.ofSeconds(90));
    }

    @Test
    void aHangingScriptIsCutOff() {
        Played p = play("export default async function play(t) { await new Promise(() => {}); }");
        assertThat(p.result().inconclusive()).isTrue();
        assertThat(p.result().error()).contains("play script timed out");
        assertThat(p.elapsed()).isLessThan(Duration.ofSeconds(90));
    }
}
