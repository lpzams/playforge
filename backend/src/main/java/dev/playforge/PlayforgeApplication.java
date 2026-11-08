package dev.playforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PlayforgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlayforgeApplication.class, args);
    }
}
