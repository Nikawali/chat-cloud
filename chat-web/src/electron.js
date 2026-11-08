// 渲染进程侧封装：把 window.electronAPI 包一层，让业务代码在「浏览器」和「Electron」里都能跑。
// 纯网页环境下这些调用会静默忽略，不会报错。

export const isElectron = typeof window !== 'undefined' && !!window.electronAPI

// 桌面通知
export function notify(title, body) {
  if (window.electronAPI?.notify) {
    return window.electronAPI.notify(title, body)
  }
  return Promise.resolve(false)
}

// 运行环境版本信息（Electron/Chromium/Node）
export function getVersions() {
  if (window.electronAPI?.getVersions) {
    return window.electronAPI.getVersions()
  }
  return Promise.resolve(null)
}
