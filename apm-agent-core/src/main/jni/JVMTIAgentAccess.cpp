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

JNIEXPORT jboolean JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_isAllocationSamplingSupported0(JNIEnv * env, jclass) {
    bool supported;
    if(ReturnCode::SUCCESS != elastic::jvmti_agent::isAllocationSamplingSupported(env, supported)) {
        return false;
    }
    return supported;
}

JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_setAllocationSamplingEnabled0(JNIEnv * env, jclass, jboolean enable, jint initialSampleRate) {
    return toJint(elastic::jvmti_agent::setAllocationSamplingEnabled(env, enable, initialSampleRate));
}
JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_setAllocationSamplingRate0(JNIEnv * env, jclass, jint samplingRate) {
    return toJint(elastic::jvmti_agent::setAllocationSamplingRate(env, samplingRate));
}
