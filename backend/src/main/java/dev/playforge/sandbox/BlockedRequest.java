package dev.playforge.sandbox;

/** Outbound connections the egress gateway refused for a sandbox: {@code count} attempts to host:port. */
public record BlockedRequest(String host, int port, int count) {}
