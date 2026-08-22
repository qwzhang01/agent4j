package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentPartJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void imagePartRoundTripsThroughJackson() throws Exception {
        ContentPart original = ContentPart.imageByUrl("https://example.com/cat.png");
        String json = mapper.writeValueAsString(original);
        ContentPart back = mapper.readValue(json, ContentPart.class);

        ContentPart.ImagePart image = assertInstanceOf(ContentPart.ImagePart.class, back);
        assertEquals("https://example.com/cat.png", image.url());
        assertNull(image.base64Data());
    }

    @Test
    void multimodalMessageRoundTrips() throws Exception {
        ChatMessage original = ChatMessage.user(List.of(
                ContentPart.text("what is this?"),
                ContentPart.imageByUrl("https://example.com/cat.png")));

        String json = mapper.writeValueAsString(original);
        ChatMessage back = mapper.readValue(json, ChatMessage.class);

        assertEquals(ChatRole.USER, back.role());
        assertEquals(2, back.parts().size());
        assertInstanceOf(ContentPart.TextPart.class, back.parts().get(0));
        assertInstanceOf(ContentPart.ImagePart.class, back.parts().get(1));
    }
}
