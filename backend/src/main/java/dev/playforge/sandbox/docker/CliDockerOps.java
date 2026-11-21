package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** {@link DockerOps} backed by the docker command-line client. */
public class CliDockerOps implements DockerOps {

    private static final Duration SHORT = Duration.ofSeconds(20);
    private static final Duration RUN = Duration.ofSeconds(60);
    private static final Duration COPY = Duration.ofSeconds(120);
    private static final String WORKSPACE = "/workspace";
    /**
     * Mirror the host tree into /workspace, then seed from the image's template (never overwriting a file): a project without
     * package.json gets the whole starter; any project without node_modules gets the preinstalled ones.
     */
    private static final String CLEAR_WORKSPACE =
            "find /workspace -mindepth 1 -maxdepth 1 ! -name node_modules ! -name dist ! -name .git -exec rm -rf {} + ; "
                    + "tar -x -C /workspace"
                    + " && cd /workspace"
                    + " && { [ -f package.json ] || cp -an /template/. /workspace/; }"
                    + " && { [ -d node_modules ] || cp -a /template/node_modules /workspace/node_modules; }";

    private final ProcessRunner runner;
    private final String docker;
    private final Function<String, String> hostEnv;

    /** @param hostEnv looks up host environment variables (normally {@code System::getenv}) */
    public CliDockerOps(ProcessRunner runner, String dockerCommand, Function<String, String> hostEnv) {
        this.runner = runner;
        this.docker = dockerCommand;
        this.hostEnv = hostEnv;
    }

    @Override
    public boolean available() {
        try {
            List<String> out = new ArrayList<>();
            ExecResult r = runner.run(List.of(docker, "version", "--format", "{{.Server.Version}}"), Map.of(), null,
                    SHORT, new CancellationToken(), out::add);
            return r.exitCode() == 0 && out.stream().anyMatch(l -> !l.isBlank());
        } catch (RuntimeException e) {
            return false; // docker binary missing, daemon down, timeout...
        }
    }

    @Override
    public String runDetached(DockerRunSpec spec) {
        List<String> cmd = new ArrayList<>(List.of(docker, "run", "-d",
                "--cpus", spec.cpus(), "--memory", spec.memory(), "--pids-limit", Integer.toString(spec.pidsLimit())));
        Map<String, String> env = new HashMap<>();
        spec.envFromHost().forEach((containerVar, hostVar) -> {
            String value = hostEnv.apply(hostVar);
            if (value != null && !value.isBlank()) {
                cmd.add("-e");
                cmd.add(containerVar);          // name only; the value travels in the client's environment
                env.put(containerVar, value);
            }
        });
        spec.env().forEach((k, v) -> {
            cmd.add("-e");
            cmd.add(k + "=" + v);
        });
        spec.labels().forEach((k, v) -> {
            cmd.add("--label");
            cmd.add(k + "=" + v);
        });
        for (DockerRunSpec.Mount m : spec.mounts()) {
            cmd.add("--mount");
            cmd.add("type=bind,source=" + m.source() + ",target=" + m.target() + ",readonly");
        }
        if (spec.name() != null) cmd.addAll(List.of("--name", spec.name()));
        if (spec.network() != null) cmd.addAll(List.of("--network", spec.network()));
        if (spec.networkAlias() != null) cmd.addAll(List.of("--network-alias", spec.networkAlias()));
        cmd.add(spec.image());
        cmd.addAll(spec.command());

        List<String> out = new ArrayList<>();
        ExecResult r = runner.run(cmd, env, null, RUN, new CancellationToken(), out::add);
        String id = out.stream().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("").trim();
        if (r.exitCode() != 0 || id.isEmpty()) {
            throw new IllegalStateException("docker run failed (exit " + r.exitCode() + "): " + r.stderrTail());
        }
        return id;
    }

    @Override
    public ExecResult exec(String containerId, List<String> cmd, byte[] stdin, Duration timeout,
                           CancellationToken cancel, Consumer<String> stdoutLine) {
        List<String> full = new ArrayList<>(List.of(docker, "exec", "-i", "-w", WORKSPACE, containerId));
        full.addAll(cmd);
        return runner.run(full, Map.of(), stdin, timeout, cancel, stdoutLine);
    }

