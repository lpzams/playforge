package dev.playforge.sandbox.docker;

import java.util.List;
import java.util.Map;

/**
 * How a container is started.
 *
 * @param envFromHost  container variable name → host variable name. Only names reach the docker command line;
 *                     values travel through the docker client's environment. For secrets.
 * @param mounts       read-only bind mounts
 * @param labels       container labels; used to find containers left behind by a previous process
 * @param name         container name, or null
 * @param network      network to attach to instead of the default bridge, or null
 * @param networkAlias DNS name of the container on {@code network}, or null
 * @param env          literal variables (not secrets: they appear on the docker command line)
 * @param command      command after the image; empty = the image's default
 */
public record DockerRunSpec(String image, Map<String, String> envFromHost, List<Mount> mounts,
                            String cpus, String memory, int pidsLimit, Map<String, String> labels,
                            String name, String network, String networkAlias, Map<String, String> env,
                            List<String> command) {

    public record Mount(String source, String target) {}

    public DockerRunSpec(String image, Map<String, String> envFromHost, List<Mount> mounts,
                         String cpus, String memory, int pidsLimit, Map<String, String> labels) {
        this(image, envFromHost, mounts, cpus, memory, pidsLimit, labels, null, null, null, Map.of(), List.of());
    }

    /** A copy attached to {@code network} with these literal variables. */
    public DockerRunSpec withNetwork(String network, Map<String, String> env) {
        return new DockerRunSpec(image, envFromHost, mounts, cpus, memory, pidsLimit, labels, name, network,
                networkAlias, env, command);
    }
}
