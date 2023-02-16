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

import co.elastic.apm.agent.testutils.ChildFirstCopyClassloader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ModuleOpenerTest {

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17)
    public void verifyModuleOpening() throws Exception {
        String illegalAccessorName = IllegalAccessor.class.getName();

        ClassLoader cl1 = new ChildFirstCopyClassloader(getClass().getClassLoader(), illegalAccessorName);
        Method illegal1 = cl1.loadClass(illegalAccessorName).getMethod("doAccess");
        ClassLoader cl2 = new ChildFirstCopyClassloader(getClass().getClassLoader(), illegalAccessorName);
        Method illegal2 = cl2.loadClass(illegalAccessorName).getMethod("doAccess");

        assertThatThrownBy(() -> illegal1.invoke(null))
            .hasCauseInstanceOf(IllegalAccessException.class);

        Instrumentation instr = ByteBuddyAgent.install();
        Class<?> classFromModuleToOpen = Class.forName("com.sun.jndi.ldap.LdapResult");
        ModuleOpener.getInstance().openModuleTo(instr, classFromModuleToOpen, cl2, Collections.singleton("com.sun.jndi.ldap"));

        //Access should be legal now and not throw any exception
        illegal2.invoke(null);
    }

    public static class IllegalAccessor {

        public static void doAccess() throws Exception {
            //com.sun.jndi.ldap.LdapResult is not accessible according to module rules
            Class.forName("com.sun.jndi.ldap.LdapResult").getConstructor().newInstance();
        }

    }


}
