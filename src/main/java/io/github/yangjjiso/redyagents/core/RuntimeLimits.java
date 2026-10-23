package io.github.yangjjiso.redyagents.core;

import java.time.Duration;

/** Process-local resource limits, independent of Spring and of model/provider configuration. */
public record RuntimeLimits(int maxSteps, int maxConcurrentTurns, int maxQueuedTurns,
                            Duration turnTimeout, int maxSubscribers) {
    public static RuntimeLimits defaults() {
        return new RuntimeLimits(8, 16, 64, Duration.ofMinutes(5), 64);
    }

    public RuntimeLimits {
        if (maxSteps < 1 || maxConcurrentTurns < 1 || maxQueuedTurns < 0 || maxSubscribers < 1
                || turnTimeout == null || turnTimeout.isNegative() || turnTimeout.isZero()
                || turnTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("runtime limits must be positive (queue capacity may be zero)");
        }
    }
}
