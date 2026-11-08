package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;

public interface Playtester {
    PlaytestReport run(Sandbox sandbox, CancellationToken cancel);
}
