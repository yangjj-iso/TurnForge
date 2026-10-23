package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.core.RuntimeLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("redy.runtime")
public record RuntimeProperties(Integer maxSteps, Integer maxConcurrentTurns, Integer maxQueuedTurns,
                                 Duration turnTimeout, Integer maxSubscribers) {
    public RuntimeLimits toLimits() {
        RuntimeLimits defaults = RuntimeLimits.defaults();
        return new RuntimeLimits(maxSteps == null ? defaults.maxSteps() : maxSteps,
                maxConcurrentTurns == null ? defaults.maxConcurrentTurns() : maxConcurrentTurns,
                maxQueuedTurns == null ? defaults.maxQueuedTurns() : maxQueuedTurns,
                turnTimeout == null ? defaults.turnTimeout() : turnTimeout,
                maxSubscribers == null ? defaults.maxSubscribers() : maxSubscribers);
    }
}
