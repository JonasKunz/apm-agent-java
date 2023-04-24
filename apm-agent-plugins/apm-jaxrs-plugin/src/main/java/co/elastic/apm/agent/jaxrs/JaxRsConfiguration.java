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
package co.elastic.apm.agent.jaxrs;

import co.elastic.apm.agent.tracer.config.ConfigOption;
import co.elastic.apm.agent.tracer.config.ConfigOptionFactory;
import co.elastic.apm.agent.tracer.config.PluginConfig;
import co.elastic.apm.agent.tracer.config.PluginConfigFactory;

import java.util.List;

/**
 * Configuration provider for the apm jax-rs plugin
 */
public class JaxRsConfiguration implements PluginConfig {
    private static final String JAXRS_CATEGORY = "JAX-RS";


    /**
     * This class is supposed to be created by the agent-core via the service-loader.
     */
    public static class Factory implements PluginConfigFactory {

        @Override
        public PluginConfig createConfig(ConfigOptionFactory optionFactory) {
            return new JaxRsConfiguration(optionFactory);
        }
    }

    private final ConfigOption<Boolean> enableJaxrsAnnotationInheritance;

    private final ConfigOption<Boolean> useAnnotationValueForTransactionName;


    public JaxRsConfiguration(ConfigOptionFactory optionFactory) {
        enableJaxrsAnnotationInheritance = optionFactory.booleanOption()
            .key("enable_jaxrs_annotation_inheritance")
            .tags("added[1.5.0]")
            .configurationCategory(JAXRS_CATEGORY)
            .tags("performance")
            .description(
                "By default, the agent will scan for @Path annotations on the whole class hierarchy, recognizing a class as a JAX-RS resource if the class or any of its superclasses/interfaces has a class level @Path annotation.\n" +
                    "If your application does not use @Path annotation inheritance, set this property to 'false' to only scan for direct @Path annotations. This can improve the startup time of the agent.\n")
            .dynamic(false)
            .buildWithDefault(true);

        useAnnotationValueForTransactionName = optionFactory.booleanOption()
            .key("use_jaxrs_path_as_transaction_name")
            .tags("added[1.8.0]")
            .configurationCategory(JAXRS_CATEGORY)
            .description("By default, the agent will use `ClassName#methodName` for the transaction name of JAX-RS requests.\n" +
                "If you want to use the URI template from the `@Path` annotation, set the value to `true`.")
            .dynamic(false)
            .buildWithDefault(false);
    }

    @Override
    public List<ConfigOption<?>> getAllOptions() {
        return List.of();
    }

    /**
     * @return if true, the jax-rs plugin must scan for @Path annotations in the class hierarchy of classes.
     * if false, only @Path annotations on implementation classes are considered.
     */
    public boolean isEnableJaxrsAnnotationInheritance() {
        return enableJaxrsAnnotationInheritance.get();
    }

    public boolean isUseJaxRsPathForTransactionName() {
        return useAnnotationValueForTransactionName.get();
    }

}
