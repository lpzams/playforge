package dev.playforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the built web UI (web/dist) from the backend, so the whole platform is one address. Paths that are not
 * files fall back to index.html for client-side routes; /api/** and /preview/** never do.
 */
@Configuration(proxyBeanMethods = false)
public class WebUiConfig implements WebMvcConfigurer {

    private final PlayforgeProperties props;

    public WebUiConfig(PlayforgeProperties props) {
        this.props = props;
    }

    /** The resource handler does not resolve the empty path, so "/" is forwarded to the index explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (Files.isDirectory(props.webDir())) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = props.webDir();
        if (!Files.isDirectory(dir)) return;
        registry.addResourceHandler("/**")
                .addResourceLocations(dir.toUri().toString())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource r = location.createRelative(resourcePath);
                        if (!resourcePath.isEmpty() && r.exists() && r.isReadable()) return r;
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("preview/")) return null;
                        Resource index = location.createRelative("index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
