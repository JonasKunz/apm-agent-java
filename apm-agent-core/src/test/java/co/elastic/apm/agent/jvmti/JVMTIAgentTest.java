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
package co.elastic.apm.agent.jvmti;

import co.elastic.apm.agent.testutils.ChildFirstCopyClassloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class JVMTIAgentTest {

    private static final Logger logger = LoggerFactory.getLogger(JVMTIAgentTest.class);


    public static class StackTrace {

        public StackTrace(long[] buffer, int numFrames) {
            this.buffer = buffer;
            this.numFrames = numFrames;
        }

        long[] buffer;
        int numFrames;
    }

    public static class StackTracerCreator implements Callable<StackTrace> {
        @Override
        public StackTrace call() throws Exception {
            long[] stackTrace = new long[1000];
            int numFrames = JVMTIAgent.getStackTrace(0, 1000, false, stackTrace);
            return new StackTrace(stackTrace, numFrames);
        }
    }

    @Nested
    public class StackTraceTests {

        private StackTrace getStackTraceAfterRecursion(int recursionDepth, int skipFrames, int maxFrames, boolean collectLocations) {
            if (recursionDepth == 1) {
                long[] buffer = new long[maxFrames * 2];
                int numFrames = JVMTIAgent.getStackTrace(skipFrames, maxFrames, collectLocations, buffer);
                return new StackTrace(buffer, numFrames);
            } else {
                return getStackTraceAfterRecursion(recursionDepth - 1, skipFrames, maxFrames, collectLocations);
            }
        }

        @Test
        public void testStackTrace() {
            StackTrace result = getStackTraceAfterRecursion(5, 0, 200, false);
            System.out.println(result.numFrames + ", " + Arrays.toString(result.buffer));
            for (int i = 0; i < result.numFrames; i++) {
                System.out.print(JVMTIAgent.getDeclaringClass(result.buffer[i]).getName() + " ");
                System.out.println(JVMTIAgent.getMethodName(result.buffer[i], true));
            }
        }


        @Test
        public void testMethodResolutionAfterGC() throws Exception {
            ClassLoader childLoader = new ChildFirstCopyClassloader(StackTracerCreator.class.getClassLoader(), StackTracerCreator.class.getName());
            Class<?> creatorClass = childLoader.loadClass(StackTracerCreator.class.getName());

            StackTrace stackTrace = ((Callable<StackTrace>) creatorClass.getConstructor().newInstance()).call();

            assertThat(stackTrace.numFrames).isGreaterThan(0);
            long methodId = stackTrace.buffer[0];
            assertThat(JVMTIAgent.getDeclaringClass(methodId)).isSameAs(creatorClass);

            WeakReference<Class<?>> creatorClassWeak = new WeakReference<>(creatorClass);
            childLoader = null;
            creatorClass = null;

            await().atMost(Duration.ofSeconds(10)).until(() -> {
                System.gc();
                return creatorClassWeak.get() == null;
            });

            assertThat(JVMTIAgent.getDeclaringClass(methodId)).isNull();
            assertThat(JVMTIAgent.getMethodName(methodId, true)).isNull();
            assertThat(JVMTIAgent.getMethodName(methodId, false)).isNull();
        }
    }


    @AfterEach
    void destroy() {
        JVMTIAgent.destroy();
    }


    public static volatile byte[] memorySink;

    @ParameterizedTest
    @ValueSource(ints = {1024, 4096, 512 * 1024})
    public void testAllocationSampling(int rate) {
        Thread currentThread = Thread.currentThread();

        AtomicLong samplesOnCurrentThreadRef = new AtomicLong(0L);
        AtomicLong smallestSizeRef = new AtomicLong(Long.MAX_VALUE);

        JVMTIAgent.setAllocationProfilingEnabled(true);
        JVMTIAgent.setAllocationSamplingRate(rate);
        JVMTIAgent.setAllocationSamplingCallback((object, samplingRate, size) -> {
            if (Thread.currentThread() == currentThread) {
                samplesOnCurrentThreadRef.incrementAndGet();
                if (smallestSizeRef.get() > size) {
                    smallestSizeRef.set(size);
                }
            }
        });

        long kbsToAllocate = 512 * 1024;
        for (long i = 0; i < kbsToAllocate; i++) {
            memorySink = new byte[1024];
        }

        long smallestSize = smallestSizeRef.get();
        long samplesOnCurrentThread = samplesOnCurrentThreadRef.get();

        double expectedSamples = kbsToAllocate * 1024.0 / rate;
        assertThat(samplesOnCurrentThread).isBetween(Math.round(expectedSamples * 0.5), Math.round(expectedSamples * 2));
        assertThat(smallestSize).isBetween(1024L, 2048L);

        JVMTIAgent.setAllocationProfilingEnabled(false);
    }
}
