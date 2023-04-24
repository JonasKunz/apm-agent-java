package co.elastic.apm.agent.configuration.plugin;

import co.elastic.apm.agent.tracer.config.ConfigOption;
import co.elastic.apm.agent.tracer.config.ConfigOptionBuilder;
import org.stagemonitor.configuration.ConfigurationOption;

public class PluginConfigOptionBuilder<T> implements ConfigOptionBuilder<T> {

    private final ConfigurationOption.ConfigurationOptionBuilder<T> delegate;

    public PluginConfigOptionBuilder(ConfigurationOption.ConfigurationOptionBuilder<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ConfigOptionBuilder<T> key(String key) {
        delegate.key(key);
        return this;
    }

    @Override
    public ConfigOptionBuilder<T> tags(String... tags) {
        delegate.tags(tags);
        return this;
    }

    @Override
    public ConfigOptionBuilder<T> configurationCategory(String category) {
        delegate.configurationCategory(category);
        return this;
    }

    @Override
    public ConfigOptionBuilder<T> description(String description) {
        delegate.description(description);
        return this;
    }

    @Override
    public ConfigOptionBuilder<T> dynamic(boolean dynamic) {
        delegate.dynamic(dynamic);
        return this;
    }

    @Override
    public ConfigOption<T> buildWithDefault(T defaultVal) {
        return new PluginConfigOption<>(delegate.buildWithDefault(defaultVal));
    }
}
