package dev.mccb.core.webdav;

import dev.mccb.core.util.Log;
import dev.mccb.core.util.ProgressListener;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 手写的 WebDAV 客户端，只依赖 JDK 自带的 {@link HttpClient}。
 *
 * <p>之所以不引第三方 WebDAV 库：这类库大多是老式的 Apache HttpClient 栈，
 * 体积大、依赖多，而且很多已经不维护了。我们这个 mod 需要的只是
 * PROPFIND / MKCOL / PUT / GET / DELETE 五个动词和 Basic 认证，
 * 用 JDK 自带的 HTTP 客户端完全够用，还能让发布的 jar 保持干净。
 */
public final class WebDavClient {

	/**
	 * 网络失败时的最大尝试次数（含第一次）。
	 *
	 * <p>实测中遇到过 {@code java.net.ConnectException} 让整次备份直接失败的情况。
	 * 对备份工具来说，因为一次瞬时抖动就放弃是不可接受的。
	 * 这里重试用到的动词（PROPFIND / MKCOL / PUT / GET / DELETE）都是幂等的，
	 * 重发不会造成副作用。
	 */
	private static final int MAX_ATTEMPTS = 3;

	/** 重试基准等待时间；第 N 次失败后等 N 倍。 */
	private static final long RETRY_DELAY_MS = 1000L;

	/** 明确列出我们要的属性，比不带头部的 allprop 在各家服务器上兼容性更好。 */
	private static final String PROPFIND_BODY =
			"<?xml version=\"1.0\" encoding=\"utf-8\"?>"
					+ "<d:propfind xmlns:d=\"DAV:\">"
					+ "<d:prop><d:resourcetype/><d:getcontentlength/></d:prop>"
					+ "</d:propfind>";

	private final String baseUrl;
	private final String authHeader;
	private final HttpClient client;

	public WebDavClient(String baseUrl, String username, String password,
						Duration timeout, boolean allowInsecureTls) {
		// 末尾的斜杠会让后面拼接路径时出现双斜杠，有些服务器会因此 404，先统一去掉。
		String normalized = baseUrl == null ? "" : baseUrl.trim();

		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		this.baseUrl = normalized;
		this.authHeader = buildAuthHeader(username, password);
		this.client = buildClient(timeout, allowInsecureTls);
	}

	// ------------------------------------------------------------------
	// 公开操作
	// ------------------------------------------------------------------

	/**
	 * 列出目录下的直接子项（Depth: 1）。目录本身不会包含在结果里。
	 *
	 * @throws WebDavException 目录不存在（404）或其它协议错误
	 */
	public List<RemoteEntry> list(String remoteDir) throws WebDavException {
		HttpRequest request = baseRequest(resolve(remoteDir))
				.header("Depth", "1")
				.header("Content-Type", "application/xml; charset=utf-8")
				.method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY, StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response = sendString(request);
		int code = response.statusCode();

		if (code == 404) {
			throw new WebDavException("云端目录不存在：" + remoteDir, code);
		}

		if (code != 207 && code != 200) {
			throw new WebDavException("列出目录失败 " + remoteDir + "（HTTP " + code + "）", code);
		}

		String self = lastSegment(remoteDir);
		List<RemoteEntry> entries = new ArrayList<>();

		for (RemoteEntry entry : parseMultiStatus(response.body())) {
			// Depth:1 的结果里会包含目录自己，按名字排除掉。
			if (entry.name().equals(self)) {
				continue;
			}

			entries.add(entry);
		}

		return entries;
	}

