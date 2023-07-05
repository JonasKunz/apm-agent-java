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
import co.elastic.apm.agent.impl.transaction.Id;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.jvmti.JVMTIAgentAccess;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.stagemonitor.configuration.ConfigurationRegistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
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
        String serviceName = readLengthEncodedString(nativeData);
        assertThat(serviceName).isEqualTo("foo-baß");

        String socketFile = readLengthEncodedString(nativeData);
        assertThat(socketFile).contains("elastic_apm_profiler_correl_socket_");

        //simulate profiler updating the version
        nativeData.putLong(0, 42);

        assertThat(lcl.getProfilerVersion()).isEqualTo(42L);

        tracer.stop();
        assertThat(getProcessCorrelationData()).isNull();
    }

    @NotNull
    private static String readLengthEncodedString(ByteBuffer nativeData) {
        long strLen = nativeData.getLong();
        byte[] str = new byte[(int) strLen];
        nativeData.get(str);
        return new String(str, StandardCharsets.UTF_8);
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


    @Test
    void testTransactionProfilingReturnChannel() throws InterruptedException {

        Id sample1 = Id.new128BitId();
        sample1.fromLongs(1, 2);
        Id sample2 = Id.new128BitId();
        sample2.fromLongs(2, 3);
        Id sample3 = Id.new128BitId();
        sample3.fromLongs(3, 4);

        ConfigurationRegistry config = SpyConfiguration.createSpyConfig();
        CoreConfiguration coreConfig = config.getConfig(CoreConfiguration.class);
        doReturn(500L).when(coreConfig).getProfilerCorrelationDelayMs();

        MockTracer.MockInstrumentationSetup setup = MockTracer.createMockInstrumentationSetup(config);
        ElasticApmTracer tracer = setup.getTracer();

        //Fake a profiler start by updating the profiler version in the process shared storage
        getProcessCorrelationData().putLong(0, 1L);

        Transaction tx = tracer.startRootTransaction(null).withName("foo-bar");
        //simulate a sample coming in while the transaction is started
        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(generateReturnChannelMessage(tx, sample1, 1));
        Thread.sleep(200);
        tx.end();

        //Sleep a little to simualte a delay in profiling data
        Thread.sleep(100);
        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(generateReturnChannelMessage(tx, sample2, 2));
        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(generateReturnChannelMessage(tx, sample3, 3));

        //Simulate profiling samples coming
        setup.getReporter().awaitTransactionCount(1);
        Transaction txResult = setup.getReporter().getFirstTransaction();

        assertThat(txResult.getProfilerSamples())
            .containsExactly(sample1, sample2, sample2, sample3, sample3, sample3);

        tracer.stop();
    }

    private byte[] generateReturnChannelMessage(Transaction tx, Id sampleId, int count) {
        byte[] resultData = new byte[42];
        ByteBuffer result = ByteBuffer.wrap(resultData);
        result.order(ByteOrder.nativeOrder());

        tx.getTraceContext().getTraceId().toBytes(resultData, 0);
        tx.getTraceContext().getId().toBytes(resultData, 16);
        sampleId.toBytes(resultData, 24);
        result.putShort(40, (short) count);

        return resultData;
    }

}
