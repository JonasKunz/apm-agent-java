#include "ElasticJvmtiAgent.h"
#include <memory>
#include <cstring>
#include <array>
#include <mutex>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include "MethodRef.h"


JNIEXPORT thread_local void* elastic_apm_profiling_correlation_tls = nullptr;

JNIEXPORT void* elastic_apm_profiling_correlation_process_storage = nullptr;

namespace elastic
{
    namespace jvmti_agent
    {

        static jvmtiEnv* jvmti;
        static int profilerSocket = -1;
        static std::string profilerSocketFile;
        static GlobalMethodRef threadMountCallback;
        static GlobalMethodRef threadUnmountCallback;
        static bool threadMountCallbacksEnabled = false;

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
        }

        ReturnCode init(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent is already initialized!");
            }

            threadMountCallback = GlobalMethodRef(jniEnv, "co/elastic/apm/agent/jvmti/JVMTIAgentAccess", true, "onThreadMount", "(Ljava/lang/Thread;)V");
            threadUnmountCallback = GlobalMethodRef(jniEnv, "co/elastic/apm/agent/jvmti/JVMTIAgentAccess", true, "onThreadUnmount", "(Ljava/lang/Thread;)V");
            if(threadMountCallback.isEmpty() || threadUnmountCallback.isEmpty()) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to lookup JVMTIAgentAccess callback methods ");
            }

            JavaVM* vm;
            auto vmError = jniEnv->GetJavaVM(&vm);
            if(vmError != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jniEnv->GetJavaVM() failed, return code is ", vmError);
            }
            auto getEnvErr = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION);
            if(getEnvErr != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "JavaVM->GetEnv() failed, return code is ", getEnvErr);
            }

            jvmtiCapabilities caps = {};
            caps.can_support_virtual_threads = 1;
            auto capErr = jvmti->AddCapabilities(&caps);
            if(capErr != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to add virtual threads capability", capErr);
            }
            std::cout << "virtual thread support has been enabled!" << std::endl;
            return ReturnCode::SUCCESS;
        }

        ReturnCode destroy(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {

                if(threadMountCallbacksEnabled ) {
                    auto ret = setVirtualThreadMountCallbackEnabled(jniEnv, false);
                    if(ret != ReturnCode::SUCCESS) {
                        return ret;
                    }
                }
                auto error = jvmti->DisposeEnvironment();
                jvmti = nullptr;
                if(error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->DisposeEnvironment() failed, return code is: ", error);
                }

                elastic_apm_profiling_correlation_process_storage = nullptr;

                if(profilerSocket != -1) {
                    auto result = closeProfilerSocket(jniEnv);
                    if(result != ReturnCode::SUCCESS) {
                        return result;
                    }
                }


                threadMountCallback.clear(jniEnv);
                threadUnmountCallback.clear(jniEnv);

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


        void setThreadProfilingCorrelationBuffer(JNIEnv* jniEnv, jobject bytebuffer) {
            if(bytebuffer == nullptr) {
                elastic_apm_profiling_correlation_tls = nullptr;
            } else {
                elastic_apm_profiling_correlation_tls = jniEnv->GetDirectBufferAddress(bytebuffer);
            }
        }

        void setProcessProfilingCorrelationBuffer(JNIEnv* jniEnv, jobject bytebuffer) {
            if(bytebuffer == nullptr) {
                elastic_apm_profiling_correlation_process_storage = nullptr;
            } else {
                elastic_apm_profiling_correlation_process_storage = jniEnv->GetDirectBufferAddress(bytebuffer);
            }
        }

        //ONLY FOR TESTING!
        jobject createThreadProfilingCorrelationBufferAlias(JNIEnv* jniEnv, jlong capacity) {
            if(elastic_apm_profiling_correlation_tls == nullptr) {
                return nullptr;
            } else {
                return jniEnv->NewDirectByteBuffer(elastic_apm_profiling_correlation_tls, capacity);
            }
        }

        //ONLY FOR TESTING!
        jobject createProcessProfilingCorrelationBufferAlias(JNIEnv* jniEnv, jlong capacity) {
            if(elastic_apm_profiling_correlation_process_storage == nullptr) {
                return nullptr;
            } else {
                return jniEnv->NewDirectByteBuffer(elastic_apm_profiling_correlation_process_storage, capacity);
            }
        }


        ReturnCode createProfilerSocket(JNIEnv* jniEnv, jstring filepath) {
            if(profilerSocket != -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Profiler socket already opened!");
            }

            int fd = socket(PF_UNIX, SOCK_DGRAM, 0);
            if (fd == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not create SOCK_DGRAM socket");
            }
            int flags = fcntl(fd, F_GETFL, 0);
            if (flags == -1) {
                close(fd);
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not read fnctl flags from socket");
            }
            if(fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0){
                close(fd);
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not configure socket to be non-blocking");                
            }

            sockaddr_un addr = { .sun_family = AF_UNIX };
            const char* pathCstr = jniEnv->GetStringUTFChars(filepath, 0);
            std::string pathStr(pathCstr);
            jniEnv->ReleaseStringUTFChars(filepath, pathCstr);
            strncpy(addr.sun_path, pathStr.c_str(), sizeof(addr.sun_path) - 1);

            if (bind(fd, (sockaddr*)&addr, sizeof(addr) ) < 0) {
                close(fd);
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not bind socket to the given filepath");    
            }

            profilerSocket = fd;
            profilerSocketFile = pathStr;
            return ReturnCode::SUCCESS;
        }

        ReturnCode closeProfilerSocket(JNIEnv* jniEnv) {
            if(profilerSocket == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "No profiler socket active!");
            }
            close(profilerSocket);
            unlink(profilerSocketFile.c_str());
            profilerSocket = -1;
            profilerSocketFile = "";
            return ReturnCode::SUCCESS;
        }

        ReturnCode writeProfilerSocketMessages(JNIEnv* jniEnv, jbyteArray message) {
            if(profilerSocket == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "No profiler socket active!");
            }

            jboolean isCopy;
            jsize numBytes = jniEnv->GetArrayLength(message);
            jbyte* data = jniEnv->GetByteArrayElements(message, &isCopy);
            if(data == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not get array data");
            }
            
            sockaddr_un addr = { .sun_family = AF_UNIX };
            strncpy(addr.sun_path, profilerSocketFile.c_str(), sizeof(addr.sun_path) - 1);

            auto result = sendto(profilerSocket, data, numBytes, MSG_DONTWAIT, (sockaddr*)&addr, sizeof(addr));
            auto errorNum = errno;

            jniEnv->ReleaseByteArrayElements(message, data, 0);
            if(result != numBytes) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not send to socket, return value is ", result," errno is ", errorNum);
            }

            return ReturnCode::SUCCESS;
        }

        jint readProfilerSocketMessages(JNIEnv* jniEnv, jobject outputBuffer, jint bytesPerMessage) {
            if(profilerSocket == -1) {
                return raiseExceptionAndReturn(jniEnv, -1, "No profiler socket active!");
            }

            jsize arrayLen = jniEnv->GetDirectBufferCapacity(outputBuffer);
            uint8_t* output = static_cast<uint8_t*>(jniEnv->GetDirectBufferAddress(outputBuffer));
            if(output == nullptr || arrayLen == -1) {
                return raiseExceptionAndReturn(jniEnv, -1, "Provided bytebuffer is not a direct buffer");
            }

            jsize numRead = 0;
            while((arrayLen - numRead * bytesPerMessage) >= bytesPerMessage) {
                int n = recv(profilerSocket, output + numRead * bytesPerMessage, bytesPerMessage, 0);
                if (n == -1) {
                    if(errno == EAGAIN || errno == EWOULDBLOCK) {
                        break; //no more data to read
                    } else {
                        return raiseExceptionAndReturn(jniEnv, -1, "Failed to read from socket, error code is ", errno);
                    }
                }
                if(n < bytesPerMessage) {
                    continue; //received a truncated message, which should be ignored
                }
                numRead++;
            }
            return numRead;
        }

        namespace {
            bool isExpectedMountEvent(jvmtiExtensionEventInfo& eventInfo) {
                if(strcmp(eventInfo.id, "com.sun.hotspot.events.VirtualThreadMount") != 0) {
                    return false;
                }
                if(eventInfo.param_count != 2) {
                    return false;
                }
                if(eventInfo.params[0].base_type != JVMTI_TYPE_JNIENV) {
                    return false;
                }
                if(eventInfo.params[0].kind != JVMTI_KIND_IN_PTR) {
                    return false;
                }
                if(eventInfo.params[0].null_ok) {
                    return false;
                }
                if(eventInfo.params[1].base_type != JVMTI_TYPE_JTHREAD) {
                    return false;
                }
                if(eventInfo.params[1].kind != JVMTI_KIND_IN) {
                    return false;
                }
                if(eventInfo.params[1].null_ok) {
                    return false;
                }
                return true;
            }

            bool isExpectedUnmountEvent(jvmtiExtensionEventInfo& eventInfo) {
                if(strcmp(eventInfo.id, "com.sun.hotspot.events.VirtualThreadUnmount") != 0) {
                    return false;
                }
                if(eventInfo.param_count != 2) {
                    return false;
                }
                if(eventInfo.params[0].base_type != JVMTI_TYPE_JNIENV) {
                    return false;
                }
                if(eventInfo.params[0].kind != JVMTI_KIND_IN_PTR) {
                    return false;
                }
                if(eventInfo.params[0].null_ok) {
                    return false;
                }
                if(eventInfo.params[1].base_type != JVMTI_TYPE_JTHREAD) {
                    return false;
                }
                if(eventInfo.params[1].kind != JVMTI_KIND_IN) {
                    return false;
                }
                if(eventInfo.params[1].null_ok) {
                    return false;
                }
                return true;
            }

            void JNICALL vtMountHandler(jvmtiEnv* jvmti, JNIEnv* jniEnv, jthread thread, ...) {
                std::cout << "Thread mounted" << std::endl;
                if(!threadMountCallback.isEmpty()) {
                    threadMountCallback.invokeStaticVoid(jniEnv, thread);
                }
            }

            void JNICALL vtUnmountHandler(jvmtiEnv* jvmti, JNIEnv* jniEnv, jthread thread, ...) {
                std::cout << "Thread unmounted" << std::endl;
                if(!threadUnmountCallback.isEmpty()) {
                    threadUnmountCallback.invokeStaticVoid(jniEnv, thread);
                }
            }
        }

        jstring checkVirtualThreadMountEventSupport(JNIEnv* jniEnv) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            jint extensionCount;
            jvmtiExtensionEventInfo* extensionInfos;
            auto error = jvmti->GetExtensionEvents(&extensionCount, &extensionInfos);
            if (error != JVMTI_ERROR_NONE) {
                return formatJString(jniEnv, "Failed to get extension events, return code is ", error);
            }
            bool mountEventFound = false;
            bool unmountEventFound = false;
            for(int i=0; i<extensionCount; i++) {
                mountEventFound = mountEventFound || isExpectedMountEvent(extensionInfos[i]);
                unmountEventFound = unmountEventFound || isExpectedUnmountEvent(extensionInfos[i]);
            }
            jvmti->Deallocate((unsigned char*) extensionInfos);
            if(!mountEventFound) {
                return formatJString(jniEnv, "mount event not found");
            }
            if(!unmountEventFound) {
                return formatJString(jniEnv, "unmount event not found");
            }
            return nullptr;
        }

        ReturnCode setVirtualThreadMountCallbackEnabled(JNIEnv* jniEnv, jboolean enabled) {
            std::cout << "Helloooooo" << std::endl;
            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent has not been initialized");
            }
            jint mountEvIdx=-1;
            jint unmountEvIdx=-1;
            jint extensionCount;
            jvmtiExtensionEventInfo* extensionInfos;
            auto error = jvmti->GetExtensionEvents(&extensionCount, &extensionInfos);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to get extension events, return code is ", error);
            }

            for(int i=0; i<extensionCount; i++) {
                if(isExpectedMountEvent(extensionInfos[i])) {
                    mountEvIdx = extensionInfos[i].extension_event_index;
                }
                if(isExpectedUnmountEvent(extensionInfos[i])) {
                    unmountEvIdx = extensionInfos[i].extension_event_index;
                }
            }
            jvmti->Deallocate((unsigned char*) extensionInfos);
            if(mountEvIdx == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Mount event not found");
            }
            if(unmountEvIdx == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Unmount event not found");
            }
            if (enabled) {
                error = jvmti->SetExtensionEventCallback(mountEvIdx, (jvmtiExtensionEvent) &vtMountHandler);
                if (error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set mount event handler, error code is  ", error);
                }
                error = jvmti->SetExtensionEventCallback(unmountEvIdx, (jvmtiExtensionEvent) &vtUnmountHandler);
                if (error != JVMTI_ERROR_NONE) {
                    jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set unmount event handler, error code is  ", error);
                }
            } else {
                auto err1 = jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                auto err2 = jvmti->SetExtensionEventCallback(unmountEvIdx, nullptr);
                if (err1 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to unset mount event handler, error code is  ", err1);
                }
                if (err2 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to unset unmount event handler, error code is  ", err2);
                }
            }
            threadMountCallbacksEnabled = enabled;
            std::cout << "Set event handlers to " << (enabled ? "enabled" : "disabled") << std::endl;
            return ReturnCode::SUCCESS;
        }

    } // namespace jvmti_agent
    
} // namespace elastic
