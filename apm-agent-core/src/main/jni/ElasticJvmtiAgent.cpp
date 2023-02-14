#include "ElasticJvmtiAgent.h"
#include <memory>
#include <array>
#include <atomic>

namespace elastic
{
    namespace jvmti_agent
    {

        static jvmtiEnv* jvmti;
        static bool allocationSamplingEnabled;

        struct AllocCallback {
            jclass clazz;
            jmethodID method;
        };

        static std::atomic<AllocCallback> javaAllocationCallback{};

        namespace {

            template<typename T>
            jlong toJlong(T value) {
                static_assert(sizeof(T) <= sizeof(jlong));
                jlong result = 0;
                std::memcpy(&result, &value, sizeof(T));
                return result;
            }

            template<typename T>
            T fromJlong(jlong value) {
                static_assert(sizeof(T) <= sizeof(jlong));
                T result;
                std::memcpy(&result, &value, sizeof(T));
                return result;
            }

            void JNICALL allocationCallback(jvmtiEnv *jvmti_env, JNIEnv* jniEnv, jthread thread, jobject object, jclass object_klass, jlong size) {
                auto callback = javaAllocationCallback.load();
                if(callback.method != NULL) {
                    jniEnv->CallStaticVoidMethod(callback.clazz, callback.method, object, size);
                }
            }
        }

        ReturnCode init(JNIEnv* jniEnv, jclass callBackClass, jmethodID allocationCallbackMethod) {
            if(jvmti != nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent is already initialized!");
            }
            JavaVM* vm;
            auto vmError = jniEnv->GetJavaVM(&vm);
            if(vmError != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jniEnv->GetJavaVM() failed, return code is ", vmError);
            }
            auto getEnvErr = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2);
            if(getEnvErr != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "JavaVM->GetEnv() failed, return code is ", getEnvErr);
            }

            javaAllocationCallback.store({callBackClass, allocationCallbackMethod});
            allocationSamplingEnabled = false;

            jvmtiEventCallbacks callbacks = {};
            callbacks.SampledObjectAlloc = &allocationCallback;
            auto callbackError = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
            if (callbackError != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->SetEventCallbacks() failed, return code is ", callbackError);
            }

