/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.rabbitmq;

import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.rabbitmq.config.RabbitListenerConfiguration;
import co.elastic.apm.agent.tracer.configuration.MessagingConfiguration;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

import static co.elastic.apm.agent.rabbitmq.TestConstants.TOPIC_EXCHANGE_NAME;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;

@RunWith(SpringRunner.class)
@SpringBootTest
@ContextConfiguration(classes = {RabbitListenerConfiguration.class}, initializers = {AbstractRabbitMqTest.Initializer.class})
public class RabbitListenerIT extends AbstractRabbitMqTest {

    @Test
    public void testContextPropagationOnly() {
        RabbitListenerConfiguration.activeContextPerMessage.clear();

        doReturn(true).when(tracer.getConfig(CoreConfiguration.class)).isContextPropagationOnly();

        rabbitTemplate.convertAndSend(TOPIC_EXCHANGE_NAME, TestConstants.ROUTING_KEY, "msg1");
        rabbitTemplate.convertAndSend(TOPIC_EXCHANGE_NAME, TestConstants.ROUTING_KEY, "msg2");

        await().untilAsserted(() -> Assertions.assertThat(RabbitListenerConfiguration.activeContextPerMessage)
            .containsKeys("msg1", "msg2"));

        Map<String,String> msg1Ctx = RabbitListenerConfiguration.activeContextPerMessage.get("msg1");
        Map<String,String> msg2Ctx = RabbitListenerConfiguration.activeContextPerMessage.get("msg2");

        String msg1TraceParent = msg1Ctx.get("traceparent");
        String msg2TraceParent = msg2Ctx.get("traceparent");

        Assertions.assertThat(msg1TraceParent).isNotEmpty();
        Assertions.assertThat(msg2TraceParent).isNotEmpty();

        Assertions.assertThat(msg1TraceParent).isNotEqualTo(msg2TraceParent);
    }
}
