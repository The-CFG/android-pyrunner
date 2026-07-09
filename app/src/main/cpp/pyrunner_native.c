// pyrunner_native.c
//
// PythonEngine의 JNI 구현체. 공식 CPython 안드로이드 배포판(testbed 예제)의
// 임베딩 패턴을 기반으로, "코드를 -c로 실행하고 stdout/stderr는 콜백으로,
// stdin은 파이프로 받는" 형태로 단순화했다.
//
// [디버깅용] 출력이 안 보이는 문제를 진단하기 위해 각 단계마다
// __android_log_print로 로그를 남긴다. `adb logcat -s PyRunnerNative`로 확인.

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <Python.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>

#define LOG_TAG "PyRunnerNative"
#define MAX_CHUNK 4000

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jobject g_engine = NULL;       // PythonEngine 인스턴스에 대한 전역 참조
static jmethodID g_onOutput = NULL;   // void onNativeOutput(String)
static jmethodID g_onError = NULL;    // void onNativeError(String)

static int g_stdin_write_fd = -1;     // Kotlin -> 파이썬 input()으로 보낼 파이프 쓰기용 fd

static void throw_runtime_exception(JNIEnv *env, const char *message) {
    LOGE("throw_runtime_exception: %s", message);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), message);
}

static void throw_errno(JNIEnv *env, const char *prefix) {
    char msg[512];
    snprintf(msg, sizeof(msg), "%s: %s", prefix, strerror(errno));
    throw_runtime_exception(env, msg);
}

// --- stdout/stderr 파이프 -> 콜백 릴레이 스레드 -----------------------------

typedef struct {
    const char *name;
    FILE *file;
    int fd;
    jmethodID method;
    int pipe_fd[2];
} StreamInfo;

static StreamInfo STREAMS[2]; // [0]=stdout, [1]=stderr

