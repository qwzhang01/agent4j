package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void mockProviderCreatesBeansAndFactoryRunWorks() {
        contextRunner
                .withPropertyValues("agent4j.model.provider=mock")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context.getBean(ModelClient.class)).isInstanceOf(MockModelClient.class);

                    AgentFactory factory = context.getBean(AgentFactory.class);
                    Agent agent = factory.create("mock-agent", "You are a test agent.");
                    String result = agent.run("hi");
                    assertThat(result).isNotBlank();

                    Agent streamAgent = factory.create("mock-stream", "You are a test agent.");
                    java.util.List<io.github.qwzhang01.agent.core.agent.AgentEvent> events =
                            new java.util.ArrayList<>();
                    streamAgent.stream("hi", events::add);
                    assertThat(events).isNotEmpty();
                    assertThat(events.get(events.size() - 1))
                            .isInstanceOf(io.github.qwzhang01.agent.core.agent.AgentEvent.Done.class);
                });
    }

    @Test
    void openaiProviderCreatesOpenAiModelClientWithoutCallingNetwork() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=openai",
                        "agent4j.model.api-key=sk-dummy",
                        "agent4j.retry.enabled=false",
                        "agent4j.call-timeout.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context.getBean(ModelClient.class)).isInstanceOf(OpenAiModelClient.class);
                    assertThat(context).hasSingleBean(AgentFactory.class);
                });
    }

    @Test
    void disabledPropertySkipsAutoConfigBeans() {
        contextRunner
                .withPropertyValues("agent4j.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ModelClient.class);
                    assertThat(context).doesNotHaveBean(AgentFactory.class);
                    assertThat(context).doesNotHaveBean(AgentAutoConfiguration.class);
                });
    }

    @Test
    void userProvidedModelClientIsNotOverridden() {
        contextRunner
                .withUserConfiguration(UserModelClientConfig.class)
                .withPropertyValues(
                        "agent4j.model.provider=openai",
                        "agent4j.model.api-key=sk-dummy")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context.getBean(ModelClient.class)).isSameAs(UserModelClientConfig.INSTANCE);
                    assertThat(context.getBean(ModelClient.class)).isNotInstanceOf(OpenAiModelClient.class);
                    assertThat(context).hasSingleBean(AgentFactory.class);
                });
    }

    @Configuration
    static class UserModelClientConfig {

        static final ModelClient INSTANCE = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                return ModelResponse.text("user-provided");
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                return Stream.empty();
            }
        };

        @Bean
        ModelClient modelClient() {
            return INSTANCE;
        }
    }
}
