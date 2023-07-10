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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

public class JVMTIAgentAccess {

    private static final Logger logger = LoggerFactory.getLogger(JVMTIAgentAccess.class);

    static volatile VirtualThreadMountCallback threadMountCallback;

    public static native int init0();

    public static native int destroy0();

    public static native int getStackTrace0(int skipFrames, int maxFrames, boolean collectLocations, long[] result);

    @Nullable
    public static native Class<?> getDeclaringClass0(long methodId);

    @Nullable
    public static native String getMethodName0(long methodId, boolean appendSignature);

    /**
     * @param threadBuffer the buffer whose address will get stored in the native thread-local-storage for APM <-> profiling correlation
     */
    public static native void setThreadProfilingCorrelationBuffer0(ByteBuffer threadBuffer);

    /**
     * @param threadBuffer the buffer whose address will get stored in the native global variable for APM <-> profiling correlation
     */
    public static native void setProcessProfilingCorrelationBuffer0(ByteBuffer threadBuffer);

    /**
     * ONLY FOR TESTING!
     * Creates a new bytebuffer for reading the currently configured thread local correlation buffer.
     * This buffer points to the same memory address as the buffer configured via setThreadProfilingCorrelationBuffer0.
     */
    public static native ByteBuffer createThreadProfilingCorrelationBufferAlias(long capacity);

    /**
     * ONLY FOR TESTING!
     * Creates a new bytebuffer for reading the currently configured process local correlation buffer.
     * This buffer points to the same memory address as the buffer configured via setProcessProfilingCorrelationBuffer0.
     */
    public static native ByteBuffer createProcessProfilingCorrelationBufferAlias(long capacity);


    public static native int startProfilerReturnChannelSocket0(String socketFilePath);

    public static native int stopProfilerReturnChannelSocket0();

    /**
     * Reads messages of the provided size into the provided direct bytebuffer.
     * This method is non-blocking. If no messages are available, it simply returns 0.
     *
     * @param outputDirectBuffer the output buffer, must be direct
     * @param bytesPerMessage    the expected size per message in bytes
     * @return the number of messages read
     */
    public static native int readProfilerReturnChannelSocket0(ByteBuffer outputDirectBuffer, int bytesPerMessage);

    /**
     * ONLY FOR TESTING!
     * Sends data to the socket which can be subsequently read via {@link #readProfilerReturnChannelSocket0(ByteBuffer, int)}.
     *
     * @param data the message to send
     */
    public static native int sendToProfilerReturnChannelSocket0(byte[] data);

    public static native String checkVirtualThreadMountEventSupport0();

    public static native int enableVirtualThreadMountEvents0();

    public static native int disableVirtualThreadMountEvents0();

    static void onThreadMount(Thread thread) {
        VirtualThreadMountCallback cb = threadMountCallback;
        if (cb != null) {
            threadMountCallback.threadMounted(thread);
        }
    }

    static void onThreadUnmount(Thread thread) {
        VirtualThreadMountCallback cb = threadMountCallback;
        if (cb != null) {
            threadMountCallback.threadUnmounted(thread);
        }
    }
}
