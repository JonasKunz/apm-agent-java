package co.elastic.apm.agent.tracer.config;

public interface ConfigOptionFactory {

    ConfigOptionBuilder<Boolean> booleanOption();
}
