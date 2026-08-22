package io.github.qwzhang01.agent.model.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ContentPart;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.GenerationTools;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void describeImageSendsMultimodalRequest() {
        CapturingClient client = new CapturingClient();
        client.response = ModelResponse.text("an orange cat");

        var tool = new VisionTool(client);
        assertEquals(GenerationTools.DESCRIBE_IMAGE, tool.getName());

        var args = mapper.createObjectNode()
                .put("question", "what animal?")
                .put("image_url", "https://example.com/cat.png");
        String result = tool.execute(args);

        assertEquals("an orange cat", result);
        ChatMessage user = client.lastRequest.messages().get(0);
        assertEquals(ChatRole.USER, user.role());
        assertEquals(2, user.parts().size());
        assertEquals("what animal?", ((ContentPart.TextPart) user.parts().get(0)).text());
        assertInstanceOf(ContentPart.ImagePart.class, user.parts().get(1));
    }

    @Test
    void missingImageReturnsError() {
        var tool = new VisionTool(new CapturingClient());
        String result = tool.execute(mapper.createObjectNode().put("question", "hi"));
        assertTrue(result.startsWith("Error:"));
    }

    static class CapturingClient implements ModelClient {
        ModelRequest lastRequest;
        ModelResponse response = ModelResponse.text("ok");

        @Override
        public ModelResponse chat(ModelRequest request) {
            lastRequest = request;
            return response;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }
}
