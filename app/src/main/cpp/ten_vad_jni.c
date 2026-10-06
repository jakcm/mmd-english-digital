// TEN VAD JNI 薄封装（Apache-2.0，TEN Framework / Agora）
// 官方提供的 Java 绑定依赖 JNA（Android 上不合适），这里用 NDK 直接编一个
// 最小 JNI 桥，链接预编译的 libten_vad.so（532KB，仅依赖 libc/libm/libdl）。
#include <jni.h>
#include <stdint.h>
#include "ten_vad.h"

#define JNI_FN(name) Java_com_mmd_englishdigital_TenVadNative_##name

JNIEXPORT jlong JNICALL
JNI_FN(nativeCreate)(JNIEnv *env, jobject thiz, jint hop_size, jfloat threshold) {
    (void) env; (void) thiz;
    ten_vad_handle_t h = NULL;
    if (ten_vad_create(&h, (size_t) hop_size, (float) threshold) != 0) return 0;
    return (jlong)(intptr_t) h;
}

/** 返回 float[2] = {概率[0..1], flag(0/1)}；失败返回 null */
JNIEXPORT jfloatArray JNICALL
JNI_FN(nativeProcess)(JNIEnv *env, jobject thiz, jlong handle, jshortArray audio) {
    (void) thiz;
    if (handle == 0 || audio == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, audio);
    jshort *buf = (*env)->GetShortArrayElements(env, audio, NULL);
    if (buf == NULL) return NULL;
    float prob = 0.0f;
    int flag = 0;
    int rc = ten_vad_process((ten_vad_handle_t)(intptr_t) handle,
                             (const int16_t *) buf, (size_t) n, &prob, &flag);
    (*env)->ReleaseShortArrayElements(env, audio, buf, JNI_ABORT);
    if (rc != 0) return NULL;
    jfloatArray out = (*env)->NewFloatArray(env, 2);
    if (out == NULL) return NULL;
    jfloat tmp[2];
    tmp[0] = prob;
    tmp[1] = (jfloat) flag;
    (*env)->SetFloatArrayRegion(env, out, 0, 2, tmp);
    return out;
}

JNIEXPORT void JNICALL
JNI_FN(nativeDestroy)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    if (handle == 0) return;
    ten_vad_handle_t h = (ten_vad_handle_t)(intptr_t) handle;
    ten_vad_destroy(&h);
}

JNIEXPORT jstring JNICALL
JNI_FN(nativeVersion)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    const char *v = ten_vad_get_version();
    return (*env)->NewStringUTF(env, v ? v : "unknown");
}
