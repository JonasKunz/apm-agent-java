package co.elastic.apm.agent.tracer.config;

public interface ConfigOptionBuilder<T> {

    ConfigOptionBuilder<T> key(String key);

    ConfigOptionBuilder<T> tags(String... tags);

    ConfigOptionBuilder<T> configurationCategory(String category);

    ConfigOptionBuilder<T> description(String description);

    ConfigOptionBuilder<T> dynamic(boolean dynamic);

    ConfigOption<T> buildWithDefault(T defaultVal);
}
