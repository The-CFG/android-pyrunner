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

    /**
     * pip install을 실행한다. 서브프로세스를 못 쓰는 환경(Android)이라
     * ensurepip.bootstrap() 대신 pip 번들 wheel을 sys.path에 직접 넣고
     * pip._internal.cli.main을 같은 프로세스 안에서 직접 호출한다.
     * runCode()와 동일한 native 경로(nativeRunCode)를 재사용하므로
     * 출력은 기존 콘솔 콜백(onNativeOutput/onNativeError)으로 그대로 들어온다.
     *
     * 주의: 이 빌드엔 zlib 모듈이 없어서 압축된 wheel(zip)은 zipimport/zipfile로
     * 열 수 없다. 순수 파이썬 + 무압축(STORED) wheel이 아니면 대부분 이 단계에서
     * 실패한다. zlib 확장 모듈이 추가되기 전까지는 참고용으로만 사용할 것.
     */
    fun installPackage(packageSpec: String): Int {
        return nativeRunCode(pythonHome.absolutePath, buildPipInstallCode(packageSpec))
    }

    private fun pyStringLiteral(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun buildPipInstallCode(packageSpec: String): String {
        val pkgLiteral = pyStringLiteral(packageSpec)
        return """
            |import sys, os
            |
            |def _fail(msg):
            |    print(msg, file=sys.stderr)
            |    raise SystemExit(1)
            |
            |try:
            |    import ensurepip
            |    from pathlib import Path
            |    _wheel = sorted(Path(ensurepip.__file__).parent.joinpath("_bundled").glob("pip-*.whl"))[-1]
            |except Exception as e:
            |    _fail("[pip] ensurepip 번들 wheel을 찾지 못함: " + type(e).__name__ + ": " + str(e))
            |
            |_wheel_str = str(_wheel)
            |if _wheel_str not in sys.path:
            |    sys.path.insert(0, _wheel_str)
            |
            |os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
            |os.environ["PIP_NO_INPUT"] = "1"
            |os.environ["PIP_CONFIG_FILE"] = os.devnull
            |
            |try:
            |    from pip._internal.cli.main import main as _pip_main
            |except Exception as e:
            |    _fail(
            |        "[pip] pip 모듈을 불러오지 못했습니다: " + type(e).__name__ + ": " + str(e) + "\n"
            |        "이 빌드엔 zlib 모듈이 없어 압축된 wheel(zip)을 zipimport로 열 수 없는 경우가 많습니다.\n"
            |        "(순수 파이썬 + 무압축 wheel이 아니면 대부분 이 단계에서 실패합니다)"
            |    )
            |
            |_args = ["install", "--disable-pip-version-check", "--no-input",
            |         "--only-binary=:all:", "--no-cache-dir", $pkgLiteral]
            |
            |print("[pip] 실행: pip " + " ".join(_args))
            |_code = _pip_main(_args)
            |if _code != 0:
            |    _fail("[pip] 설치 실패 (exit=" + str(_code) + ")")
            |print("[pip] 설치 완료")
        """.trimMargin()
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

    /** 진단용: 파이썬 없이 fd 1/2에 직접 write해서 파이프+relay 구조 자체를 테스트 */
    fun testOutput() {
        nativeTestOutput()
    }

    // --- JNI ------------------------------------------------------------------

    private external fun nativeInit()
    private external fun nativeRunCode(home: String, code: String): Int
    private external fun nativeWriteStdin(text: String)
    private external fun nativeTestOutput()
}