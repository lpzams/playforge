package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Stand-in for the local (no Docker) sandbox: no build, no browser — always passes. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "playforge.sandbox.provider", havingValue = "local", matchIfMissing = true)
public class AlwaysPassPlaytester implements Playtester {
    @Override
    public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
        cancel.throwIfCancelled();
        return new PlaytestReport(true, List.of(), Map.of());
    }
}
