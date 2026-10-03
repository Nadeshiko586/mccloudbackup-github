package dev.mccb.core.backup;

import dev.mccb.core.util.HumanSize;
import dev.mccb.core.util.ProgressListener;

/**
 * 把进度记录下来供界面轮询。
 *
 * <p>为什么是"记录 + 轮询"而不是"回调里直接更新界面"：进度回调跑在后台线程上，
 * 而改界面必须回主线程。每读 64 KB 就往主线程丢一个任务，既繁琐又容易把
 * 主线程任务队列塞满。改成后台线程只写字段、界面每帧自己去读，
 * 两个线程之间就只有几个 volatile 字段的读写，简单且不会互相拖慢。
 */
public final class ProgressTracker implements ProgressListener {

	private volatile String stage = "";
	private volatile long done;
	private volatile long total;

	@Override
	public void onStage(String stage) {
		this.stage = stage == null ? "" : stage;

		// 换阶段意味着上一阶段的总量作废，不清掉的话进度条会瞬间跳回去。
		this.done = 0;
		this.total = 0;
	}

	@Override
	public void onProgress(long done, long total) {
		this.done = done;
		this.total = total;
	}

	public String stage() {
		return stage;
	}

	public long done() {
		return done;
	}

	public long total() {
		return total;
	}

	/**
	 * 完成比例，0.0 ~ 1.0。
	 *
	 * @return 总量未知时返回负数——调用方应该显示成"进行中"而不是画一个 0% 的条
	 */
	public double fraction() {
		long size = total;

		if (size <= 0) {
			return -1.0;
		}

		double value = (double) done / (double) size;
		return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
	}

	public void reset() {
		stage = "";
		done = 0;
		total = 0;
	}

	/**
	 * 精简版的一行文字，只保留阶段和百分比。
	 *
	 * <p>给系统提示用：那条提示很窄，带上字节数会把 "9.9 MB" 从中间折断，很难看。
	 * 详细的字节数留给界面里的进度条。
	 */
	public String describeShort() {
		String current = stage.isEmpty() ? "处理中……" : stage;
		double fraction = fraction();

		return fraction < 0 ? current : current + " " + Math.round(fraction * 100) + "%";
	}

	/**
	 * 一行可直接显示的文字，例如 {@code 正在上传  45%   5.2 MB / 11.7 MB}。
	 *
	 * <p>放在这里而不是界面里，是为了让界面进度条和聊天提示用的是同一份措辞，
	 * 不会出现两处说法不一致。
	 */
	public String describe() {
		String current = stage.isEmpty() ? "处理中……" : stage;
		double fraction = fraction();

		if (fraction < 0) {
			// 总量未知。硬写个 0% 比不写更让人焦虑。
			return current;
		}

		int percent = (int) Math.round(fraction * 100);

		if (total > 0) {
			return current + "  " + percent + "%   "
					+ HumanSize.format(done) + " / " + HumanSize.format(total);
		}

		return current + "  " + percent + "%";
	}
}
