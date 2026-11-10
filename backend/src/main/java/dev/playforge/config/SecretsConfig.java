package dev.playforge.config;

import dev.playforge.engine.SecretRedactor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class SecretsConfig {

    @Bean
    SecretRedactor secretRedactor(PlayforgeProperties props) {
        return SecretRedactor.fromSources(System.getenv("PLAYFORGE_CLAUDE_TOKEN"),
                Path.of(props.sandbox().codexAuthFile()));
    }
}
