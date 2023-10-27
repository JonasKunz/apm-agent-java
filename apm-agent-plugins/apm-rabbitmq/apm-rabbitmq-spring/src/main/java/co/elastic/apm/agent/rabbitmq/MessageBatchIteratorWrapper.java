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


import co.elastic.apm.agent.tracer.ElasticContext;
import co.elastic.apm.agent.tracer.Tracer;
import co.elastic.apm.agent.tracer.Transaction;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import org.springframework.amqp.core.Message;

import java.util.Iterator;

public class MessageBatchIteratorWrapper implements Iterator<Message> {

    public static final Logger logger = LoggerFactory.getLogger(MessageBatchIteratorWrapper.class);

    private final Iterator<Message> delegate;
    private final Tracer tracer;
    private final SpringAmqpTransactionHelper transactionHelper;

    private final ThreadLocal<ElasticContext<?>> activatedContext = new ThreadLocal<>();

    public MessageBatchIteratorWrapper(Iterator<Message> delegate, Tracer tracer, SpringAmqpTransactionHelper transactionHelper) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.transactionHelper = transactionHelper;
    }

    @Override
    public boolean hasNext() {
        endCurrentTransaction();
        return delegate.hasNext();
    }

    public void endCurrentTransaction() {
        try {
            ElasticContext<?> activated = activatedContext.get();
            if(activated != null) {
                activatedContext.remove();
                Transaction<?> transaction = activated.getTransaction();
                activated.deactivate();
                if (transaction != null) {
                    transaction.end();
                }
            }
        } catch (Exception e) {
            logger.error("Error in Spring AMQP iterator wrapper", e);
        }
    }

    @Override
    public Message next() {
        endCurrentTransaction();

        Message message = delegate.next();
        try {
            ElasticContext<?> ctx = transactionHelper.createAndActivateContext(message, AmqpConstants.SPRING_AMQP_TRANSACTION_PREFIX);
            if(ctx != null) {
                activatedContext.set(ctx);
            }
        } catch (Throwable throwable) {
            logger.error("Error in transaction creation based on Spring AMQP batch message", throwable);
        }
        return message;
    }

    @Override
    public void remove() {
        delegate.remove();
    }
}
