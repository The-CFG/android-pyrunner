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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

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

    // 파이썬 전용 백그라운드 단일 스레드 풀
    private var executor: ExecutorService? = null
    // stdin(입력) 데이터를 임시 동기화 보관할 큐
    private val inputQueue = LinkedBlockingQueue<String>()

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

        // Chaquopy 파이썬 플랫폼 엔진 초기화
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setupLineNumbers()

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
            // 큐에 입력 텍스트를 개행문자와 함께 밀어 넣어 대기 중인 파이썬 스레드를 깨움
            inputQueue.put(text + "\n")
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

    // 파이썬 엔진과의 인터페이스를 담당하는 내부 브릿지 객체 정의
    inner class AndroidBridge {
        // 표준 출력(print 등)
        fun onOutput(text: String) {
            appendConsole(text, ContextCompat.getColor(this@MainActivity, R.color.console_stdout))
        }

        // 표준 에러(예외/트레이스백)
        fun onError(text: String) {
            appendConsole(text, ContextCompat.getColor(this@MainActivity, R.color.console_stderr))
        }

        // 파이썬의 input() 함수가 호출되어 입력을 대기할 때 실행됨
        fun onInputRequest(): String {
            runOnUiThread {
                setInputUIVisibility(true)
            }
            // LinkedBlockingQueue에서 요소를 꺼낼 때까지 파이썬 전용 백그라운드 스레드가 대기(Block)함
            val result = try {
                inputQueue.take()
            } catch (e: InterruptedException) {
                "" // 스레드 인터럽트 시 빈 값 반환
            }
            runOnUiThread {
                setInputUIVisibility(false)
            }
            return result
        }
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
        inputQueue.clear()
        txtConsole.text = ""
        txtStatus.text = "실행 중..."
        btnRun.isEnabled = false
        btnStop.isEnabled = true
        setInputUIVisibility(false)

        val startTime = System.currentTimeMillis()

        executor = Executors.newSingleThreadExecutor()
        executor?.execute {
            try {
                val py = Python.getInstance()
                val appRunner = py.getModule("app_runner")

                // 실행 및 입출력 제어 함수 호출
                appRunner.callAttr("run_code", code, AndroidBridge())
            } catch (e: Exception) {
                appendConsole(
                    "\n[에러 발생: ${e.message}]\n",
                    ContextCompat.getColor(this@MainActivity, R.color.console_stderr)
                )
            } finally {
                val elapsedMs = System.currentTimeMillis() - startTime
                appendConsole(
                    "\n[완료 (${elapsedMs}ms)]\n",
                    ContextCompat.getColor(this@MainActivity, R.color.console_success)
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
        // 백그라운드 스레드를 즉시 종료하도록 시도
        executor?.shutdownNow()
        appendConsole(
            "\n[작업이 사용자에 의해 중지되었습니다.]\n",
            ContextCompat.getColor(this@MainActivity, R.color.console_stderr)
        )
        runOnUiThread {
            txtStatus.text = "중지됨"
            btnRun.isEnabled = true
            btnStop.isEnabled = false
            setInputUIVisibility(false)
        }
    }

    private fun setInputUIVisibility(enabled: Boolean) {
        layoutInput.isEnabled = enabled
        editInput.isEnabled = enabled
        btnSend.isEnabled = enabled
        if (enabled) {
            editInput.requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor?.shutdownNow()
    }
}