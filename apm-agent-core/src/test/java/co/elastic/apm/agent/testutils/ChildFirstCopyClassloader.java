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
package co.elastic.apm.agent.testutils;

import org.apache.commons.compress.utils.IOUtils;

import java.io.IOException;
import java.io.InputStream;

public class ChildFirstCopyClassloader extends ClassLoader {

    String childFirstClassName;

    public ChildFirstCopyClassloader(ClassLoader parent, String childFirstClassName) {
        super(parent);
        this.childFirstClassName = childFirstClassName;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (childFirstClassName.equals(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        String binaryName = name.replace('.', '/') + ".class";
                        InputStream resourceAsStream = getParent().getResourceAsStream(binaryName);
                        if (resourceAsStream == null) {
                            throw new IllegalStateException(binaryName + " not found in parent classloader!");
                        }
                        byte[] bytecode = IOUtils.toByteArray(resourceAsStream);
                        c = defineClass(name, bytecode, 0, bytecode.length);
                        if (resolve) {
                            resolveClass(c);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return c;
            } else {
                return super.loadClass(name, resolve);
            }
        }
    }
}
