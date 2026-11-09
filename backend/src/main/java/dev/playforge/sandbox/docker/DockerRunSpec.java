package dev.playforge.sandbox.docker;

import java.util.List;
import java.util.Map;

/**
 * How pool containers are started.
 *
 * @param envFromHost container variable name → host variable name. Only names reach the docker command line;
 *                    values travel through the docker client's environment.
 * @param mounts      read-only bind mounts
 * @param labels      container labels; used to find containers left behind by a previous process
 */
public record DockerRunSpec(String image, Map<String, String> envFromHost, List<Mount> mounts,
                            String cpus, String memory, int pidsLimit, Map<String, String> labels) {

    public record Mount(String source, String target) {}
}
