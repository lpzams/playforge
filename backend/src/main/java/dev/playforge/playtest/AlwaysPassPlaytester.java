package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** P1 stand-in; replaced by the Playwright-based playtester in P3. */
@Component
public class AlwaysPassPlaytester implements Playtester {
    @Override
    public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
        cancel.throwIfCancelled();
        return new PlaytestReport(true, List.of(), Map.of());
    }
}
