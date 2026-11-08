<template>
  <div class="app-root">
    <!-- 页面出口：登录页 / 注册页 / 主页面（主页面自己再套一层底部导航） -->
    <router-view />
  </div>
</template>

<script setup>
/**
 * 应用根组件：只负责页面出口。
 *
 * <p>这里原来挂着两样<b>开发期用来验证 Electron 能力</b>的东西（一个"🔔 测试桌面通知"
 * 按钮、一行运行时版本号）。它们不是产品界面 —— 而且是 fixed 定位，会压在底部导航栏上，
 * 所以已经删掉：
 * <ul>
 *   <li>桌面通知现在有正经用途：收到新消息、且窗口没被看着时，由 {@code MainLayout} 真的弹；</li>
 *   <li>版本号要看的话 {@code window.electronAPI.getVersions()} 仍然可用，
 *       只是不再往界面上钉一行。</li>
 * </ul>
 */
</script>

<style scoped>
.app-root {
  height: 100%;
}
</style>
