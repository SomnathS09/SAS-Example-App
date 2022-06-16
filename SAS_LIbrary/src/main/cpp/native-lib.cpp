#include <jni.h>
#include <vector>
#include <string.h>
#include <iostream>
#include <cmath>
#include <string>
#include <android/log.h>

#define TAG "SAS_Library"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,    TAG, __VA_ARGS__)

const float max_short_range = 32767.0f;
const float num_of_shorts = 512.0f;

using namespace std;

// _1 is escape character for "_" in package path name :
// https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/design.html#resolving_native_method_names

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sas_1library_SASAudioRecorder_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sas_1library_SASAudioRecorder_stringCheckJNI(
        JNIEnv* env,
        jobject /* this */,
        jstring inputString) {
    const char *str = env->GetStringUTFChars(inputString, 0);
    int len = strlen(str);
    env->ReleaseStringUTFChars(inputString, str);
    return len;
}

extern "C" JNIEXPORT jshort JNICALL
Java_com_example_sas_1library_SASAudioRecorder_processShortArray(
        JNIEnv* env,
        jobject /* this */,
        jshortArray inputShortArray) {
    float sumOfSquares = 0.0f;
    jshort *inputShortBody = env->GetShortArrayElements(inputShortArray, 0);
    jsize shortArrayLength = env->GetArrayLength(inputShortArray);
//    LOGD("Array Length is %d", shortArrayLength);
//    sizeof gives wrong length : int shortArrayLength = sizeof(inputShortArray);
    for (int i=0; i<shortArrayLength; i++) {
//        LOGD("Iteration element %d", inputShortBody[i]);
        sumOfSquares += pow(inputShortBody[i]/max_short_range, 2);
//        LOGD("Sum of iteration is %f", sumOfSquares);
    }
    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
//    LOGD("Sum of squares is %d", (short)((sumOfSquares/num_of_shorts*max_short_range)*10));
//    return (short)(0);
    return (short)((sumOfSquares/num_of_shorts*max_short_range)*10);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sas_1library_SASAudioRecorder_getNativeVectorPointer(
        JNIEnv* env,
        jobject /* this */
        ) {
    vector<int>* ptr = new vector<int>(10);
    return jlong(ptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sas_1library_SASAudioRecorder_analyseProcessedArray(
        JNIEnv* env,
        jobject /* this */,
        jshortArray inputShortArray) {
    float sumOfSquares = 0.0f;
    jshort *inputShortBody = env->GetShortArrayElements(inputShortArray, 0);
    jsize shortArrayLength = env->GetArrayLength(inputShortArray);
//        LOGD("Array Length is %d", shortArrayLength);
    for (int i=0; i<shortArrayLength; i++) {
//        LOGD("Iteration element %d", inputShortBody[i]);

//        Here, Write analysis logic
          sumOfSquares += pow(inputShortBody[i]/max_short_range, 2);
    }
    LOGD("Sum of squares in processed array : %d", (short)((sumOfSquares/num_of_shorts*max_short_range)*10));
    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
    return false;
}