/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class co_elastic_apm_agent_jvmti_JVMTIAgentAccess */

#ifndef _Included_co_elastic_apm_agent_jvmti_JVMTIAgentAccess
#define _Included_co_elastic_apm_agent_jvmti_JVMTIAgentAccess
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     co_elastic_apm_agent_jvmti_JVMTIAgentAccess
 * Method:    init0
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_init0
  (JNIEnv *, jclass);

/*
 * Class:     co_elastic_apm_agent_jvmti_JVMTIAgentAccess
 * Method:    destroy0
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_destroy0
  (JNIEnv *, jclass);

/*
 * Class:     co_elastic_apm_agent_jvmti_JVMTIAgentAccess
 * Method:    getStackTrace0
 * Signature: (IIZ[J)I
 */
JNIEXPORT jint JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getStackTrace0
  (JNIEnv *, jclass, jint, jint, jboolean, jlongArray);

/*
 * Class:     co_elastic_apm_agent_jvmti_JVMTIAgentAccess
 * Method:    getDeclaringClass0
 * Signature: (J)Ljava/lang/Class;
 */
JNIEXPORT jclass JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getDeclaringClass0
  (JNIEnv *, jclass, jlong);

/*
 * Class:     co_elastic_apm_agent_jvmti_JVMTIAgentAccess
 * Method:    getMethodName0
 * Signature: (JZ)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_co_elastic_apm_agent_jvmti_JVMTIAgentAccess_getMethodName0
  (JNIEnv *, jclass, jlong, jboolean);

#ifdef __cplusplus
}
#endif
#endif
