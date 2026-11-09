package dev.playforge.engine;

/** CLI-agnostic events parsed from an agent's JSONL output. */
public sealed interface StreamEvent {

    int SUMMARY_MAX = 500;

    record Text(String text) implements StreamEvent {}

    /** @param filePath set for file-writing tools (Write, Edit, ...), otherwise null */
    record ToolCall(String name, String inputSummary, String filePath) implements StreamEvent {}

    record ToolResult(String summary, boolean isError) implements StreamEvent {}

    record Usage(long inputTokens, long outputTokens) implements StreamEvent {}

    record Final(String text, boolean isError) implements StreamEvent {}

    static String summarize(String s) {
        if (s == null) return "";
        return s.length() <= SUMMARY_MAX ? s : s.substring(0, SUMMARY_MAX - 1) + "…";
    }
}
