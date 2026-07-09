// pyrunner_native.c
//
// PythonEngine의 JNI 구현체. 공식 CPython 안드로이드 배포판(testbed 예제)의
// 임베딩 패턴을 기반으로, "코드를 -c로 실행하고 stdout/stderr는 콜백으로,
// stdin은 파이프로 받는" 형태로 단순화했다.

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

static JavaVM *g_vm = NULL;
static jobject g_engine = NULL;       // PythonEngine 인스턴스에 대한 전역 참조
static jmethodID g_onOutput = NULL;   // void onNativeOutput(String)
static jmethodID g_onError = NULL;    // void onNativeError(String)

static int g_stdin_write_fd = -1;     // Kotlin -> 파이썬 input()으로 보낼 파이프 쓰기용 fd

static void throw_runtime_exception(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), message);
}

static void throw_errno(JNIEnv *env, const char *prefix) {
    char msg[512];
    snprintf(msg, sizeof(msg), "%s: %s", prefix, strerror(errno));
    throw_runtime_exception(env, msg);
}

// --- stdout/stderr 파이프 -> 콜백 릴레이 스레드 -----------------------------

typedef struct {
    FILE *file;
    int fd;
    jmethodID method;
    int pipe_fd[2];
} StreamInfo;

static StreamInfo STREAMS[2]; // [0]=stdout, [1]=stderr

static void *relay_thread(void *arg) {
    StreamInfo *si = (StreamInfo *) arg;
    JNIEnv *env;
    // pthread로 만든 스레드는 JVM에 붙어있지 않으므로 직접 attach 해야 콜백 호출 가능
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
        return NULL;
    }

    char buf[MAX_CHUNK];
    ssize_t n;
    while ((n = read(si->pipe_fd[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        jstring jtext = (*env)->NewStringUTF(env, buf);
        (*env)->CallVoidMethod(env, g_engine, si->method, jtext);
        (*env)->DeleteLocalRef(env, jtext);
    }

    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

static char *redirect_stream(StreamInfo *si) {
    if (setvbuf(si->file, NULL, _IONBF, 0)) return "setvbuf";
    if (pipe(si->pipe_fd)) return "pipe";
    if (dup2(si->pipe_fd[1], si->fd) == -1) return "dup2";

    pthread_t thr;
    if ((errno = pthread_create(&thr, NULL, relay_thread, si))) return "pthread_create";
    if ((errno = pthread_detach(thr))) return "pthread_detach";
    return NULL;
}

// --- JNI 진입점 ---------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_example_pyrunner_PythonEngine_nativeInit(JNIEnv *env, jobject thiz) {
    (*env)->GetJavaVM(env, &g_vm);
    g_engine = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_onOutput = (*env)->GetMethodID(env, cls, "onNativeOutput", "(Ljava/lang/String;)V");
    g_onError = (*env)->GetMethodID(env, cls, "onNativeError", "(Ljava/lang/String;)V");

    STREAMS[0] = (StreamInfo){stdout, STDOUT_FILENO, g_onOutput, {-1, -1}};
    STREAMS[1] = (StreamInfo){stderr, STDERR_FILENO, g_onError, {-1, -1}};

    for (int i = 0; i < 2; i++) {
        char *err = redirect_stream(&STREAMS[i]);
        if (err) {
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
}

JNIEXPORT void JNICALL
Java_com_example_pyrunner_PythonEngine_nativeWriteStdin(JNIEnv *env, jobject thiz, jstring text) {
    if (g_stdin_write_fd < 0) return;
    const char *utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    write(g_stdin_write_fd, utf8, strlen(utf8));
    (*env)->ReleaseStringUTFChars(env, text, utf8);
}

JNIEXPORT jint JNICALL
Java_com_example_pyrunner_PythonEngine_nativeRunCode(
    JNIEnv *env, jobject thiz, jstring home, jstring code
) {
    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);

    char cwd[PATH_MAX];
    snprintf(cwd, sizeof(cwd), "%s/%s", home_utf8, "cwd");
    chdir(cwd); // 실패해도 치명적이지 않음 (cwd 없으면 홈 디렉터리에서 실행)

    PyConfig config;
    PyStatus status;
    PyConfig_InitPythonConfig(&config);

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

    int exit_code = Py_RunMain();

    (*env)->ReleaseStringUTFChars(env, home, home_utf8);
    (*env)->ReleaseStringUTFChars(env, code, code_utf8);

    return exit_code;
}
