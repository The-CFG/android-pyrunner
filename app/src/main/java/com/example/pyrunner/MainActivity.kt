package com.example.pyrunner

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
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
            btnStop.isEnabled = false
            setInputUIVisibility(false)
        }
    }

    private fun setInputUIVisibility(enabled: Boolean) {
        layoutInput.isEnabled = enabled
        editInput.isEnabled = enabled
        btnSend.isEnabled = enabled
    }
}