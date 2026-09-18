package courseclock.coursetime;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * 进程标准输出，固定按 UTF-8 编码。
 *
 * <p>{@code System.out} 用的是平台默认字符集（简中 Windows 上是 GBK），
 * 输出中文会随控制台代码页漂移。命令行与自测都从这里拿输出流，编码只定义在一处。</p>
 */
final class Utf8Stdout {

    private Utf8Stdout() {
    }

    static PrintStream stream() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return System.out;
        }
    }
}
