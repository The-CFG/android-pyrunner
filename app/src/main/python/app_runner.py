import sys

class AndroidStdout:
    def __init__(self, bridge):
        self.bridge = bridge

    def write(self, text):
        # 코틀린 인터페이스를 통해 UI 콘솔로 텍스트 전달
        self.bridge.onOutput(text)

    def flush(self):
        pass

class AndroidStdin:
    def __init__(self, bridge):
        self.bridge = bridge

    def readline(self):
        # 코틀린 측에서 입력 데이터가 준비될 때까지 스레드를 일시정지(Block)함
        return self.bridge.onInputRequest()

def run_code(code, bridge):
    # 기존 스트림 백업
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_stdin = sys.stdin

    # 안드로이드 맞춤형 스트림으로 교체
    sys.stdout = AndroidStdout(bridge)
    sys.stderr = AndroidStdout(bridge)
    sys.stdin = AndroidStdin(bridge)

    try:
        # 독립된 네임스페이스에서 코드 실행 (전역 변수 오염 방지)
        globals_dict = {"__builtins__": __builtins__}
        exec(code, globals_dict)
    except Exception as e:
        import traceback
        traceback.print_exc(file=sys.stdout)
    finally:
        # 스트림 복구
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        sys.stdin = old_stdin