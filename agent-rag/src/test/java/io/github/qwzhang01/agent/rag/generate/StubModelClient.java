package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

final class StubModelClient implements ModelClient {

    final List<ModelRequest> requests = new ArrayList<>();
    private final Function<ModelRequest, ModelResponse> handler;

    StubModelClient(Function<ModelRequest, ModelResponse> handler) {
        this.handler = handler;
    }

    static StubModelClient replying(String content, int promptTokens, int completionTokens) {
        return new StubModelClient(r -> new ModelResponse(content, null, "stop",
                new ModelResponse.TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens)));
    }

    static StubModelClient failing() {
        return new StubModelClient(r -> {
            throw new AssertionError("model must not be called");
        });
    }

    String lastUserPrompt() {
        ModelRequest last = requests.get(requests.size() - 1);
        return last.messages().get(last.messages().size() - 1).content();
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        requests.add(request);
        return handler.apply(request);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        throw new UnsupportedOperationException();
    }
}
