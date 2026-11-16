package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;

public interface Playtester {

    /** Builds the game and runs the scripted hard checks. */
    PlaytestReport run(Sandbox sandbox, CancellationToken cancel);

    /** Whether {@link #play} is available (it needs a browser in the sandbox). */
    default boolean supportsAiPlay() {
        return false;
    }

    /**
     * Runs an AI-written playthrough ({@code export default async function play(t)}) against the build that
     * {@link #run} just produced. Problems with the script itself yield an inconclusive result, never an exception.
     */
    default AiPlayResult play(Sandbox sandbox, String script, CancellationToken cancel) {
        throw new UnsupportedOperationException("AI play is not supported by " + getClass().getSimpleName());
    }
}