	/** 递归创建目录。已存在的层级会被跳过。 */
	public void ensureDirectory(String remoteDir) throws WebDavException {
		StringBuilder current = new StringBuilder();

		for (String segment : remoteDir.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}

			if (current.length() > 0) {
				current.append('/');
			}

			current.append(segment);
			mkcol(current.toString());
		}
	}

	/** 上传一个本地文件。会流式发送，不会把整个文件读进内存。 */
	public void put(String remotePath, Path localFile, ProgressListener progress) throws WebDavException {
		long size;

		try {
			// 顺便当存在性检查用：文件不存在会在这里就抛，不用等到发送阶段。
			size = Files.size(localFile);
		} catch (IOException e) {
			throw new WebDavException("无法读取要上传的文件：" + localFile, e);
		}

		progress.onStage("正在上传");
		progress.onProgress(0, size);

		HttpRequest request = baseRequest(resolve(remotePath))
				.header("Content-Type", "application/octet-stream")
				.PUT(progressPublisher(localFile, size, progress))
				.build();

		HttpResponse<String> response = sendString(request);
		int code = response.statusCode();

		if (code != 200 && code != 201 && code != 204) {
			throw new WebDavException("上传失败 " + remotePath + "（HTTP " + code + "）", code);
		}

		progress.onProgress(size, size);
	}

	/**
	 * 构造一个会汇报进度的请求体。
	 *
	 * <p>这里有个坑：{@code BodyPublishers.ofInputStream} 自己不知道流的长度，
	 * HttpClient 就会改用 chunked 传输。而不少 WebDAV 服务器（坚果云就是）
	 * <b>不接受 chunked 上传</b>，会直接返回 411。所以必须用 fromPublisher
	 * 把长度补回去。
	 */
	private static HttpRequest.BodyPublisher progressPublisher(Path file, long size, ProgressListener progress) {
		HttpRequest.BodyPublisher source = HttpRequest.BodyPublishers.ofInputStream(() -> {
			try {
				return new CountingInputStream(Files.newInputStream(file), size, progress);
			} catch (IOException e) {
				// Supplier 的签名不允许抛受检异常，包一层。
				throw new UncheckedIOException(e);
			}
		});

		return HttpRequest.BodyPublishers.fromPublisher(source, size);
	}

	/** 下载到本地文件。调用方需要保证父目录已存在。 */
	public void get(String remotePath, Path localFile, ProgressListener progress) throws WebDavException {
		HttpRequest request = baseRequest(resolve(remotePath)).GET().build();

		for (int attempt = 1; ; attempt++) {
			try {
				downloadOnce(request, remotePath, localFile, progress);
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				deleteQuietly(localFile);
				throw new WebDavException("下载被中断 " + remotePath, e);
			} catch (IOException e) {
				// 半截文件绝不能留下，否则会被上层当成"下载成功"，重试前也必须先删掉。
				deleteQuietly(localFile);

				if (attempt >= MAX_ATTEMPTS) {
					throw new WebDavException("下载失败 " + remotePath, e);
				}

				Log.warn("第 " + attempt + " 次下载失败，稍后重试：" + remotePath + " —— " + e);
				sleepBeforeRetry(attempt, remotePath);
			}
		}
	}

	/** 真正下载一次。失败就抛出去，由 {@link #get} 决定要不要重试。 */
	private void downloadOnce(HttpRequest request, String remotePath, Path localFile, ProgressListener progress)
			throws IOException, InterruptedException, WebDavException {
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		int code = response.statusCode();

		if (code != 200) {
			// 失败时也必须把响应体关掉，否则连接不会归还到连接池，泄漏下去会拖垮后续请求。
			response.body().close();

			// HTTP 错误码（404、401 之类）不是网络抖动，重试没有意义，直接往上抛。
			throw new WebDavException("下载失败 " + remotePath + "（HTTP " + code + "）", code);
		}

		long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

		progress.onStage("正在下载");
		progress.onProgress(0, total > 0 ? total : 0);

		try (InputStream in = response.body();
			 OutputStream out = Files.newOutputStream(localFile)) {
			byte[] buffer = new byte[64 * 1024];
			long done = 0;
			long lastReport = 0;
			int read;

			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				done += read;

				// 每 64 KB 报一次就够了。每读一小块就写一次 volatile，
				// 在几十 MB 的存档上会变成几万次无谓的跨线程写。
				if (done - lastReport >= 64 * 1024 || (total > 0 && done >= total)) {
					lastReport = done;
					progress.onProgress(done, total);
				}
			}

			progress.onProgress(done, total > 0 ? total : done);
		}
	}

	/** 删除一个文件。目标不存在视为成功。 */
	public void delete(String remotePath) throws WebDavException {
		HttpRequest request = baseRequest(resolve(remotePath)).DELETE().build();

		HttpResponse<String> response = sendString(request);
		int code = response.statusCode();

		if (code != 200 && code != 204 && code != 404) {
			throw new WebDavException("删除失败 " + remotePath + "（HTTP " + code + "）", code);
		}
	}

	/** 判断某个路径是否存在。 */
	public boolean exists(String remotePath) throws WebDavException {
		HttpRequest request = baseRequest(resolve(remotePath))
				.header("Depth", "0")
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = sendString(request);
		int code = response.statusCode();

		if (code == 404) {
			return false;
		}

		if (code == 207 || code == 200) {
			return true;
		}

		throw new WebDavException("探测路径失败 " + remotePath + "（HTTP " + code + "）", code);
	}

	/**
	 * 连通性自检：依次尝试列目录、建目录、再列一次。
	 *
	 * @return 给玩家看的一行结果描述
	 */
	public String testConnection(String remoteDir) throws WebDavException {
		try {
			list(remoteDir);
			return "连接成功，目录已存在：" + remoteDir;
		} catch (WebDavException e) {
			if (e.statusCode() != 404) {
				throw e;
			}
		}

		ensureDirectory(remoteDir);

		if (!exists(remoteDir)) {
			throw new WebDavException("目录创建后仍无法访问，请检查账号是否有写权限：" + remoteDir, -1);
		}

		return "连接成功，已创建目录：" + remoteDir;
	}

	// ------------------------------------------------------------------
	// 内部实现
	// ------------------------------------------------------------------

	private void mkcol(String remotePath) throws WebDavException {
		HttpRequest request = baseRequest(resolve(remotePath))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = sendString(request);
		int code = response.statusCode();

		// 201 = 创建成功。
		// 405 / 301 = 目录已存在。各家 WebDAV 服务器对"目录已存在"的返回码
		// 并不统一（坚果云返回 405，有些返回 301），这里都当作正常情况放过。
		if (code == 201 || code == 200 || code == 405 || code == 301) {
			return;
		}

		throw new WebDavException("创建目录失败 " + remotePath + "（HTTP " + code + "）", code);
	}

	private HttpRequest.Builder baseRequest(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

		if (authHeader != null) {
			builder.header("Authorization", authHeader);
		}

		return builder;
	}

	private HttpResponse<String> sendString(HttpRequest request) throws WebDavException {
		for (int attempt = 1; ; attempt++) {
			try {
				return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (UncheckedIOException e) {
				// 上传途中读本地文件失败（见 progressPublisher 里的包装）。
				// 本地文件的问题重试多少次都一样。
				throw new WebDavException("读取要上传的文件时出错：" + request.uri(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new WebDavException("请求被中断：" + request.method() + " " + request.uri(), e);
			} catch (IOException e) {
				if (attempt >= MAX_ATTEMPTS) {
					Log.warn("请求连续失败 " + MAX_ATTEMPTS + " 次，放弃：" + request.method() + " " + request.uri());
					throw new WebDavException("网络错误：" + request.method() + " " + request.uri(), e);
				}

				Log.warn("第 " + attempt + " 次请求失败，稍后重试：" + request.method() + " " + request.uri() + " —— " + e);
				sleepBeforeRetry(attempt, request.uri().toString());
			}
		}
	}

	/** 重试前的退避等待。被中断时抛异常，让调用方立刻退出而不是继续重试。 */
	private static void sleepBeforeRetry(int attempt, String what) throws WebDavException {
		try {
			Thread.sleep(RETRY_DELAY_MS * attempt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WebDavException("重试等待被中断：" + what, e);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 清理临时文件失败无所谓，下次会覆盖。
		}
	}

	/**
	 * 会边读边汇报进度的输入流，供上传使用。
	 */
	private static final class CountingInputStream extends FilterInputStream {

		/** 每读这么多字节才汇报一次，避免为每一小块都去写一次 volatile 字段。 */
		private static final long REPORT_INTERVAL = 64 * 1024;

		private final long total;
		private final ProgressListener progress;

		private long count;
		private long lastReport;

		CountingInputStream(InputStream delegate, long total, ProgressListener progress) {
			super(delegate);
			this.total = total;
			this.progress = progress;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();

			if (value >= 0) {
				count++;
				maybeReport();
			}

			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = super.read(buffer, offset, length);

			if (read > 0) {
				count += read;
				maybeReport();
			}

			return read;
		}

		private void maybeReport() {
			if (count - lastReport >= REPORT_INTERVAL || count >= total) {
				lastReport = count;
				progress.onProgress(count, total);
			}
		}
	}

	/**
	 * 把远端相对路径拼到 baseUrl 上，逐段做百分号编码。
	 */
	private URI resolve(String remotePath) throws WebDavException {
		StringBuilder builder = new StringBuilder(baseUrl);

		for (String segment : remotePath.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}

			builder.append('/').append(encodeSegment(segment));
		}

		String raw = builder.toString();

		try {
			return URI.create(raw);
		} catch (IllegalArgumentException e) {
			// URI.create 抛的是非受检异常，不包起来的话会直接穿透所有
			// catch (WebDavException)，把后台线程打挂。玩家在配置里
			// 贴一个带空格或中文的地址就会触发。
			throw new WebDavException("WebDAV 地址不是合法的 URL：" + raw, e);
		}
	}

	private static String encodeSegment(String segment) {
		// URLEncoder 是为表单编码设计的，会把空格编成 '+'。但在路径里 '+' 是字面量，
		// 空格必须编码成 %20，否则服务器会去找一个名字里带加号的文件。
		return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String lastSegment(String remotePath) {
		String trimmed = remotePath;

		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		int slash = trimmed.lastIndexOf('/');
		return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
	}

	/** 从 href 里取出最后一段并解码成普通文件名。 */
	private static String decodeLastSegment(String href) {
		String trimmed = href;

		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		int slash = trimmed.lastIndexOf('/');
		String raw = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;

		// 同样地，先把字面量 '+' 转义掉再解码，否则它会被当成空格。
		return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	private static String buildAuthHeader(String username, String password) {
		if (username == null || username.isEmpty()) {
			return null;
		}

		String raw = username + ":" + (password == null ? "" : password);
		return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static HttpClient buildClient(Duration timeout, boolean allowInsecureTls) {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NORMAL);

		if (allowInsecureTls) {
			Log.warn("已启用「允许不安全 TLS」：将不校验服务器证书，仅在你明确知道自己在做什么时使用。");

			try {
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
				builder.sslContext(context);

				// 光换 TrustManager 还不够，JDK 还会单独做主机名校验，这里一并关掉。
				SSLParameters parameters = new SSLParameters();
				parameters.setEndpointIdentificationAlgorithm(null);
				builder.sslParameters(parameters);
			} catch (Exception e) {
				Log.warn("关闭 TLS 校验失败，将继续使用默认校验", e);
			}
		}

		return builder.build();
	}

	private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	};

	/**
	 * 解析 207 Multi-Status 响应。
	 *
	 * <p>按 local name 匹配而不是写死命名空间前缀：各家服务器的前缀五花八门
	 * （{@code D:}、{@code d:}、{@code lp1:}……），写死前缀必然翻车。
	 */
	private static List<RemoteEntry> parseMultiStatus(String xml) throws WebDavException {
		Document document = parseXml(xml);
		NodeList responses = document.getElementsByTagNameNS("*", "response");

		List<RemoteEntry> entries = new ArrayList<>();

		for (int i = 0; i < responses.getLength(); i++) {
			Node node = responses.item(i);

			if (!(node instanceof Element)) {
				continue;
			}

			Element response = (Element) node;
			String href = textOfFirstChild(response, "href");

			if (href == null || href.isEmpty()) {
				continue;
			}

			boolean collection = firstDescendant(response, "collection") != null;
			String sizeText = textOfFirstDescendant(response, "getcontentlength");
			long size = parseLong(sizeText);

			entries.add(new RemoteEntry(decodeLastSegment(href), size, collection));
		}

		return entries;
	}

	private static Document parseXml(String xml) throws WebDavException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);

			// 解析的是远端返回的内容，属于不可信输入，必须关掉外部实体，
			// 否则会中 XXE（XML 外部实体注入）。
			hardening(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
			hardening(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
			hardening(factory, "http://xml.org/sax/features/external-general-entities", false);
			hardening(factory, "http://xml.org/sax/features/external-parameter-entities", false);
			factory.setExpandEntityReferences(false);

			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new WebDavException("无法解析服务器返回的 XML（可能不是标准的 WebDAV 服务）", e);
		}
	}

	/** 不同 XML 实现支持的特性集不一样，单独设置失败不应该让整个解析挂掉。 */
	private static void hardening(DocumentBuilderFactory factory, String feature, boolean value) {
		try {
			factory.setFeature(feature, value);
		} catch (Exception e) {
			Log.debug("XML 解析器不支持特性 " + feature + "，已跳过");
		}
	}

	private static String textOfFirstChild(Element parent, String localName) {
		NodeList children = parent.getChildNodes();

		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);

			if (child instanceof Element && localName.equals(localName(child))) {
				return child.getTextContent();
			}
		}

		return null;
	}

	private static String textOfFirstDescendant(Element parent, String localName) {
		Node found = firstDescendant(parent, localName);
		return found == null ? null : found.getTextContent();
	}

	private static Node firstDescendant(Element parent, String localName) {
		NodeList nodes = parent.getElementsByTagNameNS("*", localName);
		return nodes.getLength() > 0 ? nodes.item(0) : null;
	}

	private static String localName(Node node) {
		String local = node.getLocalName();
		return local != null ? local : node.getNodeName();
	}

	private static long parseLong(String text) {
		if (text == null) {
			return 0L;
		}

		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException e) {
			return 0L;
		}
	}
}
