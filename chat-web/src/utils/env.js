// 运行环境相关的小工具。
//
// 两个判断，语义不同、别混用：
//   · isWindowVisible —— "这个窗口还在显示吗"（切到别的 tab、最小化 → false）。
//     全局同步用它决定"定时补拉要不要跑"（窗口不可见但连接假死时仍然要拉）。
//   · isWindowFocused —— "现在是不是真有人在看这个窗口"（可见 **且** 它是活动窗口）。
//     聊天页用它决定"要不要把消息标成已读"。

/**
 * 窗口现在是不是可见的。
 *
 * <p>没有 {@code document} 时（理论上不会发生）<b>按"可见"处理</b>：
 * 宁可多做一次（多补拉一次），也不要因为拿不到状态就漏掉。
 *
 * @returns {boolean}
 */
export function isWindowVisible() {
  return typeof document === 'undefined' || document.visibilityState === 'visible'
}

/**
 * 窗口现在是不是"真的有人在看"：<b>可见 且 有焦点</b>。
 *
 * <p><b>为什么"已读上报"必须用这个，而不是 {@link isWindowVisible}</b>：
 * 主进程为了让"窗口被别的程序盖住时也能收到推送"，关掉了 Chromium 的遮挡检测
 * （见 {@code electron/main.cjs} 的 {@code disable-features=CalculateNativeWinOcclusion}
 * 和 {@code disable-backgrounding-occluded-windows}）。副作用是：
 * 窗口被盖住、或者用户已经切到别的程序时，{@code visibilityState} 仍然报告 {@code 'visible'}。
 * 只看可见性，就会出现"人根本没在看，消息却被标成已读"——对方那边的勾直接变蓝。
 *
 * <p>{@code document.hasFocus()} 才是"这个窗口现在是活动窗口"的判据。代价是：
 * 打开 DevTools、或者焦点落在别的窗口时不会上报已读，等用户点回聊天窗口
 * （{@code focus} 事件 → {@code onForegroundRead}）会补报一次。
 *
 * @returns {boolean}
 */
export function isWindowFocused() {
  if (typeof document === 'undefined') {
    return true
  }
  if (document.visibilityState !== 'visible') {
    return false
  }
  return typeof document.hasFocus === 'function' ? document.hasFocus() : true
}
