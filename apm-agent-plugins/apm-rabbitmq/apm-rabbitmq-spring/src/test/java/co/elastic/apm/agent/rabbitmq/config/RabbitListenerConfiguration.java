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
package co.elastic.apm.agent.rabbitmq.config;

import co.elastic.apm.agent.impl.TextHeaderMapAccessor;
import co.elastic.apm.agent.rabbitmq.TestConstants;
import co.elastic.apm.agent.tracer.GlobalTracer;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableRabbit
public class RabbitListenerConfiguration extends CommonRabbitListenerConfiguration {

    public static volatile ConcurrentHashMap<String, Map<String,String>> activeContextPerMessage = new ConcurrentHashMap<>();

    @RabbitListener(queues = TestConstants.QUEUE_NAME)
    public void processMessage(String message) {
        Map<String, String> context = new HashMap<>();
        GlobalTracer.get().currentContext().propagateContext(context, TextHeaderMapAccessor.INSTANCE, null);
        activeContextPerMessage.put(message, context);
        testSpan();
    }

}