            return ReturnCode::SUCCESS;
        }

        ReturnCode destroy(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {
                javaAllocationCallback.store({});
                auto error = jvmti->DisposeEnvironment();
                jvmti = nullptr;
                if(error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->DisposeEnvironment() failed, return code is: ", error);
                }
                return ReturnCode::SUCCESS;
            } else {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized!");
            }
        }

        ReturnCode getStackTrace(JNIEnv* jniEnv, jint skipFrames, jint maxCollectFrames, bool collectLocations, jlongArray resultBuffer, jint& resultNumFrames) {
            static_assert(sizeof(jmethodID) == sizeof(jlong));
            static_assert(sizeof(jlocation) == sizeof(jlong));

            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized");
            }

            jvmtiFrameInfo* buffer;
            std::array<jvmtiFrameInfo, MAX_ALLOCATION_FREE_FRAMES> stackBuffer;
            std::unique_ptr<jvmtiFrameInfo[]> heapBuffer;
            if (maxCollectFrames <= MAX_ALLOCATION_FREE_FRAMES) {
                buffer = stackBuffer.data();
            } else {
                heapBuffer = std::make_unique<jvmtiFrameInfo[]>(maxCollectFrames);
                buffer = heapBuffer.get();
            }

            auto error = jvmti->GetStackTrace(NULL, skipFrames, maxCollectFrames, buffer, &resultNumFrames);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->GetStackTrace() failed, return code is ", error);
            }

            jlong* resultPtr = static_cast<jlong*>(jniEnv->GetPrimitiveArrayCritical(resultBuffer, nullptr));
            if(resultPtr == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not access result array buffer");
            }

            for (jint i=0; i<resultNumFrames; i++) {
                if (collectLocations) {
                    resultPtr[i*2] = toJlong(buffer[i].method);
                    resultPtr[i*2 + 1] = toJlong(buffer[i].location);
                } else {
                    resultPtr[i] = toJlong(buffer[i].method);
                }
            }

            jniEnv->ReleasePrimitiveArrayCritical(resultBuffer, resultPtr, 0);
            return ReturnCode::SUCCESS;
        }


        jclass getDeclaringClass(JNIEnv* jniEnv, jlong methodId) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            jclass returnValue;
            auto error = jvmti->GetMethodDeclaringClass(fromJlong<jmethodID>(methodId), &returnValue);
            if (error != JVMTI_ERROR_NONE) {
                return nullptr;
            }
            return returnValue;
        }

        jstring getMethodName(JNIEnv* jniEnv, jlong methodId, bool appendSignature) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            char* namePtr = nullptr;
            char* signaturePtr = nullptr;
            auto error = jvmti->GetMethodName(fromJlong<jmethodID>(methodId), &namePtr, appendSignature ? &signaturePtr : nullptr, nullptr);
            if (error != JVMTI_ERROR_NONE) {
                return nullptr;
            }

            std::array<char, 1024> stackBuffer;
            std::unique_ptr<char[]> heapBuffer;

            char* result;
            if (!appendSignature) {
                result = namePtr;
            } else {
                auto nameLen = std::strlen(namePtr);
                auto signatureLen = std::strlen(signaturePtr);
                auto minBufferLen = nameLen + signatureLen + 1; // +2 because of slash and zero termination
                if(minBufferLen <= stackBuffer.size()) {
                    result = stackBuffer.data();
                } else {
                    heapBuffer = std::make_unique<char[]>(minBufferLen);
                    result = heapBuffer.get();
                }
                std::memcpy(result, namePtr, nameLen);
                std::memcpy(result + nameLen, signaturePtr, signatureLen);
                result[nameLen + signatureLen] = 0;
            }

            jstring resultStr = jniEnv->NewStringUTF(result);

            jvmti->Deallocate(reinterpret_cast<unsigned char*>(namePtr));
            if(appendSignature) {
                jvmti->Deallocate(reinterpret_cast<unsigned char*>(signaturePtr));
            }
            
            return resultStr;
        }

        ReturnCode isAllocationSamplingSupported(JNIEnv* jniEnv, bool& isSupported) {
            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized");
            }
            jvmtiCapabilities caps{};
            auto error = jvmti->GetPotentialCapabilities(&caps);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->GetPotentialCapabilities() failed, return code is ", error);
            }
            isSupported = caps.can_generate_sampled_object_alloc_events != 0;
            return ReturnCode::SUCCESS;
        }

        ReturnCode disableAllocationSampling(JNIEnv* jniEnv) {
            
            auto error = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SAMPLED_OBJECT_ALLOC, NULL);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->SetEventNotificationMode() failed for JVMTI_EVENT_SAMPLED_OBJECT_ALLOC, return code is ", error);
            }
            jvmtiCapabilities caps = {};
            caps.can_generate_sampled_object_alloc_events = 1;
            error = jvmti->RelinquishCapabilities(&caps);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->RelinquishCapabilities() failed for allocation sampling capability, return code is ", error);
            }

            return ReturnCode::SUCCESS;
        }

        ReturnCode enableAllocationSampling(JNIEnv* jniEnv, jint samplingRateBytes) {
            bool isSupported;
            auto retCode = isAllocationSamplingSupported(jniEnv, isSupported);
            if(retCode != ReturnCode::SUCCESS) {
                return retCode;
            }
            if (!isSupported) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "JVMTI does not allow enabling allocation sampling capability!");
            }

            jvmtiCapabilities caps = {};
            caps.can_generate_sampled_object_alloc_events = 1;
            auto error = jvmti->AddCapabilities(&caps);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->AddCapabilities() failed for allocation sampling capability, return code is ", error);
            }

            error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SAMPLED_OBJECT_ALLOC, NULL);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->SetEventNotificationMode() failed for JVMTI_EVENT_SAMPLED_OBJECT_ALLOC, return code is ", error);
            }

            error = jvmti->SetHeapSamplingInterval(samplingRateBytes);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->SetHeapSamplingInterval() failed for sampling rate ", samplingRateBytes, ", return code is ", error);
            }
            
            return ReturnCode::SUCCESS;
        }

        ReturnCode setAllocationSamplingEnabled(JNIEnv* jniEnv, bool enable, jint initialSampleRate) {
            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized");
            }
            if(enable == allocationSamplingEnabled) {
                return ReturnCode::SUCCESS;
            }

            ReturnCode result;

            if(enable) {
                result = enableAllocationSampling(jniEnv, initialSampleRate);
            } else {
                result = disableAllocationSampling(jniEnv);
            }

            if(result == ReturnCode::SUCCESS) {
                allocationSamplingEnabled = enable;
            }
            return result;            
        }


        ReturnCode setAllocationSamplingRate(JNIEnv* jniEnv, jint samplingRateBytes) {
            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized");
            }
            if(!allocationSamplingEnabled) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Allocation sampling has not been enabled yet!");
            }

            auto error = jvmti->SetHeapSamplingInterval(samplingRateBytes);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->SetHeapSamplingInterval() failed for sampling rate ", samplingRateBytes, ", return code is ", error);
            }
            
            return ReturnCode::SUCCESS;
        }
        

    } // namespace jvmti_agent
    
} // namespace elastic
