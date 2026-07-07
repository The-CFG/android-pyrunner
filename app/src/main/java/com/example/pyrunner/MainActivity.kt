package com.example.pyrunner

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : AppCompatActivity() {

    private lateinit var editCode: EditText
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

    // 파이썬 엔진과의 인터페이스를 담당하는 내부 브릿지 객체 정의
    inner class AndroidBridge {
        // 파이썬의 print() 등 출력이 호출될 때 실행됨
        fun onOutput(text: String) {
            runOnUiThread {
                txtConsole.append(text)
                scrollConsole.post { scrollConsole.fullScroll(View.FOCUS_DOWN) }
            }
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

    private fun startPythonExecution(code: String) {
        inputQueue.clear()
        txtConsole.text = ""
        btnRun.isEnabled = false
        btnStop.isEnabled = true
        setInputUIVisibility(false)

        executor = Executors.newSingleThreadExecutor()
        executor?.execute {
            try {
                val py = Python.getInstance()
                val appRunner = py.getModule("app_runner")
                
                // 실행 및 입출력 제어 함수 호출
                appRunner.callAttr("run_code", code, AndroidBridge())
            } catch (e: Exception) {
                runOnUiThread {
                    txtConsole.append("\n[에러 발생: ${e.message}]\n")
                }
            } finally {
                runOnUiThread {
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
        runOnUiThread {
            txtConsole.append("\n[작업이 사용자에 의해 중지되었습니다.]\n")
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