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
package co.elastic.apm.agent.universalprofiling;

import co.elastic.apm.agent.impl.ActivationListener;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.ElasticContext;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.jvmti.JVMTIAgent;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class ProfilingActivationCorrelator implements ActivationListener {

    /**
     * Flag which indicates whether there is a trace context active.
     */
    public static final long TRACE_ACTIVE_FLAG = 256;

    private static final int TRACE_ID_OFFSET = 0;
    private static final int SPAN_ID_OFFSET = 16;
    private static final int TRACE_FLAGS_OFFSET = 24;
    private static final int TRANSACTION_ID_OFFSET = 32;

    private static final int BUFFER_CAPACITY = 40;

    private final ElasticApmTracer tracer;

    private final ThreadLocal<ByteBuffer> threadCorrelationBuffer = new ThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
            buffer.order(ByteOrder.nativeOrder());
            JVMTIAgent.setProfilerCurrentThreadStorage(buffer);
            return buffer;
        }
    };

    public ProfilingActivationCorrelator(ElasticApmTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void beforeActivate(AbstractSpan<?> span) throws Throwable {
        updateCorrelationBuffer(span);
    }

    @Override
    public void afterDeactivate(@Nullable AbstractSpan<?> deactivatedSpan) throws Throwable {
        ElasticContext<?> currentContext = tracer.currentContext();
        AbstractSpan<?> span = currentContext == null ? null : currentContext.getSpan();
        updateCorrelationBuffer(span);
    }

    private void updateCorrelationBuffer(@Nullable AbstractSpan<?> span) {
        ByteBuffer byteBuffer = threadCorrelationBuffer.get();
        if (span == null) {
            for (int offset = 0; offset < BUFFER_CAPACITY; offset += 8) {
                byteBuffer.putLong(offset, 0L);
            }
        } else {
            TraceContext traceContext = span.getTraceContext();
            byteBuffer.position(TRACE_ID_OFFSET);
            traceContext.getTraceId().writeToBuffer(byteBuffer);
            byteBuffer.position(SPAN_ID_OFFSET);
            traceContext.getId().writeToBuffer(byteBuffer);
            long flags = traceContext.getFlags();
            flags |= TRACE_ACTIVE_FLAG;
            byteBuffer.putLong(TRACE_FLAGS_OFFSET, flags);

            byteBuffer.position(TRANSACTION_ID_OFFSET);
            traceContext.getTransactionId().writeToBuffer(byteBuffer);
        }

    }

}
