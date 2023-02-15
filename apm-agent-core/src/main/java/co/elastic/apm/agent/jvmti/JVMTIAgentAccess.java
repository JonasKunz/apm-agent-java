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

import javax.annotation.Nullable;

public class JVMTIAgentAccess {

    public interface JVMTIAllocationCallback {

        /**
         * Called when a sampled allocation happens.
         * Called on the thread who is doing the allocation.
         *
         * @param object
         * @param sizeBytes
         */
        void objectAllocated(Object object, long sizeBytes);

    }

    private static volatile JVMTIAllocationCallback allocationCallback;

    static void setAllocationCallback(JVMTIAllocationCallback callback) {
        allocationCallback = callback;
    }

    public static native int init0();

    public static native int destroy0();

    public static native int getStackTrace0(int skipFrames, int maxFrames, boolean collectLocations, long[] result);

    @Nullable
    public static native Class<?> getDeclaringClass0(long methodId);

    @Nullable
    public static native String getMethodName0(long methodId, boolean appendSignature);

    public static native boolean isAllocationSamplingSupported0();

    public static native int setAllocationSamplingEnabled0(boolean enable, int initialSamplingRateBytes);

    public static native int setAllocationSamplingRate0(int samplingRateBytes);

    @SuppressWarnings("unused")
    public static void allocationCallback(Object allocated, long allocSize) {
        JVMTIAllocationCallback cb = allocationCallback;
        if (cb != null) {
            cb.objectAllocated(allocated, allocSize);
        }
    }

}
