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
package co.elastic.apm.agent.allocations;

import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.context.AbstractLifecycleListener;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.Tracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.jvmti.AllocationCallback;
import co.elastic.apm.agent.jvmti.JVMTIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.configuration.ConfigurationOption;

public class AllocationSampler extends AbstractLifecycleListener implements AllocationCallback {

    private static final Logger logger = LoggerFactory.getLogger(AllocationSampler.class);
    private Tracer tracer;

    @Override
    public void start(ElasticApmTracer tracer) throws Exception {
        this.tracer = tracer;
        CoreConfiguration coreConfig = tracer.getConfigurationRegistry().getConfig(CoreConfiguration.class);
        JVMTIAgent.setAllocationSamplingRate(coreConfig.getAllocationProfilingRate().get());
        JVMTIAgent.setAllocationSamplingCallback(this);
        JVMTIAgent.setAllocationProfilingEnabled(true);
        coreConfig.getAllocationProfilingRate().addChangeListener(new ConfigurationOption.ChangeListener<Integer>() {
            @Override
            public void onChange(ConfigurationOption<?> configurationOption, Integer oldValue, Integer newValue) {
                logger.info("Changing allocation profiling rate to {} bytes", newValue);
                JVMTIAgent.setAllocationSamplingRate(newValue);
            }
        });
    }

    @Override
    public void objectAllocated(Object object, int currentSamplingRate, long sizeBytes) {
        //TODO: make tracer.getActive() allocation free (currently initialized the ThreadLocal)
        AbstractSpan<?> currentSpan = tracer.getActive();
        if (currentSpan != null) {
            currentSpan.addSampledAllocationBytes(currentSamplingRate);
            if (!(currentSpan instanceof Transaction)) {
                Transaction transaction = currentSpan.getTransaction();
                if (transaction != null) {
                    transaction.addSampledAllocationBytes(currentSamplingRate);
                }
            }
        }
    }

    @Override
    public void stop() throws Exception {
        JVMTIAgent.setAllocationProfilingEnabled(false);
    }
}
