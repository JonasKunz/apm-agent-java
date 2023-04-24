package co.elastic.apm.agent.configuration.plugin;

import co.elastic.apm.agent.tracer.config.ConfigOption;
import org.stagemonitor.configuration.ConfigurationOption;

public class PluginConfigOption<T> implements ConfigOption<T> {

    private final ConfigurationOption<T> delegate;

    public PluginConfigOption(ConfigurationOption<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T get() {
        return delegate.get();
    }
}
