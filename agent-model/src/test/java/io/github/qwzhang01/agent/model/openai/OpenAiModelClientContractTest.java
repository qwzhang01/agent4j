package io.github.qwzhang01.agent.model.openai;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.model.contract.ModelClientContract;
import io.github.qwzhang01.agent.model.testsupport.FakeHttpClient;

import java.time.Duration;

/**
 * Stage 6.1: OpenAI-compatible run of the shared {@link ModelClientContract}.
 * <p>
 * Pins the mapping logic of {@link OpenAiModelClient} (chat completions
 * wire format) against a canned HTTP layer: sync text, tool calls, SSE
 * streaming, and the four error translations.
 */
class OpenAiModelClientContractTest extends ModelClientContract {

    private static final Duration NEVER = Duration.ofSeconds(30);

    @Override
    protected String providerName() {
        return "openai";
    }

    @Override
    protected ModelClient clientFor(String httpBody) {
        return new OpenAiModelClient(
                "http://fake.local", "test-key", "gpt-test", NEVER,
                null, null, null, FakeHttpClient.ok(httpBody));
    }

    @Override
    protected ModelClient clientForError(int status, String body) {
        return new OpenAiModelClient(
                "http://fake.local", "test-key", "gpt-test", NEVER,
                null, null, null, FakeHttpClient.error(status, body));
    }

    @Override
    protected String syncBody() {
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "hello from gpt"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}
                }
                """;
    }

    @Override
    protected String toolCallBody() {
        return """
                {
                  "id": "chatcmpl-tool",
                  "object": "chat.completion",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"SF\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}
                }
                """;
    }

    @Override
    protected String streamBody() {
        return """
                data: {"id":"chatcmpl-s","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"hi "},"finish_reason":null}]}

                data: {"id":"chatcmpl-s","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"stream"},"finish_reason":null}]}

                data: {"id":"chatcmpl-s","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]
                """;
    }

    @Override
    protected String streamToolCallBody() {
        // OpenAI streams tool calls by splitting function.arguments across
        // chunks with a stable tool call index; accumulation must stitch
        // them into one ToolCall on the terminal chunk.
        return """
                data: {"id":"chatcmpl-t","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_stream_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-t","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-t","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"SF\\"}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-t","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
    }
}
