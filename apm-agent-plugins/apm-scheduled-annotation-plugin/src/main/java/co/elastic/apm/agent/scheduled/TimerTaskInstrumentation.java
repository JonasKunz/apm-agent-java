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
package co.elastic.apm.agent.scheduled;

import co.elastic.apm.agent.sdk.bytebuddy.SimpleMethodSignatureOffsetMappingFactory;
import co.elastic.apm.agent.sdk.ElasticApmInstrumentation;
import co.elastic.apm.agent.sdk.logging.Logger;
import co.elastic.apm.agent.sdk.logging.LoggerFactory;
import co.elastic.apm.agent.sdk.internal.util.PrivilegedActionUtils;
import co.elastic.apm.agent.tracer.AbstractSpan;
import co.elastic.apm.agent.tracer.ElasticContext;
import co.elastic.apm.agent.tracer.GlobalTracer;
import co.elastic.apm.agent.tracer.Tracer;
import co.elastic.apm.agent.tracer.Transaction;
import co.elastic.apm.agent.tracer.configuration.StacktraceConfiguration;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;

import static co.elastic.apm.agent.sdk.bytebuddy.CustomElementMatchers.isInAnyPackage;
import static co.elastic.apm.agent.sdk.bytebuddy.CustomElementMatchers.isProxy;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

public class TimerTaskInstrumentation extends ElasticApmInstrumentation {
    private static final String FRAMEWORK_NAME = "TimerTask";

    public static final Logger logger = LoggerFactory.getLogger(TimerTaskInstrumentation.class);

    private static final Tracer tracer = GlobalTracer.get();

    private final Collection<String> applicationPackages;

    public TimerTaskInstrumentation(Tracer tracer) {
        applicationPackages = tracer.getConfig(StacktraceConfiguration.class).getApplicationPackages();
    }

    public static class TimerTaskAdvice {
        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object setTransactionName(@SimpleMethodSignatureOffsetMappingFactory.SimpleMethodSignature String signature,
                                                 @Advice.Origin Class<?> clazz) {
            AbstractSpan<?> active = tracer.getActive();
            if (active == null) {
                Transaction<?> transaction = tracer.startRootTransaction(PrivilegedActionUtils.getClassLoader(clazz));
                if (transaction != null) {
                    transaction.withName(signature)
                        .withType("scheduled")
                        .activate();
                    transaction.setFrameworkName(FRAMEWORK_NAME);
                    return transaction;
                } else {
                    ElasticContext<?> propagationOnly = tracer.currentContext().withContextPropagationOnly(null, null);
                    if(propagationOnly != null) {
                        return propagationOnly.activate();
                    }
                }
            } else {
                logger.debug("Not creating transaction for method {} because there is already a transaction running ({})", signature, active);
            }
            return null;
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onMethodExit(@Advice.Enter @Nullable Object transactionOrCtxObj,
                                        @Advice.Thrown Throwable t) {
            if (transactionOrCtxObj instanceof Transaction<?>) {
                Transaction<?> transaction = (Transaction<?>) transactionOrCtxObj;
                transaction.captureException(t)
                    .deactivate()
                    .end();
            } else if (transactionOrCtxObj instanceof ElasticContext<?>) {
                ((ElasticContext<?>)transactionOrCtxObj).deactivate();
            }
        }
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return isInAnyPackage(applicationPackages, ElementMatchers.<NamedElement>none())
            .and(hasSuperClass(named("java.util.TimerTask")))
            .and(not(isProxy()));
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named("run");
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Arrays.asList("timer-task");
    }

    @Override
    public String getAdviceClassName() {
        return getClass().getName() + "$TimerTaskAdvice";
    }
}
