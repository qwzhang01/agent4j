package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test helper: records every request then delegates.
 */
final class RecordingModelClient implements ModelClient {

    private final ModelClient delegate;
    final List<ModelRequest> requests = new ArrayList<>();

    RecordingModelClient(ModelClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        requests.add(request);
        return delegate.chat(request);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        requests.add(request);
        return delegate.stream(request);
    }
}
