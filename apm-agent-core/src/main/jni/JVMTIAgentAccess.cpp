#include "co_elastic_apm_agent_jvmti_JVMTIAgentAccess.h"
#include "ElasticJvmtiAgent.h"
#include <iostream>


using elastic::jvmti_agent::ReturnCode;
using elastic::jvmti_agent::toJint;
using elastic::raiseExceptionAndReturn;

JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_init0(JNIEnv * env, jclass declaringClass) {
    return toJint(elastic::jvmti_agent::init(env));
}


JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_destroy0(JNIEnv * env, jclass) {
    return toJint(elastic::jvmti_agent::destroy(env));
}

JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getStackTrace0(JNIEnv * env, jclass, jint skipFrames, jint maxFrames, jboolean collectLocations, jlongArray resultBuffer) {
    jint numFramesCollected;
    auto resultCode = elastic::jvmti_agent::getStackTrace(env, skipFrames,maxFrames, collectLocations, resultBuffer, numFramesCollected);
    if(resultCode != elastic::jvmti_agent::ReturnCode::SUCCESS) {
        return toJint(resultCode);
    } else {
        return numFramesCollected;
    }
}

JNIEXPORT jclass JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getDeclaringClass0(JNIEnv * env, jclass, jlong methodId) {
    return elastic::jvmti_agent::getDeclaringClass(env, methodId);
}

JNIEXPORT jstring JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getMethodName0(JNIEnv * env, jclass, jlong methodId, jboolean appendSignature) {
    return elastic::jvmti_agent::getMethodName(env, methodId, appendSignature);
}


JNIEXPORT void JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_setThreadProfilingCorrelationBuffer0(JNIEnv* env, jclass, jobject bytebuffer) {
    elastic::jvmti_agent::setThreadProfilingCorrelationBuffer(env, bytebuffer);
}


JNIEXPORT void JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_setProcessProfilingCorrelationBuffer0(JNIEnv* env, jclass, jobject bytebuffer) {
    elastic::jvmti_agent::setProcessProfilingCorrelationBuffer(env, bytebuffer);
}


JNIEXPORT jobject JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_createThreadProfilingCorrelationBufferAlias(JNIEnv * env , jclass, jlong capacity) {
    return elastic::jvmti_agent::createThreadProfilingCorrelationBufferAlias(env, capacity);
}


JNIEXPORT jobject JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_createProcessProfilingCorrelationBufferAlias(JNIEnv * env , jclass, jlong capacity) {
    return elastic::jvmti_agent::createProcessProfilingCorrelationBufferAlias(env, capacity);
}


JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_startProfilerReturnChannelSocket0(JNIEnv* env, jclass, jstring socketFilePath) {
    return toJint(elastic::jvmti_agent::createProfilerSocket(env, socketFilePath));
}


JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_stopProfilerReturnChannelSocket0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::closeProfilerSocket(env));
}


JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_readProfilerReturnChannelSocket0(JNIEnv* env, jclass, jobject byteBuf, jint messageSize) {
    return elastic::jvmti_agent::readProfilerSocketMessages(env, byteBuf, messageSize);
}


JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_sendToProfilerReturnChannelSocket0(JNIEnv* env, jclass, jbyteArray message) {
    return toJint(elastic::jvmti_agent::writeProfilerSocketMessages(env, message));
}