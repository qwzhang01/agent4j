package io.github.qwzhang01.agent.product;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 registry tests: registration, lookup, duplicate rejection, name listings.
 */
class ProductContextTest {

    // ============ Minimal doubles ============

    private static final ModelClient CLIENT_A = new ThrowingModelClient();
    private static final ModelClient CLIENT_B = new ThrowingModelClient();

    private record NoopTool(String name) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "noop";
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "noop";
        }
    }

    private static final class ThrowingModelClient implements ModelClient {
        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new UnsupportedOperationException("not called");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            throw new UnsupportedOperationException("not called");
        }
    }

    // ============ Tests ============

    @Test
    void registerAndLookup() {
        ProductContext ctx = new ProductContext()
                .registerModel("openai", CLIENT_A)
                .registerModel("deepseek", CLIENT_B)
                .registerTool("order-query", new NoopTool("order-query"));

        assertTrue(ctx.model("openai").isPresent());
        assertEquals(CLIENT_B, ctx.model("deepseek").orElseThrow());
        assertTrue(ctx.tool("order-query").isPresent());
        assertFalse(ctx.model("claude").isPresent());
        assertFalse(ctx.tool("nope").isPresent());
    }

    @Test
    void duplicateRegistrationFailsFast() {
        ProductContext ctx = new ProductContext().registerModel("openai", CLIENT_A);
        assertThrows(IllegalArgumentException.class,
                () -> ctx.registerModel("openai", CLIENT_B));
    }

    @Test
    void blankNamesAreRejected() {
        ProductContext ctx = new ProductContext();
        assertThrows(IllegalArgumentException.class, () -> ctx.registerModel(" ", CLIENT_A));
        assertThrows(IllegalArgumentException.class, () -> ctx.registerTool(null, new NoopTool("x")));
    }

    @Test
    void nameListingsPreserveRegistrationOrder() {
        ProductContext ctx = new ProductContext()
                .registerModel("b-model", CLIENT_A)
                .registerModel("a-model", CLIENT_B);

        assertEquals(java.util.List.of("b-model", "a-model"), ctx.modelNames());
    }
}
