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
package co.elastic.apm.agent.impl.transaction;

import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.baggage.Baggage;
import co.elastic.apm.agent.impl.baggage.BaggageContext;
import co.elastic.apm.agent.impl.baggage.W3CBaggagePropagation;
import co.elastic.apm.agent.tracer.Id;
import co.elastic.apm.agent.tracer.Scope;
import co.elastic.apm.agent.tracer.dispatch.HeaderGetter;
import co.elastic.apm.agent.tracer.dispatch.HeaderSetter;
import co.elastic.apm.agent.tracer.dispatch.HeaderUtils;

import javax.annotation.Nullable;

public abstract class ElasticContext<T extends ElasticContext<T>> implements co.elastic.apm.agent.tracer.ElasticContext<T> {

    protected final ElasticApmTracer tracer;

    protected ElasticContext(ElasticApmTracer tracer) {
        this.tracer = tracer;
    }

    @Nullable
    @Override
    public abstract AbstractSpan<?> getSpan();

    @Nullable
    public abstract TraceContext getRemoteParent();

    @Override
    public abstract Baggage getBaggage();

    public final ElasticApmTracer getTracer() {
        return tracer;
    }

    /**
     * @return transaction associated to this context, {@literal null} if there is none
     */
    @Nullable
    public final Transaction getTransaction() {
        AbstractSpan<?> contextSpan = getSpan();
        return contextSpan != null ? contextSpan.getParentTransaction() : null;
    }

    @Nullable
    @Override
    public Id getTraceId() {
        AbstractSpan<?> span = getSpan();
        if(span != null) {
            return span.getTraceContext().getTraceId();
        }
        TraceContext remoteParent = getRemoteParent();
        if(remoteParent != null) {
            return remoteParent.getTraceId();
        }
        return null;
    }

    @Nullable
    @Override
    public <C> ElasticContext<?> withRemoteParent(C carrier, HeaderGetter<?, C> headerGetter) {
        if (getTraceId() == null) { //only read remote parent if we have no active trace
            //TODO: use pooling instead
            RemoteParentContext parent = new RemoteParentContext(getTracer());
            parent.fill(carrier, headerGetter);
            if(!parent.isEmpty()) {
                return parent;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T activate() {
        tracer.activate(this);
        return (T) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deactivate() {
        tracer.deactivate(this);
        return (T) this;
    }

    @Override
    public Scope activateInScope() {
        return tracer.activateInScope(this);
    }

    @Override
    public BaggageContext.Builder withUpdatedBaggage() {
        return BaggageContext.createBuilder(this);
    }

    @Nullable
    @Override
    public co.elastic.apm.agent.impl.transaction.Span createSpan() {
        AbstractSpan<?> currentSpan = getSpan();
        return currentSpan == null ? null : currentSpan.createSpan(getBaggage());
    }

    @Nullable
    @Override
    public final co.elastic.apm.agent.impl.transaction.Span createExitSpan() {
        AbstractSpan<?> contextSpan = getSpan();
        if (contextSpan == null || contextSpan.isExit()) {
            return null;
        }
        return createSpan().asExit();
    }

    public boolean isEmpty() {
        return getSpan() == null && getBaggage().isEmpty() && getRemoteParent() == null;
    }

    @Override
    public final <C> void propagateContext(C carrier, HeaderSetter<?, C> headerSetter, @Nullable HeaderGetter<?, C> headerGetter) {
        propagateContext(carrier, headerSetter, carrier, headerGetter);
    }

    @Override
    public <C1, C2> void propagateContext(C1 carrier, HeaderSetter<?, C1> headerSetter, @Nullable C2 carrier2, @Nullable HeaderGetter<?, C2> headerGetter) {
        AbstractSpan<?> contextSpan = getSpan();
        TraceContext remoteParent = getRemoteParent();
        if (contextSpan != null || remoteParent != null) {
            if (headerGetter == null || carrier2 == null || !HeaderUtils.containsAny(TraceContext.TRACE_TEXTUAL_HEADERS, carrier2, headerGetter)) {
                if(contextSpan != null) {
                    contextSpan.setNonDiscardable();
                    contextSpan.getTraceContext().propagateTraceContext(carrier, headerSetter);
                } else {
                    remoteParent.propagateTraceContext(carrier, headerSetter);
                }
            }
        }
        Baggage baggage = getBaggage();
        if (!baggage.isEmpty()) {
            if (headerGetter == null || carrier2 == null || headerGetter.getFirstHeader(W3CBaggagePropagation.BAGGAGE_HEADER_NAME, carrier2) == null) {
                W3CBaggagePropagation.propagate(baggage, carrier, headerSetter);
            }
        }
    }

    @Override
    public <C> boolean isPropagationRequired(C carrier, HeaderGetter<?, C> headerGetter) {
        boolean traceContextPropagationRequired = getTraceId() != null && !HeaderUtils.containsAny(TraceContext.TRACE_TEXTUAL_HEADERS, carrier, headerGetter);
        boolean baggagePropagationRequired = !getBaggage().isEmpty() && headerGetter.getFirstHeader(W3CBaggagePropagation.BAGGAGE_HEADER_NAME, carrier) == null;
        return traceContextPropagationRequired || baggagePropagationRequired;
    }

    @Override
    public final boolean shouldSkipChildSpanCreation() {
        Transaction contextTransaction = getTransaction();
        return contextTransaction == null || contextTransaction.checkSkipChildSpanCreation();
    }
}
