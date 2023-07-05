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

import co.elastic.apm.agent.common.util.ResourceExtractionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JVMTIAgent {

    public static long LOCATION_NATIVE_CODE = -1;

    private static final Logger logger = LoggerFactory.getLogger(JVMTIAgent.class);

    private enum State {
        NOT_LOADED,
        LOAD_FAILED,
        LOADED,
        INITIALIZED,
        INITIALIZATION_FAILED,
        DESTROY_FAILED
    }

    private static volatile State state = State.NOT_LOADED;

    private static boolean allocationSamplingEnabled = false;
    private static volatile int allocationSamplingRate = 512 * 1024;

    public static int getStackTrace(int skipFrames, int maxFrames, boolean collectLocations, long[] buffer) {
        int minBufferLen = collectLocations ? maxFrames * 2 : maxFrames;
        if (buffer.length < minBufferLen) {
            throw new IllegalArgumentException("Provided buffer for stacktrace is too small!");
        }
        if (skipFrames < 0) {
            throw new IllegalArgumentException("skipFrames must be positive");
            //TODO: support negative skipFrames (counting from stack bottom instead of top)
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("maxFrames must be greater than zero");
        }
        assertInitialized();
        //we skip the frame of this method and of the native method, therefore + 2
        int numFrames = JVMTIAgentAccess.getStackTrace0(skipFrames + 2, maxFrames, collectLocations, buffer);
        if (numFrames < 0) {
            throw new RuntimeException("Native code returned error " + numFrames);
        }
        return numFrames;
    }

    public static Class<?> getDeclaringClass(long methodId) {
        assertInitialized();
        return JVMTIAgentAccess.getDeclaringClass0(methodId);
    }

    public static String getMethodName(long methodId, boolean appendSignature) {
        assertInitialized();
        return JVMTIAgentAccess.getMethodName0(methodId, appendSignature);
    }

    public static void setProfilerProcessStorage(@Nullable ByteBuffer storage) {
        assertInitialized();
        JVMTIAgentAccess.setProcessProfilingCorrelationBuffer0(storage);
    }

    public static void setProfilerCurrentThreadStorage(@Nullable ByteBuffer storage) {
        assertInitialized();
        JVMTIAgentAccess.setThreadProfilingCorrelationBuffer0(storage);
    }

    public static void startProfilerReturnChannel(String absoluteFilePath) {
        assertInitialized();
        logger.info("Opening profiler return channel via file {}", absoluteFilePath);
        JVMTIAgentAccess.startProfilerReturnChannelSocket0(absoluteFilePath);
    }

    public static void stopProfilerReturnChannel() {
        assertInitialized();
        JVMTIAgentAccess.stopProfilerReturnChannelSocket0();
    }

    /**
     * @return the number of messages (not bytes) read
     */
    public static int readFromProfilerReturnChannel(ByteBuffer outputBuffer, int messageSize) {
        assertInitialized();
        return JVMTIAgentAccess.readProfilerReturnChannelSocket0(outputBuffer, messageSize);
    }

    private static void assertInitialized() {
        switch (state) {
            case NOT_LOADED:
            case LOADED:
                doInit();
        }
        if (state != State.INITIALIZED) {
            throw new IllegalStateException("Agent could not be initialized");
        }
    }

    private static boolean checkInitialized() {
        switch (state) {
            case NOT_LOADED:
            case LOADED:
                try {
                    doInit();
                } catch (Throwable t) {
                    logger.error("Failed to initialize JVMTI agent", t);
                }
        }
        return state == State.INITIALIZED;
    }


    private static synchronized void doInit() {
        switch (state) {
            case NOT_LOADED:
                try {
                    loadNativeLibrary();
                    state = State.LOADED;
                } catch (Throwable t) {
                    logger.error("Failed to load jvmti native library", t);
                    state = State.LOAD_FAILED;
                    return;
                }
            case LOADED:
                try {
                    checkError(JVMTIAgentAccess.init0());
                    state = State.INITIALIZED;
                } catch (Throwable t) {
                    logger.error("Failed to initialize jvmti native library", t);
                    state = State.INITIALIZATION_FAILED;
                    return;
                }
        }
    }

    public static synchronized void destroy() {
        switch (state) {
            case INITIALIZED:
                try {
                    checkError(JVMTIAgentAccess.destroy0());
                    allocationSamplingEnabled = false;
                    state = State.LOADED;
                } catch (Throwable t) {
                    logger.error("Failed to shutdown jvmti native library", t);
                    state = State.DESTROY_FAILED;
                    return;
                }
        }
    }


    private static void checkError(int returnCode) {
        if (returnCode < 0) {
            throw new RuntimeException("Elastic JVMTI Agent returned error code " + returnCode);
        }
    }

    private static void loadNativeLibrary() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String libraryName;
        if (os.contains("linux")) {
            if (arch.contains("arm") || arch.contains("aarch32")) {
                throw new IllegalStateException("Native agent does not work on Linux " + arch);
            } else if (arch.contains("aarch")) {
                libraryName = "linux-arm64";
            } else if (arch.contains("64")) {
                libraryName = "linux-x86_64";
            } else {
                throw new IllegalStateException("Native agent does not work on Linux " + arch);
            }
        } else if (os.contains("mac")) {
            if (arch.contains("aarch")) {
                libraryName = "darwin-arm64";
            } else {
                libraryName = "darwin-x86_64";
            }
        } else {
            throw new IllegalStateException("Native agent does not work on " + os);
        }

        String libraryDirectory = System.getProperty("java.io.tmpdir");
        libraryName = "elastic-jvmti-" + libraryName;
        Path file = ResourceExtractionUtil.extractResourceToDirectory("jvmti_agent/" + libraryName + ".so", libraryName, ".so", Paths.get(libraryDirectory));
        System.load(file.toString());
    }

}
