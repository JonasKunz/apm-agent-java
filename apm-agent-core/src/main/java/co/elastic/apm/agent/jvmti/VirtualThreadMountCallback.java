package co.elastic.apm.agent.jvmti;

public interface VirtualThreadMountCallback {

    void threadMounted(Thread thread);

    void threadUnmounted(Thread thread);
}
