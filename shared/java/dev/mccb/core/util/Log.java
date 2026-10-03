package dev.mccb.core.util;

/**
 * 极简日志门面。
 *
 * <p>核心层刻意不直接依赖 slf4j：这样它就能保持"零外部依赖"，
 * 将来被任意 Minecraft 版本模块复用时不会带进额外的类加载问题。
 * 真正写日志的实现由各版本模块在启动时通过 {@link #setSink(Sink)} 注入。
 */
public final class Log {

	public enum Level {
		DEBUG, INFO, WARN, ERROR
	}

	public interface Sink {
		void log(Level level, String message, Throwable error);
	}

	private static volatile Sink sink = (level, message, error) -> {
	};

	private Log() {
	}

	public static void setSink(Sink newSink) {
		sink = newSink == null ? (level, message, error) -> {
		} : newSink;
	}

	public static void debug(String message) {
		sink.log(Level.DEBUG, message, null);
	}

	public static void info(String message) {
		sink.log(Level.INFO, message, null);
	}

	public static void warn(String message) {
		sink.log(Level.WARN, message, null);
	}

	public static void warn(String message, Throwable error) {
		sink.log(Level.WARN, message, error);
	}

	public static void error(String message, Throwable error) {
		sink.log(Level.ERROR, message, error);
	}
}
