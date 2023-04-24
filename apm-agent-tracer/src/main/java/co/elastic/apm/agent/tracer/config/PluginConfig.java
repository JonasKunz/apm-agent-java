package co.elastic.apm.agent.tracer.config;

import java.util.List;

public interface PluginConfig {

    List<ConfigOption<?>> getAllOptions();

}
