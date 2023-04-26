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

import co.elastic.apm.agent.MockTracer;
import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.configuration.SpyConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.jvmti.JVMTIAgentAccess;
import org.junit.jupiter.api.Test;
import org.stagemonitor.configuration.ConfigurationRegistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.doReturn;

public class UniversalProfilingTest {


    @Test
    void testProcessStorage() {
        ConfigurationRegistry config = SpyConfiguration.createSpyConfig();
        CoreConfiguration coreConfig = config.getConfig(CoreConfiguration.class);
        doReturn("foo-baß").when(coreConfig).getServiceName();

        MockTracer.MockInstrumentationSetup setup = MockTracer.createMockInstrumentationSetup(config);
        ElasticApmTracer tracer = setup.getTracer();

        UniversalProfilingLifecycleListener lcl = tracer.getLifecycleListener(UniversalProfilingLifecycleListener.class);

        ByteBuffer nativeData = getProcessCorrelationData();
        assertThat(nativeData.getLong()).isEqualTo(0L); //profiler version
        long strLen = nativeData.getLong();
        byte[] utf8ServiceName = new byte[(int) strLen];
        nativeData.get(utf8ServiceName);

        assertThat(new String(utf8ServiceName, StandardCharsets.UTF_8)).isEqualTo("foo-baß");

        //simulate profiler updating the version
        nativeData.putLong(0, 42);

        assertThat(lcl.getProfilerVersion()).isEqualTo(42L);

        tracer.stop();
        assertThat(getProcessCorrelationData()).isNull();
    }


    @Test
    void testThreadStorage() {
        ElasticApmTracer tracer = MockTracer.createRealTracer();

        assertThreadCorrelationTraceContextEquals(null);

        Transaction transaction = tracer.startRootTransaction(null);
        assertThreadCorrelationTraceContextEquals(null);

        transaction.activate();
        assertThreadCorrelationTraceContextEquals(transaction.getTraceContext());

        Span span = transaction.createSpan();
        assertThreadCorrelationTraceContextEquals(transaction.getTraceContext());

        span.activate();
        assertThreadCorrelationTraceContextEquals(span.getTraceContext());

        span.deactivate();
        assertThreadCorrelationTraceContextEquals(transaction.getTraceContext());

        transaction.deactivate();
        assertThreadCorrelationTraceContextEquals(null);

        transaction.end();
        assertThreadCorrelationTraceContextEquals(null);

        tracer.stop();
    }

    private static ByteBuffer getProcessCorrelationData() {
        ByteBuffer data = JVMTIAgentAccess.createProcessProfilingCorrelationBufferAlias(200);
        if (data != null) {
            data.order(ByteOrder.nativeOrder());
        }
        return data;
    }

    private static void assertThreadCorrelationTraceContextEquals(TraceContext expected) {
        ByteBuffer data = JVMTIAgentAccess.createThreadProfilingCorrelationBufferAlias(200);
        if (data == null) {
            assertThat(expected).isNull();
            return;
        }
        data.order(ByteOrder.nativeOrder());

        byte[] traceId = new byte[16];
        data.get(traceId);
        byte[] spanId = new byte[8];
        data.get(spanId);
        long allFlags = data.getLong();
        byte[] transactionId = new byte[8];
        data.get(transactionId);

        if (expected != null) {
            assertThat(expected.getTraceId().dataEquals(traceId, 0)).isTrue();
            assertThat(expected.getId().dataEquals(spanId, 0)).isTrue();
            assertThat(expected.getTransactionId().dataEquals(transactionId, 0)).isTrue();
            assertThat(expected.getFlags()).isEqualTo((byte) (allFlags & 255));
            assertThat(allFlags & ProfilingActivationCorrelator.TRACE_ACTIVE_FLAG).isNotZero();
        } else {
            assertThat(traceId).containsOnly(0);
            assertThat(spanId).containsOnly(0);
            assertThat(transactionId).containsOnly(0);
            assertThat(allFlags).isEqualTo((byte) 0);
        }
    }


}