    @Override
    public void copyIn(String containerId, Path hostDir) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        TarSync.pack(hostDir, tar);
        ExecResult r = runner.runBinary(List.of(docker, "exec", "-i", containerId, "sh", "-c", CLEAR_WORKSPACE),
                new ByteArrayInputStream(tar.toByteArray()), OutputStreamSink.INSTANCE, COPY, new CancellationToken());
        check(r, "copy into container");
    }

    @Override
    public void copyOut(String containerId, Path hostDir) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        ExecResult r = runner.runBinary(List.of(docker, "exec", containerId, "tar", "-c", "-C", WORKSPACE,
                        "--exclude=./node_modules", "--exclude=./dist", "--exclude=./.git", "."),
                null, tar, COPY, new CancellationToken());
        check(r, "copy out of container");
        TarSync.unpackMirror(new ByteArrayInputStream(tar.toByteArray()), hostDir);
    }

    @Override
    public void copyDirOut(String containerId, String relativePath, Path hostDir) {
        String rel = relativePath.replace('\\', '/');
        if (rel.isBlank() || rel.startsWith("/") || java.util.Arrays.asList(rel.split("/")).contains("..")) {
            throw new IllegalArgumentException("path must be relative to /workspace: " + relativePath);
        }
        String dir = WORKSPACE + "/" + rel;
        ExecResult exists = runner.run(List.of(docker, "exec", containerId, "test", "-d", dir), Map.of(), null,
                SHORT, new CancellationToken(), l -> { });
        if (exists.exitCode() != 0) throw new IllegalStateException("no such directory in sandbox: " + rel);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        ExecResult r = runner.runBinary(List.of(docker, "exec", containerId, "tar", "-c", "-C", dir, "."),
                null, tar, COPY, new CancellationToken());
        check(r, "copy " + rel + " out of container");
        TarSync.unpackMirror(new ByteArrayInputStream(tar.toByteArray()), hostDir);
    }

    @Override
    public void remove(String containerId) {
        runner.run(List.of(docker, "rm", "-f", containerId), Map.of(), null, SHORT, new CancellationToken(), l -> { });
    }

    @Override
    public void removeNamed(String name) {
        runner.run(List.of(docker, "rm", "-f", name), Map.of(), null, SHORT, new CancellationToken(), l -> { });
    }

    @Override
    public boolean healthy(String containerId) {
        try {
            return runner.run(List.of(docker, "exec", containerId, "true"), Map.of(), null, SHORT,
                    new CancellationToken(), l -> { }).exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public int removeLabeled(Map<String, String> labels) {
        List<String> ids = listLabeled("-aq", labels);
        if (ids.isEmpty()) return 0;
        List<String> rm = new ArrayList<>(List.of(docker, "rm", "-f"));
        rm.addAll(ids);
        runner.run(rm, Map.of(), null, RUN, new CancellationToken(), l -> { });
        return ids.size();
    }

    @Override
    public List<String> findRunning(Map<String, String> labels) {
        return listLabeled("-q", labels);
    }

    private List<String> listLabeled(String flags, Map<String, String> labels) {
        List<String> ps = new ArrayList<>(List.of(docker, "ps", flags));
        labels.forEach((k, v) -> {
            ps.add("--filter");
            ps.add("label=" + k + "=" + v);
        });
        List<String> ids = new ArrayList<>();
        runner.run(ps, Map.of(), null, SHORT, new CancellationToken(), l -> {
            if (!l.isBlank()) ids.add(l.trim());
        });
        return ids;
    }

    @Override
    public void ensureNetwork(String name, boolean internal) {
        List<String> out = new ArrayList<>();
        ExecResult exists = runner.run(List.of(docker, "network", "inspect", "-f", "{{.Internal}}", name), Map.of(), null,
                SHORT, new CancellationToken(), out::add);
        if (exists.exitCode() == 0) {
            boolean isInternal = out.stream().anyMatch(l -> l.trim().equals("true"));
            if (internal && !isInternal) {
                // Sandboxes on it would have a route out while everything reports "filtered".
                throw new IllegalStateException("network " + name + " exists but is not internal; remove it with "
                        + "`docker network rm " + name + "` (after stopping its containers) and restart");
            }
            return;
        }
        List<String> create = new ArrayList<>(List.of(docker, "network", "create"));
        if (internal) create.add("--internal");
        create.add(name);
        check(runner.run(create, Map.of(), null, SHORT, new CancellationToken(), l -> { }), "create network " + name);
    }

    @Override
    public void connect(String network, String containerId) {
        check(runner.run(List.of(docker, "network", "connect", network, containerId), Map.of(), null, SHORT,
                new CancellationToken(), l -> { }), "connect " + containerId + " to " + network);
    }

    @Override
    public String containerIp(String containerId, String network) {
        // Lists every network as name=ip; a template naming the network would need double quotes, which Windows
        // process creation mangles.
        List<String> out = new ArrayList<>();
        ExecResult r = runner.run(List.of(docker, "inspect", "-f",
                        "{{range $k, $v := .NetworkSettings.Networks}}{{$k}}={{$v.IPAddress}};{{end}}", containerId),
                Map.of(), null, SHORT, new CancellationToken(), out::add);
        if (r.exitCode() != 0) return "";
        for (String entry : String.join("", out).split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0 && entry.substring(0, eq).trim().equals(network)) return entry.substring(eq + 1).trim();
        }
        return "";
    }

    private static void check(ExecResult r, String what) {
        if (r.exitCode() != 0) throw new IllegalStateException(what + " failed (exit " + r.exitCode() + "): " + r.stderrTail());
    }

    /** Discards output. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
