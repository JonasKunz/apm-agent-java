package co.elastic.apm.agent.tracer.config;

/**
 * All implementations of this class are supposed to be created vy the agent-core via the service-loader mechanism.
 */
public interface PluginConfigFactory {
    PluginConfig createConfig(ConfigOptionFactory optionFactory);
}
