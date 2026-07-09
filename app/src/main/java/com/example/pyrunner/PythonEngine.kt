package com.example.pyrunner

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chaquopy를 대체하는, 공식 CPython Android 배포판을 직접 임베드하는 엔진.
 *
 * 사용 순서:
 *   1. init(context) - 최초 1회, assets에서 stdlib를 내부 저장소로 복사 + 네이티브 리다이렉션 설정
 *   2. runCode(code) - 백그라운드 스레드에서 호출 (블로킹)
 *   3. writeStdin(text) - input() 대기 중일 때 사용자 입력을 흘려보냄
 *
 * onNativeOutput / onNativeError 콜백은 네이티브 리다이렉션 스레드에서 호출되므로
 * (메인/UI 스레드가 아님) 리스너 쪽에서 필요하면 runOnUiThread로 감싸야 한다.
 */
class PythonEngine(private val context: Context) {

    var listener: Listener? = null

    interface Listener {
        fun onOutput(text: String)
        fun onError(text: String)
    }

    private lateinit var pythonHome: File
    private var nativeInitialized = false

    // logcat 없이도 "네이티브 콜백이 실제로 호출됐는지"를 눈으로 확인하기 위한 카운터.
    // (adb 없는 환경에서 진단용)
    val outputCallbackCount = AtomicInteger(0)
    val errorCallbackCount = AtomicInteger(0)

    fun resetCallbackCounters() {
        outputCallbackCount.set(0)
        errorCallbackCount.set(0)
    }

    companion object {
        init {
            System.loadLibrary("pyrunner_native")
        }
    }

    /** assets/python 을 내부 저장소로 풀고, 네이티브 stdio 리다이렉션을 1회 설정한다. */
    fun init() {
        // Python이 임시 디렉터리를 찾을 때 쓰는 변수 (API 33 미만에서는 안드로이드가 안 채워줌)
        Os.setenv("TMPDIR", context.cacheDir.toString(), false)

        pythonHome = extractAssetsIfNeeded()

        if (!nativeInitialized) {
            nativeInit()
            nativeInitialized = true
        }
    }

    /** 코드를 실행한다. 반드시 백그라운드 스레드에서 호출할 것 (Py_RunMain은 블로킹). */
    fun runCode(code: String): Int {
        return nativeRunCode(pythonHome.absolutePath, code)
    }

    /** input() 이 대기 중일 때 사용자가 입력한 값을 파이썬 stdin으로 전달 ('\n' 포함해서 넘길 것) */
    fun writeStdin(text: String) {
        nativeWriteStdin(text)
    }

    // --- 네이티브에서 호출되는 콜백 (별도 스레드에서 호출됨) -----------------------

    @Suppress("unused") // pyrunner_native.c 의 relay_thread에서 리플렉션 없이 JNI로 직접 호출
    fun onNativeOutput(text: String) {
        outputCallbackCount.incrementAndGet()
        listener?.onOutput(text)
    }

    @Suppress("unused")
    fun onNativeError(text: String) {
        errorCallbackCount.incrementAndGet()
        listener?.onError(text)
    }

    // --- assets -> 내부 저장소 추출 -------------------------------------------

    private fun extractAssetsIfNeeded(): File {
        val home = File(context.filesDir, "python")
        val marker = File(home, ".extracted_v1")
        if (home.exists() && marker.exists()) {
            return home
        }
        if (home.exists() && !home.deleteRecursively()) {
            throw RuntimeException("Failed to clear $home")
        }
        extractAssetDir("python", context.filesDir)

        val cwd = File(home, "cwd")
        if (!cwd.exists()) cwd.mkdirs()

        marker.createNewFile()
        return home
    }

    private fun extractAssetDir(path: String, targetDir: File) {
        val names = context.assets.list(path) ?: throw RuntimeException("Failed to list $path")
        val targetSubdir = File(targetDir, path)
        if (!targetSubdir.exists() && !targetSubdir.mkdirs()) {
            throw RuntimeException("Failed to create $targetSubdir")
        }

        for (name in names) {
            val subPath = "$path/$name"
            val input: InputStream
            try {
                input = context.assets.open(subPath)
            } catch (e: FileNotFoundException) {
                // 파일이 아니라 디렉터리였다는 뜻 -> 재귀
                extractAssetDir(subPath, targetDir)
                continue
            }
            input.use { stream ->
                File(targetSubdir, name).outputStream().use { output ->
                    stream.copyTo(output)
                }
            }
        }
    }

    // --- JNI ------------------------------------------------------------------

    private external fun nativeInit()
    private external fun nativeRunCode(home: String, code: String): Int
    private external fun nativeWriteStdin(text: String)
}