package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yangjjiso.redyagents.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Timeout(10)
class RuntimeCompositionTest {
    @TempDir Path directory;

    @Test
    void applicationToolBeansJoinDefaultHarnessAndResumeWithoutCustomRunner() {
        new ApplicationContextRunner().withUserConfiguration(RedyAgentsConfiguration.class, Tools.class)
                .withPropertyValues("redy.data-dir=" + directory, "redy.runtime.max-steps=2")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentService service = context.getBean(AgentService.class);
                    String id = service.createSession(new AgentConfig("example", "test", "")).id();
                    Turn turn = service.startTurn(id, "lookup");
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    while (!"requires_action".equals(service.getSession(id).status())) {
                        if (System.nanoTime() > deadline) fail("function tool did not pause: " + service.getTurn(id, turn.id()));
                        Thread.sleep(5);
                    }
                    assertEquals("lookup", service.getSession(id).requiredActions().get(0).name());
                    service.submitToolResult(id, turn.id(), "call_lookup", true, "found", null);
                    while (!"completed".equals(service.getTurn(id, turn.id()).status())) {
                        if (System.nanoTime() > deadline) fail("function tool did not resume");
                        Thread.sleep(5);
                    }
                    assertTrue(context.getBean(Tools.class).catalogSeen.get());
                });
    }

    @Test
    void invalidRuntimeConfigurationFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(RedyAgentsConfiguration.class)
                .withPropertyValues("redy.data-dir=" + directory, "redy.runtime.max-concurrent-turns=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    static class Tools {
        final AtomicBoolean catalogSeen = new AtomicBoolean();

        @Bean Tool lookup() { return new ExternalFunctionTool() {}; }

        @Bean Model model() {
            return new Model() {
                public Decision next(CancellationToken token, AgentConfig agent, List<Message> messages) {
                    throw new AssertionError("catalog overload is required");
                }
                public Decision next(CancellationToken token, AgentConfig agent, List<Message> messages,
                                     List<ToolDefinition> tools) {
                    catalogSeen.set(tools.stream().anyMatch(tool -> "lookup".equals(tool.name())));
                    return "tool".equals(messages.get(messages.size() - 1).role())
                            ? Decision.finalMessage("done")
                            : Decision.toolCall("lookup", new ToolCall("lookup",
                                    "{}".getBytes(StandardCharsets.UTF_8), "call_lookup"));
                }
            };
        }
    }
}
