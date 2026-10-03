package dev.mccb.core.webdav;

public class WebDavException extends Exception {

	private static final long serialVersionUID = 1L;

	private final int statusCode;

	public WebDavException(String message, int statusCode) {
		super(message);
		this.statusCode = statusCode;
	}

	public WebDavException(String message, Throwable cause) {
		super(message, cause);
		this.statusCode = -1;
	}

	/** HTTP 状态码；-1 表示这次失败根本没有拿到响应（网络层就挂了）。 */
	public int statusCode() {
		return statusCode;
	}
}
