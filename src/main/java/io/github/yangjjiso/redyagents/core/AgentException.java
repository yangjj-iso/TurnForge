package io.github.yangjjiso.redyagents.core;

public final class AgentException extends RuntimeException {
    public enum Reason { INVALID, NOT_FOUND, CONFLICT, UNAVAILABLE }

    private final Reason reason;
    private final String sessionId;
    private final String turnId;

    public AgentException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    private AgentException(Reason reason, String message, String sessionId, String turnId) {
        super(message);
        this.reason = reason;
        this.sessionId = sessionId;
        this.turnId = turnId;
    }

    public Reason reason() {
        return reason;
    }

    public String sessionId() { return sessionId; }

    public String turnId() { return turnId; }

    public static AgentException invalid() {
        return new AgentException(Reason.INVALID, "invalid request");
    }

    public static AgentException notFound() {
        return new AgentException(Reason.NOT_FOUND, "resource not found");
    }

    public static AgentException conflict() {
        return new AgentException(Reason.CONFLICT, "session already has an active turn");
    }

    public static AgentException unavailable(String message) {
        return new AgentException(Reason.UNAVAILABLE, message);
    }

    public static AgentException unavailable(String message, String sessionId, String turnId) {
        return new AgentException(Reason.UNAVAILABLE, message, sessionId, turnId);
    }
}
