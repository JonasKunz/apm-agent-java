package co.elastic.apm.agent.configuration.plugin;

import co.elastic.apm.agent.tracer.config.ConfigOptionBuilder;
import co.elastic.apm.agent.tracer.config.ConfigOptionFactory;
import org.stagemonitor.configuration.ConfigurationOption;

/**
 * The agent core would use this implementation to create plugin configuration instances
 * via the {@link co.elastic.apm.agent.tracer.config.PluginConfigFactory} interfaces.
 */
public class PluginConfigOptionFactory implements ConfigOptionFactory {
    @Override
    public ConfigOptionBuilder<Boolean> booleanOption() {
        return new PluginConfigOptionBuilder<>(ConfigurationOption.booleanOption());
    }
}
