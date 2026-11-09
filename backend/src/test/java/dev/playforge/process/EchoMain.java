package dev.playforge.process;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Child-process helper for ProcessRunnerTest; avoids depending on a shell. */
public final class EchoMain {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        switch (args[0]) {
            case "lines" -> {
                for (int i = 1; i <= Integer.parseInt(args[1]); i++) out.println("l" + i);
            }
            case "echo" -> {
                System.out.write(System.in.readAllBytes());
                System.out.flush();
            }
            case "fail" -> {
                System.err.print("boom");
                System.err.flush();
                System.exit(3);
            }
            case "sleep" -> {
                out.println(ProcessHandle.current().pid());
                Thread.sleep(30_000);
            }
            case "env" -> out.println(System.getenv(args[1]));
            case "longline" -> out.println("x".repeat(Integer.parseInt(args[1])));
            default -> throw new IOException("unknown mode " + args[0]);
        }
    }
}
