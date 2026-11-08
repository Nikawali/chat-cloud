// 全站统一的"提示"外观 —— 只有这一种。
//
// ── 为什么要在这里"劫持" ElMessage ──────────────────────────────
// 各页面调用的是 ElMessage.error / success / warning / info 四个快捷方法（约 36 处）。
// 要统一外观有两条路：
//   ① 把 36 处调用全改成我们自己的一层封装 —— 改一遍容易，以后新加的地方照样会写回
//      ElMessage.success，于是又变回两套样式；
//   ② 在启动时把四个快捷方法包一层，只覆盖"外观参数"（customClass / type / 时长）。
// 选 ②：调用方的语义（"这是一条错误"）保留，但**显示出来永远是同一种样子** ——
// 页面正中、红底白字、一句话。样式本身写在 src/style.css 的 .el-message.chat-toast 里。
//
// ── 为什么连 success 也显示成红的 ─────────────────────────────
// 这是明确要求：全站只保留一种提示。好处是"屏幕上出现红块 = 有事要看一眼"，
// 不用先分辨颜色和图标；代价是"保存成功"也会是红的。真要分档，在这里给
// type 传不同的值、并在 style.css 里按 .chat-toast--ok 之类的类名分开即可。
//
// ⚠️ ElMessageBox（确认框）不受影响：它是需要用户做选择的对话框，不是"提示"。
import { ElMessage } from 'element-plus'

/** 统一提示的类名（样式在 src/style.css 里） */
export const TOAST_CLASS = 'chat-toast'

/** 提示停留时长：够读完一句话，又不至于挡着操作 */
const TOAST_DURATION_MS = 2600

/** 拿一份"原始"的四个快捷方法：必须最先取，晚一步就成了我们自己的包装（递归） */
const originals = {
  error: ElMessage.error,
  success: ElMessage.success,
  warning: ElMessage.warning,
  info: ElMessage.info,
}

let patched = false

/**
 * 屏幕上当前那条提示的文字（null = 没提示或有别处弹出的）。
 *
 * <p>为什么需要它：提示被强制画在页面正中，而 Element Plus 给每条提示的
 * {@code top} 是"上一条的高度 + 间距"算出来的。我们把 top 统一改成居中之后，
 * 同一时刻出现两条<b>不同</b>的提示就会重叠在同一处，变成一团看不清的红块。
 * 所以这里做"只留一条"：文字相同就交给 grouping 合并（不会闪），
 * 文字不同就把旧的全关掉再弹新的 —— 看到的永远是最新那件事。
 */
let currentMessage = null

/**
 * 把四个快捷方法包成同一个外观。只做一次（模块被重复 import 也不会叠加包装）。
 */
export function unifyMessageStyle() {
  if (patched) {
    return
  }
  patched = true

  for (const kind of Object.keys(originals)) {
    const original = originals[kind]
    ElMessage[kind] = (options) => {
      // 允许 ElMessage.error('一句话') 和 ElMessage.error({ message, duration }) 两种写法
      const base = typeof options === 'string' ? { message: options } : { ...(options || {}) }
      if (!base.message) {
        return undefined
      }
      // 只留一条：和屏幕上那条不同才清场（相同的话交给下面的 grouping 合并，避免闪一下）
      if (typeof base.message === 'string' && base.message !== currentMessage) {
        ElMessage.closeAll?.()
        currentMessage = base.message
      }
      return original({
        ...base,
        // 一律用 error 的图标：外观统一，图标也跟着统一
        type: 'error',
        customClass: TOAST_CLASS,
        // 同样的提示连着来（例如连续两条请求都失败）只留一条，不要叠成一摞
        grouping: true,
        duration: base.duration ?? TOAST_DURATION_MS,
      })
    }
  }
}

// 这个模块的唯一目的就是"被 import 时生效"，所以这里直接执行一次。
// 调用方不需要记得先调什么 —— 忘记调用就会退回两种样式，那种"只在某些页面不一样"的 bug 最难发现。
unifyMessageStyle()