static void *relay_thread(void *arg) {
    StreamInfo *si = (StreamInfo *) arg;
    LOGD("[%s] relay_thread 시작, pipe_fd={%d,%d}", si->name, si->pipe_fd[0], si->pipe_fd[1]);

    JNIEnv *env;
    // pthread로 만든 스레드는 JVM에 붙어있지 않으므로 직접 attach 해야 콜백 호출 가능
    int attach_result = (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    if (attach_result != 0) {
        LOGE("[%s] AttachCurrentThread 실패: %d", si->name, attach_result);
        return NULL;
    }
    LOGD("[%s] AttachCurrentThread 성공, read 루프 진입", si->name);

    char buf[MAX_CHUNK];
    ssize_t n;
    while ((n = read(si->pipe_fd[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        LOGD("[%s] read()로 %zd 바이트 수신: %.100s", si->name, n, buf);
        jstring jtext = (*env)->NewStringUTF(env, buf);
        (*env)->CallVoidMethod(env, g_engine, si->method, jtext);
        if ((*env)->ExceptionCheck(env)) {
            LOGE("[%s] 콜백 호출 중 자바 예외 발생", si->name);
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, jtext);
    }
    LOGD("[%s] read 루프 종료 (n=%zd, errno=%s)", si->name, n, strerror(errno));

    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

static char *redirect_stream(StreamInfo *si) {
    if (setvbuf(si->file, NULL, _IONBF, 0)) return "setvbuf";
    if (pipe(si->pipe_fd)) return "pipe";
    LOGD("[%s] pipe 생성됨: read_fd=%d write_fd=%d, 원래 fd=%d", si->name, si->pipe_fd[0], si->pipe_fd[1], si->fd);
    if (dup2(si->pipe_fd[1], si->fd) == -1) return "dup2";
    LOGD("[%s] dup2 완료: fd %d 가 이제 pipe write end를 가리킴", si->name, si->fd);

    pthread_t thr;
    if ((errno = pthread_create(&thr, NULL, relay_thread, si))) return "pthread_create";
    if ((errno = pthread_detach(thr))) return "pthread_detach";
    return NULL;
}

// --- JNI 진입점 ---------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_example_pyrunner_PythonEngine_nativeInit(JNIEnv *env, jobject thiz) {
    LOGD("nativeInit 시작");
    (*env)->GetJavaVM(env, &g_vm);
    g_engine = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_onOutput = (*env)->GetMethodID(env, cls, "onNativeOutput", "(Ljava/lang/String;)V");
    g_onError = (*env)->GetMethodID(env, cls, "onNativeError", "(Ljava/lang/String;)V");
    LOGD("메서드ID 확보: onOutput=%p onError=%p", (void *) g_onOutput, (void *) g_onError);
    if ((*env)->ExceptionCheck(env)) {
        LOGE("GetMethodID 중 예외 발생 (메서드 시그니처 불일치 가능성)");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    STREAMS[0] = (StreamInfo){"stdout", stdout, STDOUT_FILENO, g_onOutput, {-1, -1}};
    STREAMS[1] = (StreamInfo){"stderr", stderr, STDERR_FILENO, g_onError, {-1, -1}};

    for (int i = 0; i < 2; i++) {
        char *err = redirect_stream(&STREAMS[i]);
        if (err) {
            LOGE("redirect_stream(%s) 실패: %s", STREAMS[i].name, err);
            throw_errno(env, err);
            return;
        }
    }

    // stdin: 읽기 끝은 실제 STDIN_FILENO로 dup2, 쓰기 끝은 Kotlin이 nativeWriteStdin으로 사용
    int stdin_pipe[2];
    if (pipe(stdin_pipe)) {
        throw_errno(env, "pipe(stdin)");
        return;
    }
    if (dup2(stdin_pipe[0], STDIN_FILENO) == -1) {
        throw_errno(env, "dup2(stdin)");
        return;
    }
    g_stdin_write_fd = stdin_pipe[1];
    LOGD("nativeInit 완료 (stdin_write_fd=%d)", g_stdin_write_fd);
}

JNIEXPORT void JNICALL
Java_com_example_pyrunner_PythonEngine_nativeWriteStdin(JNIEnv *env, jobject thiz, jstring text) {
    if (g_stdin_write_fd < 0) return;
    const char *utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    ssize_t written = write(g_stdin_write_fd, utf8, strlen(utf8));
    LOGD("nativeWriteStdin: %zd 바이트 씀", written);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
}

JNIEXPORT jint JNICALL
Java_com_example_pyrunner_PythonEngine_nativeRunCode(
    JNIEnv *env, jobject thiz, jstring home, jstring code
) {
    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);
    LOGD("nativeRunCode 시작, home=%s", home_utf8);

    char cwd[PATH_MAX];
    snprintf(cwd, sizeof(cwd), "%s/%s", home_utf8, "cwd");
    if (chdir(cwd) != 0) {
        LOGE("chdir(%s) 실패: %s (치명적이지 않음, 계속 진행)", cwd, strerror(errno));
    }

    PyConfig config;
    PyStatus status;
    PyConfig_InitPythonConfig(&config);

    // 버퍼링 때문에 stdout/stderr가 늦게(혹은 인터프리터 종료 시점에만) 파이프로
    // flush되는 걸 막기 위해 강제로 unbuffered 모드 사용 (python -u 와 동일)
    config.buffered_stdio = 0;

    // argv[0]는 임베디드 모드에서 실행파일 이름 자리(비워둠), -c 뒤에 코드 본문을 그대로 전달
    const char *argv[] = {"", "-c", code_utf8};
    status = PyConfig_SetBytesArgv(&config, 3, (char **) argv);
    if (PyStatus_Exception(status)) {
        throw_runtime_exception(env, status.err_msg ? status.err_msg : "PyConfig_SetBytesArgv failed");
        PyConfig_Clear(&config);
        return 1;
    }

    status = PyConfig_SetBytesString(&config, &config.home, home_utf8);
    if (PyStatus_Exception(status)) {
        throw_runtime_exception(env, status.err_msg ? status.err_msg : "PyConfig_SetBytesString failed");
        PyConfig_Clear(&config);
        return 1;
    }

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) {
        throw_runtime_exception(env, status.err_msg ? status.err_msg : "Py_InitializeFromConfig failed");
        return 1;
    }
    LOGD("Py_InitializeFromConfig 성공, Py_RunMain 호출");

    int exit_code = Py_RunMain();
    LOGD("Py_RunMain 반환: exit_code=%d", exit_code);

    (*env)->ReleaseStringUTFChars(env, home, home_utf8);
    (*env)->ReleaseStringUTFChars(env, code, code_utf8);

    return exit_code;
}