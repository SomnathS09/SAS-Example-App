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
    //LOGD("Sum of squares in processed array : %d", (short)((sumOfSquares/num_of_shorts*max_short_range)*10));
    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sas_1library_SASAudioRecorder_getNoiseFloor(
        JNIEnv* env,
        jobject /* this */,
        jshortArray inputShortArray,
        jobject userFeat) {
    jshort *inputShortBody = env->GetShortArrayElements(inputShortArray, 0);
    jsize shortArrayLength = env->GetArrayLength(inputShortArray);
    jfieldID nf = env->GetFieldID(env->GetObjectClass(userFeat),"noiseFloor","F");
    float energyFloor = env->GetFloatField(userFeat,nf);
    LOGD("Cumul Energy Floor %f", energyFloor);
    LOGD("Frame Energy %d", inputShortBody[shortArrayLength-1]);
    // Use first 10 frames to establish noise floor
    energyFloor+=(max<int>(1,inputShortBody[shortArrayLength-1])); // Cannot use 0 value since it will lead to energy floor of -infinity
    env->SetFloatField(userFeat,nf,energyFloor);
    if (shortArrayLength==10){
        energyFloor = energyFloor/10;
        energyFloor = 10.0 * log10(energyFloor);
        env->SetFloatField(userFeat,nf,energyFloor);
        LOGD("Energy Floor %f", energyFloor);
        env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
        return true;
    }
    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sas_1library_SASAudioRecorder_lookForStart(
        JNIEnv* env,
        jobject /* this */,
        jshortArray inputShortArray,
        jobject userFeat) {
    jshort *inputShortBody = env->GetShortArrayElements(inputShortArray, 0);
    jsize shortArrayLength = env->GetArrayLength(inputShortArray);
    // Check Energy Buffer for Start of Audio
    float energyFloor = env->GetFloatField(userFeat,env->GetFieldID(env->GetObjectClass(userFeat),"noiseFloor","F"));
    jfieldID nf = env->GetFieldID(env->GetObjectClass(userFeat),"spurtFrameCount","I");
    int highEnergyFrameCount = env->GetIntField(userFeat,nf);
    const float ENERGY_FLOOR_THRESHOLD = 15;
    const int START_SPURT_ENERGY_DURATION_FRAMES = 3; // 100ms spurt expected
    bool foundStartSpurt = false;
    float currentFrameLogEnergy = 10.0 * log10(max<int>(1,inputShortBody[shortArrayLength-1]));
    LOGD("Frame Log Energy %f", currentFrameLogEnergy);
    if (currentFrameLogEnergy>energyFloor+ENERGY_FLOOR_THRESHOLD){
        highEnergyFrameCount+=1;
        if (highEnergyFrameCount>=START_SPURT_ENERGY_DURATION_FRAMES){
            LOGD("Found Start Spurt: %f",currentFrameLogEnergy);
            env->SetIntField(userFeat,env->GetFieldID(env->GetObjectClass(userFeat),"startFrame","I"),shortArrayLength-highEnergyFrameCount);
            highEnergyFrameCount = 0; // Reset this so that it can be used for stop frame
            foundStartSpurt = true;
        }
    }
    else{
        highEnergyFrameCount=0;
    }
    env->SetIntField(userFeat,nf,highEnergyFrameCount);

    //LOGD("Array Length is %d", shortArrayLength);
    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
    if (foundStartSpurt){
        return true;
    }
    else{
        return false;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sas_1library_SASAudioRecorder_lookForStop(
        JNIEnv* env,
        jobject /* this */,
        jshortArray inputShortArray,
        jobject userFeat) {
    jshort *inputShortBody = env->GetShortArrayElements(inputShortArray, 0);
    jsize shortArrayLength = env->GetArrayLength(inputShortArray);
//        LOGD("Array Length is %d", shortArrayLength);

    jfieldID nfEnergy = env->GetFieldID(env->GetObjectClass(userFeat),"maxFrameEnergy","F");
    float energyMax = env->GetFloatField(userFeat,nfEnergy);
    float currentFrameLogEnergy = 10 * log10(max<int>(1,inputShortBody[shortArrayLength-1]));
    energyMax=max<float>(energyMax,currentFrameLogEnergy);
    jfieldID nf = env->GetFieldID(env->GetObjectClass(userFeat),"spurtFrameCount","I");
    int lowEnergyFrameCount = env->GetIntField(userFeat,nf);

    const int END_SPURT_ENERGY_DURATION_FRAMES = 60; // 2 second silence spurt expected
    const float ENERGY_FLOOR_THRESHOLD = 15;
    bool foundEndSpurt = false;

    if (currentFrameLogEnergy<energyMax-ENERGY_FLOOR_THRESHOLD){
        lowEnergyFrameCount+=1;
        if (lowEnergyFrameCount>=END_SPURT_ENERGY_DURATION_FRAMES){
            LOGD("Found End Spurt: %f",currentFrameLogEnergy);
            env->SetIntField(userFeat,env->GetFieldID(env->GetObjectClass(userFeat),"stopFrame","I"),shortArrayLength-lowEnergyFrameCount);
            foundEndSpurt = true;
        }
    }
    else{
        lowEnergyFrameCount=0;
    }
    env->SetIntField(userFeat,nf,lowEnergyFrameCount);
    env->SetFloatField(userFeat,nfEnergy,energyMax);

    env->ReleaseShortArrayElements(inputShortArray, inputShortBody, 0);
    if (foundEndSpurt){
        return true;
    }
    else{
        return false;
    }
}

