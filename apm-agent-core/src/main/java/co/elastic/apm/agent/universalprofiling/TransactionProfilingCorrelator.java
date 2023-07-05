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
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.Id;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.jvmti.JVMTIAgent;
import co.elastic.apm.agent.util.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TransactionProfilingCorrelator {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProfilingCorrelator.class);

    private final CoreConfiguration coreConfiguration;

    /**
     * 16 byte trace-ID
     * 8 byte transaction-id
     * 16 byte profiler-stacktrace-id
     * 2 byte (short) count
     */
    private final int MESSAGE_SIZE_BYTES = 42;

    private final ElasticApmTracer tracer;

    private final LinkedBlockingQueue<Transaction> endedTransactionQueue;

    /**
     * Maps the span-id of a transaction to the corresponding buffered transaction elements.
     */
    private final Map<Id, Transaction> transactionsById;
    private volatile String socketFilePath;

    private ScheduledExecutorService executor;

    private final UniversalProfilingLifecycleListener profLifecycleListener;

    public TransactionProfilingCorrelator(ElasticApmTracer tracer, UniversalProfilingLifecycleListener profLifecycleListener) {
        this.tracer = tracer;
        coreConfiguration = tracer.getConfig(CoreConfiguration.class);
        endedTransactionQueue = new LinkedBlockingQueue<>();
        transactionsById = new ConcurrentHashMap<>();
        this.profLifecycleListener = profLifecycleListener;
    }


    public synchronized void start() {
        String dir = System.getProperty("java.io.tmpdir");
        String absolutePath = Paths.get(dir)
            .resolve("elastic_apm_profiler_correl_socket_" + System.currentTimeMillis())
            .toAbsolutePath().toString();
        JVMTIAgent.startProfilerReturnChannel(absolutePath);
        socketFilePath = absolutePath;
        executor = ExecutorUtils.createSingleThreadSchedulingDaemonPool("universal-profiling-correlation");
        executor.scheduleWithFixedDelay(new Runner(), 0, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        JVMTIAgent.stopProfilerReturnChannel();
        socketFilePath = null;
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public String getSocketFilePath() {
        return socketFilePath;
    }

    public void transactionStarted(Transaction tx) {
        transactionsById.put(tx.getTraceContext().getId(), tx);
    }

    public void bufferEndedTransaction(Transaction tx) {
        if (profLifecycleListener.getProfilerVersion() == 0 || socketFilePath == null || !endedTransactionQueue.offer(tx)) {
            reportTransaction(tx);
        }
    }

    private void reportTransaction(Transaction tx) {
        transactionsById.remove(tx.getTraceContext().getId());
        tracer.getReporter().report(tx);
    }

    private class Runner implements Runnable {

        private final ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

        private final Id traceId = Id.new128BitId();
        private final Id transactionId = Id.new64BitId();
        private final Id profilingStackTraceId = Id.new128BitId();

        public Runner() {
            buffer.order(ByteOrder.nativeOrder());
        }

        @Override
        public void run() {
            int read = 0;
            while ((read = JVMTIAgent.readFromProfilerReturnChannel(buffer, MESSAGE_SIZE_BYTES)) > 0) {
                for (int i = 0; i < read; i++) {
                    buffer.position(i * MESSAGE_SIZE_BYTES);
                    traceId.readFromBuffer(buffer);
                    transactionId.readFromBuffer(buffer);
                    profilingStackTraceId.readFromBuffer(buffer);
                    short count = buffer.getShort();
                    Transaction transaction = transactionsById.get(transactionId);
                    if (transaction != null && transaction.getTraceContext().getTraceId().equals(traceId)) {
                        Id stackTraceCopy = Id.new128BitId();
                        stackTraceCopy.copyFrom(profilingStackTraceId);
                        transaction.addProfilerSamples(stackTraceCopy, count);
                    } else {
                        logger.warn("Received profiler samples for unknown transaction {} (trace-id {})", transactionId, traceId);
                    }
                }
            }
            Transaction nextTx = endedTransactionQueue.peek();
            long minDelay = coreConfiguration.getProfilerCorrelationDelayMs();
            while (nextTx != null && nextTx.getTimeSinceEnded() >= minDelay * 1000) {
                reportTransaction(endedTransactionQueue.poll());
                nextTx = endedTransactionQueue.peek();
            }

        }
    }
}
