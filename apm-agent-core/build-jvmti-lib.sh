g++ -std=c++20 -O2 -fPIC -shared -I $JAVA_HOME/include -I $JAVA_HOME/include/darwin/ -I src/main/jni -o src/main/resources/jvmti_agent/elastic-jvmti.so src/main/jni/*.cpp
