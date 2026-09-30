package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.RagTrace;

/** Receives one trace per pipeline call. Exceptions thrown here are logged and swallowed. */
@FunctionalInterface
public interface RagTraceListener {

    void onTrace(RagTrace trace);
}
