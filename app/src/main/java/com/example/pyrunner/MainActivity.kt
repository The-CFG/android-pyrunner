package com.example.pyrunner

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var editCode: EditText
    private lateinit var txtLineNumbers: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnRun: Button
    private lateinit var btnStop: Button
    private lateinit var btnPip: Button
    private lateinit var btnLog: Button
    private lateinit var txtConsole: TextView
    private lateinit var scrollConsole: ScrollView
    private lateinit var layoutInput: LinearLayout
    private lateinit var editInput: EditText
    private lateinit var btnSend: Button

    private lateinit var highlighter: SyntaxHighlighter
    private lateinit var engine: PythonEngine

    // 파이썬 실행 전용 백그라운드 단일 스레드 풀
    private var executor: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 뷰 초기화
        editCode = findViewById(R.id.editCode)
        txtLineNumbers = findViewById(R.id.txtLineNumbers)
        txtStatus = findViewById(R.id.txtStatus)
        btnRun = findViewById(R.id.btnRun)
        btnStop = findViewById(R.id.btnStop)
        btnPip = findViewById(R.id.btnPip)
        btnLog = findViewById(R.id.btnLog)
        txtConsole = findViewById(R.id.txtConsole)
        scrollConsole = findViewById(R.id.scrollConsole)
        layoutInput = findViewById(R.id.layoutInput)
        editInput = findViewById(R.id.editInput)
        btnSend = findViewById(R.id.btnSend)

        setupLineNumbers()

        highlighter = SyntaxHighlighter(
            colorKeyword = ContextCompat.getColor(this, R.color.syntax_keyword),
            colorString = ContextCompat.getColor(this, R.color.syntax_string),
            colorComment = ContextCompat.getColor(this, R.color.syntax_comment),
            colorNumber = ContextCompat.getColor(this, R.color.syntax_number),
            colorFunction = ContextCompat.getColor(this, R.color.syntax_function),
            colorBuiltin = ContextCompat.getColor(this, R.color.syntax_builtin)
        )
        editCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s != null) highlighter.highlight(s)
            }
        })

        // 공식 CPython Android 배포판을 임베드하는 엔진. 최초 asset 추출은
        // 시간이 걸릴 수 있으므로 백그라운드 스레드에서 준비한다.
        engine = PythonEngine(this)
        engine.listener = object : PythonEngine.Listener {
            override fun onOutput(text: String) {
                appendConsole(text, ContextCompat.getColor(this@MainActivity, R.color.console_stdout))
            }

            override fun onError(text: String) {
                appendConsole(text, ContextCompat.getColor(this@MainActivity, R.color.console_stderr))
            }
        }
        btnRun.isEnabled = false
        btnPip.isEnabled = false
        txtStatus.text = "초기화 중..."
        Executors.newSingleThreadExecutor().execute {
            engine.init()
            engine.resetCallbackCounters()
            engine.testOutput()
            // testOutput()은 파이썬 없이 fd에 직접 write하므로, 여기서 카운터가 0이면
            // dup2/파이프/relay_thread 구조 자체가 문제라는 뜻이고, 1 이상이면
            // 파이프 구조는 정상이고 문제는 파이썬 쪽 write에 있다는 뜻이다.
            val testOutCnt = engine.outputCallbackCount.get()
            val testErrCnt = engine.errorCallbackCount.get()
            runOnUiThread {
                appendConsole(
                    "[진단] nativeTestOutput 콜백 stdout=$testOutCnt stderr=$testErrCnt\n",
                    ContextCompat.getColor(this@MainActivity, R.color.console_success)
                )
                txtStatus.text = ""
                btnRun.isEnabled = true
                btnPip.isEnabled = true
            }
        }

        btnRun.setOnClickListener {
            val code = editCode.text.toString()
            if (code.isNotBlank()) {
                startPythonExecution(code)
            }
        }

        btnStop.setOnClickListener {
            stopPythonExecution()
        }

        btnPip.setOnClickListener {
            showPackageManagerDialog()
        }

        btnLog.setOnClickListener {
            showLogcatDialog()
        }

        btnSend.setOnClickListener {
            val text = editInput.text.toString()
            // 실제 파이프를 통해 네이티브 stdin으로 전달 (input()이 그대로 받아감)
            engine.writeStdin(text + "\n")
            appendConsole(text + "\n", ContextCompat.getColor(this@MainActivity, R.color.console_stdout))
            editInput.setText("")
        }
    }

    // 코드 줄 수 변화에 맞춰 줄번호를 갱신하고, 에디터 스크롤과 줄번호 스크롤을 동기화
    private fun setupLineNumbers() {
        updateLineNumbers(editCode.lineCount.coerceAtLeast(1))

        editCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateLineNumbers(editCode.lineCount.coerceAtLeast(1))
            }
        })

        editCode.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            txtLineNumbers.scrollTo(0, scrollY)
        }
    }

    private fun updateLineNumbers(lineCount: Int) {
        val sb = StringBuilder()
        for (i in 1..lineCount) {
            sb.append(i)
            if (i != lineCount) sb.append('\n')
        }
        txtLineNumbers.text = sb.toString()
    }

    private fun appendConsole(text: String, color: Int) {
        runOnUiThread {
            val spannable = SpannableString(text)
            spannable.setSpan(ForegroundColorSpan(color), 0, text.length, 0)
            txtConsole.append(spannable)
            scrollConsole.post { scrollConsole.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun startPythonExecution(code: String) {
        txtConsole.text = ""
        txtStatus.text = "실행 중..."
        btnRun.isEnabled = false
        btnPip.isEnabled = false
        btnStop.isEnabled = true
        setInputUIVisibility(true) // input() 호출 시점을 알 수 없으므로 실행 중엔 항상 입력 가능하게 둠

        val startTime = System.currentTimeMillis()
        engine.resetCallbackCounters()

        executor = Executors.newSingleThreadExecutor()
        executor?.execute {
            var exitCode = -1
            try {
                exitCode = engine.runCode(code)
            } catch (e: Exception) {
                appendConsole(
                    "\n[네이티브 에러: ${e.message}]\n",
                    ContextCompat.getColor(this@MainActivity, R.color.console_stderr)
                )
            } finally {
                val elapsedMs = System.currentTimeMillis() - startTime
                val statusColor = if (exitCode == 0)
                    R.color.console_success else R.color.console_stderr
                // 진단용: 네이티브 콜백이 실제로 몇 번 호출됐는지 함께 표시 (logcat 없이 확인용)
                val outCnt = engine.outputCallbackCount.get()
                val errCnt = engine.errorCallbackCount.get()
                appendConsole(
                    "\n[완료 (종료 코드 $exitCode, ${elapsedMs}ms, 콜백 stdout=$outCnt stderr=$errCnt)]\n",
                    ContextCompat.getColor(this@MainActivity, statusColor)
                )
                runOnUiThread {
                    txtStatus.text = "완료 (${elapsedMs}ms)"
                    btnRun.isEnabled = true
                    btnPip.isEnabled = true
                    btnStop.isEnabled = false
                    setInputUIVisibility(false)
                }
            }
        }
    }

    private fun stopPythonExecution() {
        // 주의: 네이티브 Py_RunMain()은 자바 스레드 인터럽트로 멈출 수 없다.
        // 무한루프 등은 프로세스 자체를 재시작해야 멈추는 근본적 한계가 있음 (추후 개선 대상).
        executor?.shutdownNow()
        appendConsole(
            "\n[중지 요청됨: 실행 중인 코드가 즉시 멈추지 않을 수 있습니다]\n",
            ContextCompat.getColor(this@MainActivity, R.color.console_stderr)
        )
        runOnUiThread {
            txtStatus.text = "중지 요청됨"
            btnRun.isEnabled = true
            btnPip.isEnabled = true
            btnStop.isEnabled = false
            setInputUIVisibility(false)
        }
    }

    private fun setInputUIVisibility(enabled: Boolean) {
        layoutInput.isEnabled = enabled
        editInput.isEnabled = enabled
        btnSend.isEnabled = enabled
    }

    // --- pip 패키지 관리 다이얼로그 ---------------------------------------------

    private fun showPackageManagerDialog() {
        val editSpec = EditText(this).apply {
            hint = "패키지명 (예: requests, six==1.16.0)"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(editSpec)
        }

        AlertDialog.Builder(this)
            .setTitle("패키지 관리 (pip)")
            .setMessage(
                "주의: 이 빌드엔 zlib 모듈이 없어 압축된 wheel(zip)은 대부분 설치에 실패합니다.\n" +
                    "순수 파이썬 + 무압축(STORED) wheel만 설치될 수 있습니다."
            )
            .setView(container)
            .setPositiveButton("설치") { _, _ ->
                val spec = editSpec.text.toString().trim()
                if (spec.isNotEmpty()) startPackageInstall(spec)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun startPackageInstall(spec: String) {
        txtStatus.text = "패키지 설치 중..."
        btnRun.isEnabled = false
        btnPip.isEnabled = false
        appendConsole(
            "\n[pip install $spec 시작]\n",
            ContextCompat.getColor(this, R.color.console_stdout)
        )

        Executors.newSingleThreadExecutor().execute {
            var exitCode = -1
            try {
                exitCode = engine.installPackage(spec)
            } catch (t: Throwable) {
                appendConsole(
                    "\n[네이티브/JVM 에러: ${t.javaClass.simpleName}: ${t.message}]\n" +
                        "(이 정도까진 잡혔다는 건 크래시가 아니라 예외였다는 뜻. 만약 이 메시지 없이 " +
                        "앱이 그냥 꺼졌다면 진짜 네이티브 크래시이므로 '📋 로그' 버튼으로 확인하세요.)\n",
                    ContextCompat.getColor(this@MainActivity, R.color.console_stderr)
                )
            } finally {
                val statusColor = if (exitCode == 0) R.color.console_success else R.color.console_stderr
                appendConsole(
                    "\n[pip 종료 코드 $exitCode]\n",
                    ContextCompat.getColor(this@MainActivity, statusColor)
                )
                runOnUiThread {
                    txtStatus.text = ""
                    btnRun.isEnabled = true
                    btnPip.isEnabled = true
                }
            }
        }
    }

    // --- 로그캣 뷰어 (adb 없이 폰에서 바로 확인용) --------------------------------
    //
    // 네이티브 크래시(세그폴트)는 Kotlin try/catch로 절대 못 잡고 프로세스가
    // 그대로 죽는다. logcat 링버퍼는 OS 레벨이라 앱이 재시작돼도 최근 로그가
    // 남아있으므로, 크래시 후 앱을 다시 켜서 이 버튼을 눌러도 직전 크래시
    // 원인(Fatal signal ... / faulthandler 트레이스백 등)을 확인할 수 있다.
    // 단, 안드로이드 버전/제조사에 따라 자기 프로세스 로그만 보이거나
    // logcat 실행 자체가 막혀 있을 수 있다 (그럴 땐 아래에서 에러 메시지로 안내).

    private fun showLogcatDialog() {
        val progress = TextView(this).apply {
            text = "로그 읽는 중..."
            setPadding(32, 32, 32, 32)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("로그 (logcat 최근 500줄)")
            .setView(progress)
            .setNegativeButton("닫기", null)
            .setNeutralButton("복사", null) // 아래에서 리스너 다시 세팅 (로그 로딩 후)
            .create()
        dialog.show()

        Executors.newSingleThreadExecutor().execute {
            val logText = try {
                val process = ProcessBuilder("logcat", "-d", "-t", "500", "-v", "brief")
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText().ifBlank { "(로그가 비어 있음)" }
            } catch (t: Throwable) {
                "logcat 실행 실패: ${t.javaClass.simpleName}: ${t.message}\n" +
                    "이 기기/안드로이드 버전에서는 앱이 직접 logcat을 실행할 수 없을 수 있습니다.\n" +
                    "PC가 있다면: adb logcat -s PyRunnerNative *:E"
            }

            runOnUiThread {
                if (!isFinishing) {
                    val scrollableText = TextView(this).apply {
                        text = logText
                        setTextIsSelectable(true)
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 11f
                        setPadding(24, 24, 24, 24)
                    }
                    val scroll = ScrollView(this).apply { addView(scrollableText) }
                    dialog.setView(scroll)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("logcat", logText))
                        Toast.makeText(this, "클립보드에 복사됨", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}