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
package co.elastic.apm.agent.kafka.helper;

import co.elastic.apm.agent.common.util.WildcardMatcher;
import co.elastic.apm.agent.sdk.internal.util.PrivilegedActionUtils;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import co.elastic.apm.agent.tracer.ElasticContext;
import co.elastic.apm.agent.tracer.Tracer;
import co.elastic.apm.agent.tracer.Transaction;
import co.elastic.apm.agent.tracer.configuration.CoreConfiguration;
import co.elastic.apm.agent.tracer.configuration.MessagingConfiguration;
import co.elastic.apm.agent.tracer.metadata.Message;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;

import java.util.Iterator;

class ConsumerRecordsIteratorWrapper implements Iterator<ConsumerRecord<?, ?>> {

    public static final Logger logger = LoggerFactory.getLogger(ConsumerRecordsIteratorWrapper.class);
    public static final String FRAMEWORK_NAME = "Kafka";

    private final Iterator<ConsumerRecord<?, ?>> delegate;
    private final Tracer tracer;
    private final CoreConfiguration coreConfiguration;
    private final MessagingConfiguration messagingConfiguration;

    private final ThreadLocal<ElasticContext<?>> startedContext = new ThreadLocal<>();

    public ConsumerRecordsIteratorWrapper(Iterator<ConsumerRecord<?, ?>> delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
        coreConfiguration = tracer.getConfig(CoreConfiguration.class);
        messagingConfiguration = tracer.getConfig(MessagingConfiguration.class);
    }

    @Override
    public boolean hasNext() {
        endCurrentTransaction();
        return delegate.hasNext();
    }

    public void endCurrentTransaction() {
        try {
            ElasticContext<?> context = startedContext.get();
            if(context != null) {
                startedContext.remove();
                if(context.getTransaction() != null) {
                    // end our previously started messaging transaction
                    context.getTransaction().deactivate().end();
                } else {
                    context.deactivate(); //propagation-only context needs to be ended aswell
                }
            }
        } catch (Exception e) {
            logger.error("Error in Kafka iterator wrapper", e);
        }
    }

    @Override
    public ConsumerRecord<?, ?> next() {
        endCurrentTransaction();
        ConsumerRecord<?, ?> record = delegate.next();
        try {
            String topic = record.topic();
            if (!WildcardMatcher.isAnyMatch(messagingConfiguration.getIgnoreMessageQueues(), topic)) {
                Transaction<?> transaction = tracer.startChildTransaction(record, KafkaRecordHeaderAccessor.instance(), PrivilegedActionUtils.getClassLoader(ConsumerRecordsIteratorWrapper.class));
                if (transaction != null) {
                    transaction.withType("messaging").withName("Kafka record from " + topic).activate();
                    startedContext.set(tracer.currentContext());
                    transaction.setFrameworkName(FRAMEWORK_NAME);

                    Message message = transaction.getContext().getMessage();
                    message.withQueue(topic);
                    if (record.timestampType() == TimestampType.CREATE_TIME) {
                        message.withAge(System.currentTimeMillis() - record.timestamp());
                    }

                    if (transaction.isSampled() && coreConfiguration.isCaptureHeaders()) {
                        for (Header header : record.headers()) {
                            String key = header.key();
                            if (!tracer.getTraceHeaderNames().contains(key) &&
                                WildcardMatcher.anyMatch(coreConfiguration.getSanitizeFieldNames(), key) == null) {
                                message.addHeader(key, header.value());
                            }
                        }
                    }

                    if (transaction.isSampled() && coreConfiguration.getCaptureBody() != CoreConfiguration.EventType.OFF) {
                        message.appendToBody("key=").appendToBody(String.valueOf(record.key())).appendToBody("; ")
                            .appendToBody("value=").appendToBody(String.valueOf(record.value()));
                    }
                } else {
                    ElasticContext<?> propagationOnlyCtx = tracer.currentContext().withContextPropagationOnly(record, KafkaRecordHeaderAccessor.instance());
                    if(propagationOnlyCtx != null) {
                        propagationOnlyCtx.activate();
                        startedContext.set(propagationOnlyCtx);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in transaction creation based on Kafka record", e);
        }
        return record;
    }

    @Override
    public void remove() {
        delegate.remove();
    }
}
