package io.github.qwzhang01.agent.mcp.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 M10.1 tests: StdioTransport with `cat` as an echo subprocess.
 * <p>
 * `cat` reads lines from stdin and echoes them to stdout -- perfect as a mock
 * MCP server for transport-level testing (no real protocol needed).
 * <p>
 * Tests are gated on macOS/Linux (where `cat` is available). Windows would need
 * `cmd /c type` or similar.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class StdioTransportTest {

    private StdioTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        // `cat` echoes stdin to stdout line-by-line
        transport = new StdioTransport("cat");
        transport.open();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void open_subprocessStarts() {
        assertTrue(transport.isOpen());
    }

    @Test
    void sendReceive_echoRoundTrip() throws IOException {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

        transport.send(message);
        String received = transport.receive();

        assertEquals(message, received);
    }

    @Test
    void sendReceive_multipleMessages() throws IOException {
        for (int i = 1; i <= 5; i++) {
            String msg = "{\"id\":" + i + "}";
            transport.send(msg);
            String received = transport.receive();
            assertEquals(msg, received);
        }
    }

    @Test
    void isOpen_afterClose_returnsFalse() throws IOException {
        transport.close();
        assertFalse(transport.isOpen());
        transport = null;  // prevent double-close in tearDown
    }

    @Test
    void send_whenClosed_throws() throws IOException {
        transport.close();
        assertThrows(IOException.class, () -> transport.send("test"));
        transport = null;  // prevent double-close in tearDown
    }

    @Test
    void close_idempotent() throws IOException {
        transport.close();
        // second close should not throw
        transport.close();
        transport = null;
    }
}
