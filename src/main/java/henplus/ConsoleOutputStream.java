package henplus;

import java.io.IOException;
import java.io.OutputStream;

import jline.ConsoleReader;

public class ConsoleOutputStream extends OutputStream {

	private ConsoleReader console;

	ConsoleOutputStream(ConsoleReader console) {
		this.console = console;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		String str = new String(b, off, len);
		str = str.replace("\n", "\r\n");
		console.printString(str);
		console.flushConsole();
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b });
	}
}