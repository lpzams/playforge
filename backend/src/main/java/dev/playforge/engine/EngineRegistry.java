package dev.playforge.engine;

import dev.playforge.config.PlayforgeProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EngineRegistry {

    private final List<AgentEngine> engines;
    private final PlayforgeProperties props;

    public EngineRegistry(List<AgentEngine> engines, PlayforgeProperties props) {
        this.engines = engines;
        this.props = props;
    }

    public AgentEngine coder() {
        return find(props.engines().coder(), AgentRole.CODER);
    }

    public AgentEngine reviewer() {
        return find(props.engines().reviewer(), AgentRole.REVIEWER);
    }

    private AgentEngine find(String name, AgentRole role) {
        return engines.stream()
                .filter(e -> e.name().equals(name) && e.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + role + " engine named " + name));
    }
}
