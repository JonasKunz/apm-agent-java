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


import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class ProfilerSocketTest {

    @BeforeAll
    public static void startChannel() {
        String dir = System.getProperty("java.io.tmpdir");
        String absolutePath = Paths.get(dir).resolve("elastic_apm_test_socket").toAbsolutePath().toString();
        JVMTIAgent.startProfilerReturnChannel(absolutePath);
    }

    @Test
    public void testMessagePassing() {
        byte[] msg1 = {1, 2, 3, 4, 5};
        byte[] msg2 = {6, 7, 8, 9, 10};
        byte[] msg3 = {11, 12, 13, 14, 15};

        ByteBuffer buff = ByteBuffer.allocateDirect(14);

        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(0);

        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(msg1);
        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(msg2);
        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(msg3);

        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(2);

        byte[] buf1 = new byte[10];
        buff.get(buf1);
        assertThat(buf1).containsAnyOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(1);

        byte[] buf2 = new byte[5];
        buff.position(0);
        buff.get(buf2);
        assertThat(buf2).containsAnyOf(11, 12, 13, 14, 15);

        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(0);
        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(0);

        JVMTIAgentAccess.sendToProfilerReturnChannelSocket0(msg1);
        assertThat(JVMTIAgent.readFromProfilerReturnChannel(buff, 5)).isEqualTo(1);
        buff.position(0);
        buff.get(buf2);
        assertThat(buf2).containsAnyOf(1, 2, 3, 4, 5);
    }

    @AfterAll
    public static void stopChannel() {
        JVMTIAgent.stopProfilerReturnChannel();
    }
}
