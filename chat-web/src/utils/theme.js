// 「模式选择」—— 浅色 / 深色 / 跟随系统。
//
// ── 为什么单独抽一个模块 ─────────────────────────────────────────
// 主题这件事有三个动作必须一起做对：读偏好、应用到 DOM、跟随系统变化时重算。
// 写在设置页里也能跑，但"启动时应用"这一步在 main.js（页面还没渲染），
// 两边各写一份的话，最典型的 bug 就是**刷新之后闪一下白**（启动时没人应用偏好）。
//
// ── 怎么应用：给 <html> 挂一个 class ───────────────────────────
// Element Plus 的暗色主题认的就是 `html.dark`（它自带一整套暗色 CSS 变量），
// 我们自己的变量（见 style.css 的 :root / html.dark）也挂在同一个类上。
// 所以"换主题"这件事在实现上就一句 classList.toggle —— 不需要重建组件、
// 不需要给每个组件传 prop，切换是瞬时的。
const STORAGE_KEY = 'chat_theme_mode'

/** Element Plus 认的暗色类名（别改，改了暗色组件就不生效了） */
const DARK_CLASS = 'dark'

/** 合法的取值。'system' 是默认值：新用户第一次打开时，跟系统走最不容易突兀 */
const MODES = ['light', 'dark', 'system']

/** 给界面渲染用的选项列表（文案和取值放在一起，避免页面上再写一份） */
export const THEME_MODES = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

/**
 * 「跟随系统」时那个媒体查询的取消函数。
 *
 * <p>必须留句柄：用户在设置里来回切（跟随系统 → 浅色 → 跟随系统），
 * 每次都要把上一个监听摘掉，否则会攒下一堆监听器，而且它们互相打架
 * （切到浅色之后系统一变，旧的监听又把页面改回深色）。
 */
let disposeSystemListener = null

/** 系统当前是不是深色。拿不到 matchMedia（理论上不会）时按浅色处理 */
function systemPrefersDark() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches === true
}

/** 当前偏好：读不到 / 存的值不合法（比如被人手改过）都退回 'system' */
export function getThemeMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return MODES.includes(saved) ? saved : 'system'
  } catch {
    // 隐私模式下 localStorage 可能直接抛异常。读不到偏好不该让页面打不开
    return 'system'
  }
}

/** 这个偏好最终是不是深色 */
function isDark(mode) {
  return mode === 'dark' || (mode === 'system' && systemPrefersDark())
}

/**
 * 把偏好应用到 DOM。幂等，重复调用无害。
 *
 * @param {string} [mode] 不传就用当前存着的偏好
 */
export function applyTheme(mode = getThemeMode()) {
  if (typeof document === 'undefined') {
    return
  }
  document.documentElement.classList.toggle(DARK_CLASS, isDark(mode))
}

/**
 * 换一个模式：存起来 + 立刻生效 + 重新挂（或摘掉）系统监听。
 *
 * <p>为什么"存"和"生效"绑在一个函数里：只存不生效的话，
 * 用户点完还得刷新才看到变化；只生效不存的话，重启又变回去了。
 */
export function setThemeMode(mode) {
  const next = MODES.includes(mode) ? mode : 'system'
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 写不进去只影响"下次启动还记不记得"，本次切换照样生效 —— 不能因为这个让用户点了没反应
  }
  applyTheme(next)
  watchSystemTheme(next)
}

/**
 * 「跟随系统」时才需要监听系统变化；其它模式把监听摘掉。
 *
 * <p>为什么不能一直挂着监听、在里面判断当前模式：可以，但那样每次系统切换都要
 * 读一次 localStorage，而且"用户选了浅色、系统却切到深色"时白白触发一次无意义的计算。
 */
function watchSystemTheme(mode) {
  if (disposeSystemListener) {
    disposeSystemListener()
    disposeSystemListener = null
  }
  if (mode !== 'system' || typeof window === 'undefined'
      || typeof window.matchMedia !== 'function') {
    return
  }
  const query = window.matchMedia('(prefers-color-scheme: dark)')
  const onChange = () => applyTheme('system')
  query.addEventListener('change', onChange)
  disposeSystemListener = () => query.removeEventListener('change', onChange)
}

/**
 * 启动时调一次（main.js 在挂载前调）。
 *
 * <p>⚠️ 这一步不能省：偏好存在 localStorage 里，而 <html> 的 class 不会跟着刷新保留，
 * 所以每次启动都必须重新应用一次 —— 漏了的话，选了深色的人每次刷新都会先看到白屏一闪。
 */
export function initTheme() {
  const mode = getThemeMode()
  applyTheme(mode)
  watchSystemTheme(mode)
}
