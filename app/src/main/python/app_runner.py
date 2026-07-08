import sys

class _LineBufferedStream:
    """개행 문자가 나올 때까지 버퍼링했다가, 완성된 줄 단위로만
    코틀린 브릿지로 전달해서 콘솔에 한 줄씩 안전하게 출력되도록 함."""

    def __init__(self, emit):
        self._emit = emit
        self._buffer = ""

    def write(self, text):
        if not text:
            return
        self._buffer += text
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            self._emit(line + "\n")

    def flush_remaining(self):
        # 개행 없이 끝난 마지막 조각(예: input 프롬프트)도 놓치지 않고 출력
        if self._buffer:
            self._emit(self._buffer)
            self._buffer = ""

    def flush(self):
        pass

class AndroidStdout(_LineBufferedStream):
    def __init__(self, bridge):
        super().__init__(bridge.onOutput)

class AndroidStderr(_LineBufferedStream):
    def __init__(self, bridge):
        super().__init__(bridge.onError)

class AndroidStdin:
    def __init__(self, bridge, stdout_stream):
        self.bridge = bridge
        self.stdout_stream = stdout_stream

    def readline(self):
        # input()의 프롬프트 문자열은 보통 개행 없이 출력되므로,
        # 버퍼에 남아있는 프롬프트를 먼저 흘려보낸 뒤 입력을 대기함
        self.stdout_stream.flush_remaining()
        # 코틀린 측에서 입력 데이터가 준비될 때까지 스레드를 일시정지(Block)함
        return self.bridge.onInputRequest()

def run_code(code, bridge):
    # 기존 스트림 백업
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_stdin = sys.stdin

    # 안드로이드 맞춤형 스트림으로 교체 (출력/에러 분리 + 줄 단위 버퍼링)
    android_stdout = AndroidStdout(bridge)
    android_stderr = AndroidStderr(bridge)
    sys.stdout = android_stdout
    sys.stderr = android_stderr
    sys.stdin = AndroidStdin(bridge, android_stdout)

    try:
        # 독립된 네임스페이스에서 코드 실행 (전역 변수 오염 방지)
        globals_dict = {"__builtins__": __builtins__}
        exec(code, globals_dict)
    except Exception:
        import traceback
        # 예외는 stderr로 보내서 콘솔에 빨간색으로 표시되게 함
        traceback.print_exc(file=sys.stderr)
    finally:
        # 개행 없이 버퍼에 남아있던 마지막 출력 조각을 마저 흘려보냄
        android_stdout.flush_remaining()
        android_stderr.flush_remaining()
        # 스트림 복구
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        sys.stdin = old_stdin