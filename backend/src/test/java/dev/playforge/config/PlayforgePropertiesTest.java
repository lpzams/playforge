package dev.playforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "playforge.workspace-root=../relative-workspaces")
@ActiveProfiles("test")
class PlayforgePropertiesTest {

    @Autowired
    PlayforgeProperties props;

    @Test
    void relativeWorkspaceRootResolvesAgainstWorkingDirectory() {
        assertThat(props.workspaceDir()).isEqualTo(Path.of("../relative-workspaces").toAbsolutePath().normalize());
    }
}
