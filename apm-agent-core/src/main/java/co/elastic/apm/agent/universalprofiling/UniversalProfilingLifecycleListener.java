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

import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.context.AbstractLifecycleListener;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.jvmti.JVMTIAgent;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Manages the creation and updating of native memory used for communication with
 * the elastic universal profiler.
 * <p>
 * The java agent loads a native library which exports the following two symbols:
 * thread_local void* elastic_apm_profiling_correlation_tls = nullptr; //thread correlation storage
 * void* elastic_apm_profiling_correlation_process_storage = nullptr; //process correlation storage
 * <p>
 * Process Correlation Storage Layout:
 * uint64 profilerVersion //zero initialized. This is printed to the console every second by the agent. Supposed
 * uint64 serviceNameLengthBytes
 * uint8[serviceNameLengthBytes] serviceNameUtf8
 * <p>
 * the profilerVersion field is supposed to be written by the profiler to showcase profiler -> agent communication.
 * profilerVersion is zero-initialized by the agent. The current value is logged every second.
 * <p>
 * Thread Correlation Storage Layout (initialized on first transaction):
 * uint8[16] traceId.
 * uint8[8] spanId.
 * uint64 trace-flags.
 * - Flag 1 : sampled flag (bool sampled = (trace-flags & 1) != 0)
 * - Flag 256: indicates a span is currently active and therefore traceId, spanId
 * and transactionId are actually populated ((trace-flags & 256) != 0)
 * uint8[8] transactionId (=the spanId of the transaction)
 */
public class UniversalProfilingLifecycleListener extends AbstractLifecycleListener {

    private static final int PROFILER_VERSION_OFFSET = 0;
    private static final int SERVICE_NAME_OFFSET = 8;

    private static final Logger logger = LoggerFactory.getLogger(UniversalProfilingLifecycleListener.class);

    private boolean isRunning = false;

    private ScheduledFuture<?> profilerVersionLogger;

    private ByteBuffer processStorage;

    @Override
    public void init(ElasticApmTracer tracer) throws Exception {
        try {
            initProcessStorage(tracer);
            runProfilerVersionPoller(tracer);
            JVMTIAgent.setProfilerProcessStorage(processStorage);
            tracer.registerSpanListener(new ProfilingActivationCorrelator(tracer));
            isRunning = true;
        } catch (Throwable t) {
            logger.error("Failed to initialize universal profiler correlation", t);
        }
    }

    private void runProfilerVersionPoller(ElasticApmTracer tracer) {
        profilerVersionLogger = tracer.getSharedSingleThreadedPool().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                logger.info("Current Universal Profiler Version: {}", Long.toHexString(getProfilerVersion()));
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void initProcessStorage(ElasticApmTracer tracer) {
        processStorage = ByteBuffer.allocateDirect(4096);
        processStorage.order(ByteOrder.nativeOrder());
        resetProfilerVersion();
        String serviceName = tracer.getConfig(CoreConfiguration.class).getServiceName();
        setServiceName(serviceName);
    }

    public void resetProfilerVersion() {
        processStorage.putLong(PROFILER_VERSION_OFFSET, 0);
    }

    public long getProfilerVersion() {
        return processStorage.getLong(PROFILER_VERSION_OFFSET);
    }

    public void setServiceName(String serviceName) {
        byte[] utf8Name = serviceName.getBytes(StandardCharsets.UTF_8);
        processStorage.putLong(SERVICE_NAME_OFFSET, 0);
        processStorage.position(SERVICE_NAME_OFFSET + 8);
        processStorage.put(utf8Name);
        processStorage.putLong(SERVICE_NAME_OFFSET, utf8Name.length);
    }


    @Override
    public void stop() throws Exception {
        if (profilerVersionLogger != null) {
            profilerVersionLogger.cancel(false);
        }
        if (isRunning) {
            try {
                JVMTIAgent.setProfilerProcessStorage(null);
            } catch (Throwable t) {
                logger.error("Failed to shutdown universal profiler correlation", t);
            }
        }
    }
}
