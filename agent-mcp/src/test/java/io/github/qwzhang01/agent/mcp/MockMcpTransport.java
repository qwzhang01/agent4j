package io.github.qwzhang01.agent.mcp;

import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mock MCP transport for testing (Stage 10 M10.2).
 * <p>
 * Instead of launching a real subprocess, this transport:
 * 1. Parses each sent message to extract the JSON-RPC method
 * 2. Returns a pre-canned response for that method on the next receive()
 * <p>
 * Register expected responses via {@link #registerResponse(String, String)}.
 */
public class MockMcpTransport implements McpTransport {

    private final java.util.Map<String, String> responses = new java.util.concurrent.ConcurrentHashMap<>();
    private final Queue<String> responseQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean open = false;

    /**
     * Register a response for a given JSON-RPC method.
     */
    public void registerResponse(String method, String responseJson) {
        responses.put(method, responseJson);
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void send(String json) {
        if (!open) throw new RuntimeException("Transport not open");

        // Parse method from the request
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String method = node.has("method") ? node.get("method").asText() : "";

            // Special handling: "notifications/initialized" has no response
            if (method.startsWith("notifications/")) {
                return;  // fire-and-forget, no response queued
            }

            // Look up pre-canned response for this method
            String resp = responses.get(method);
            if (resp != null) {
                // If the canned response has a placeholder for id, replace it
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode reqNode = mapper.readTree(json);
                com.fasterxml.jackson.databind.JsonNode respNode = mapper.readTree(resp);
                com.fasterxml.jackson.databind.node.ObjectNode respObj = (com.fasterxml.jackson.databind.node.ObjectNode) respNode;
                respObj.set("id", reqNode.get("id"));
                responseQueue.add(respObj.toString());
            } else {
                // Default: empty success response
                responseQueue.add("{\"jsonrpc\":\"2.0\",\"id\":" +
                        (node.has("id") ? node.get("id").toString() : "0") +
                        ",\"result\":{}}");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process sent message: " + e.getMessage(), e);
        }
    }

    @Override
    public String receive() {
        String resp = responseQueue.poll();
        if (resp == null) {
            throw new RuntimeException("No response queued");
        }
        return resp;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }
}
