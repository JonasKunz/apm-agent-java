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
package co.elastic.apm.agent.bci.modules;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class MemberAccessTest {

    @BeforeAll
    public static void setup() {
        MemberAccess.setInstrumentation(ByteBuddyAgent.install());
    }

    @Test
    public void accessPrivateFieldInOtherModule() throws Throwable {
        Thread test = new Thread();
        test.setName("foobar");

        MethodHandle threadNameGetter = MemberAccess.fieldGetter(Thread.class, "name");
        MethodHandle threadNameSetter = MemberAccess.fieldSetter(Thread.class, "name");

        assertThat((String) threadNameGetter.invokeExact(test)).isEqualTo("foobar");
        threadNameSetter.invokeExact(test, "haha!");
        assertThat(test.getName()).isEqualTo("haha!");
        assertThat((String) threadNameGetter.invoke(test)).isEqualTo("haha!");

        //Thread.getThreads is a private static native method
        MethodHandle getThreads = MemberAccess.method(Thread.class, "getThreads");
        Thread[] threads = (Thread[]) getThreads.invokeExact();
        assertThat(threads)
            .hasSizeGreaterThanOrEqualTo(1)
            .contains(Thread.currentThread());
    }

}
