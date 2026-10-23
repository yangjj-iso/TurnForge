package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.core.ExternalFunctionTool;
import io.github.yangjjiso.redyagents.core.Tool;
import io.github.yangjjiso.redyagents.core.ToolDefinition;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Opt-in examples for local tools and application-owned approval handoff. */
@Configuration(proxyBeanMethods = false)
@Profile("example-tools")
public class ExampleToolsConfiguration {
    @Bean("approval")
    Tool approval() {
        return new ExternalFunctionTool() {
            @Override
            public ToolDefinition definition(String registeredName) {
                return new ToolDefinition(registeredName,
                        "Ask the application or user to approve a proposed action before proceeding.",
                        Map.of("type", "object", "properties", Map.of("reason", Map.of("type", "string")),
                                "required", java.util.List.of("reason"), "additionalProperties", false));
            }
        };
    }
}
