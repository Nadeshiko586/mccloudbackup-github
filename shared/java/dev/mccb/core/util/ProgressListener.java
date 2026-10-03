package dev.mccb.core.util;

/**
 * 进度回调。所有方法都可能从后台线程调用，实现里不要直接碰游戏状态。
 *
 * <p>放在 util 而不是 backup 包里，是因为 {@code webdav}、{@code archive}、
 * {@code backup} 三层都要用它。<b>别把它挪回 backup</b>——那会让最底层的
 * webdav 反过来依赖上层的 backup，是依赖倒置。
 */
public interface ProgressListener {

	ProgressListener NOOP = new ProgressListener() {
		@Override
		public void onStage(String stage) {
		}

		@Override
		public void onProgress(long done, long total) {
		}
	};

	/** 进入某个阶段，例如"正在打包"、"正在上传"。总量会被重置为未知。 */
	void onStage(String stage);

	/** 当前阶段的进度。total 小于等于 0 表示总量未知。 */
	void onProgress(long done, long total);
}
