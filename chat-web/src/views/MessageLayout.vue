<template>
  <!--
    消息区的"主从布局"外壳（桌面 IM 的标准结构）：
      宽窗口 → 左边一条固定的会话轨道（340px）+ 右边聊天区铺满剩余宽度；
      窄窗口 → 退回单栏：列表铺满，打开会话时把列表藏起来（就是手机上的行为）。

    ⚠️ 为什么要有这一层：
    会话列表是"一行的两端会被拉开"的界面 —— 把它铺到 950px 宽，名字在最左、
    时间在最右，中间一大片空白，看着像没做完。桌面版的解法从来不是"把整页收窄居中"
    （那会在两侧留出更难看的空白），而是**让它只占一条轨道**，把宽度让给内容。
  -->
  <div class="msg-layout" :class="{ 'msg-layout--chat': chatOpen }">
    <!--
      轨道里直接渲染列表组件（而不是再用一个 router-view）：
      嵌套路由的 router-view 一次只能渲染"当前匹配到的那一个"子路由，
      而这里需要【同时】显示列表和聊天。所以列表写死在这，聊天交给右边的 router-view。

      副作用是列表在切会话时不会被卸载重建 —— 这其实是好事（不再闪一下），
      代价是"读完消息返回列表"时那条会话的未读不会自己变 0（原来靠重新挂载重读）。
      所以下面 watch 了路由，返回时显式让列表重读一次。
    -->
    <aside class="msg-rail">
      <MessageList />
    </aside>

    <section class="msg-pane">
      <!--
        ⚠️ :key 是【必须的】，少它就有"点了另一个会话没反应"的 bug。

        原因：vue-router 在"只有路由参数变了"时（/message/chat/2 → /message/chat/3）
        会**复用同一个组件实例**，onMounted 不会重跑 —— 而聊天页的
        messages / 水位 / conversationId 全是在 onMounted 里加载的，
        于是界面还停在原来那个会话上。

        窄窗口下这个 bug 看不见：返回列表会把聊天页卸载，再进另一个会话就是全新挂载。
        宽窗口双栏里聊天页一直挂着，才暴露出来。

        为什么用 key 强制重挂载，而不是像 UserProfile.vue 那样 watch 参数：
        聊天页的状态多得危险（消息列表、两条水位、待上报的已读、草稿、事件订阅、
        已读上报定时器……），手写"切换时逐项重置"漏掉任何一项，
        轻则旧会话的勾画在新会话上，重则把 A 的已读上报成 B 的。
        重挂载复用的是"卸载/挂载"那两条已经被窄窗口路径验证过的代码，没有中间状态。

        key 用 fullPath：换会话、甚至换查询参数都会强制重挂载，语义最直白。
      -->
      <router-view v-slot="{ Component }">
        <component :is="Component" v-if="chatOpen" :key="route.fullPath" />
      </router-view>

      <!-- 宽窗口下"还没选会话"：右边给一句引导，而不是留一片死白 -->
      <div v-if="!chatOpen" class="pane-empty chat-empty">
        <el-icon class="chat-empty-icon"><ChatDotRound /></el-icon>
        <p class="empty-text">从左边选一个会话开始聊天</p>
      </div>
    </section>
  </div>
</template>

<script setup>
/**
 * 消息区布局（会话列表 + 聊天窗）。
 *
 * <p>为什么用 CSS 媒体查询而不是 JS 监听窗口宽度：布局随窗口变化是【样式】的事，
 * 用 JS 判断就得处理"窗口缩放时重新计算 + 组件重新渲染"，还容易和 CSS 打架；
 * 媒体查询由浏览器直接算，缩放时不会有中间状态。
 *
 * <p>断点选 1024px：低于它时 340px 的轨道 + 聊天区会挤得两边都难看
 * （聊天区只剩 600px 出头，气泡折行会非常频繁）。
 */
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'
import MessageList from './Message.vue'

const route = useRoute()

/** 当前是不是在某个会话里（决定右边显示聊天还是引导语） */
const chatOpen = ref(route.name === 'chat' || route.name === 'group-chat')
watch(() => route.name, (name) => {
  chatOpen.value = name === 'chat' || name === 'group-chat'
})

/*
  这里原来还有一个 watch：从会话返回列表时调 listRef.refresh() 让列表重读一次，
  因为列表常驻左栏不会被重新挂载、"刚读过的那条未读"就没人清了。

  现在不需要了：已读游标推进会在 utils/messageStore.js 里广播，
  而共享的会话列表（utils/conversationStore.js）订阅了那个事件自己会刷新 ——
  不管列表是不是被常驻着。少一个"靠壳去提醒子组件"的耦合。
*/
</script>

<style scoped>
.msg-layout {
  display: flex;
  /* 铺满 .main-body 给的高度；消息页自己管滚动（列表滚它自己的，聊天滚它自己的） */
  height: 100%;
  overflow: hidden;
  background: var(--chat-page-bg);
}

.msg-rail {
  flex-shrink: 0;
  width: var(--chat-rail);
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--chat-surface);
  /* 轨道和右边聊天区之间的分界：一条发丝线就够，不要投影 */
  border-right: 1px solid var(--chat-border);
}

/*
  列表组件（Message.vue）的根节点是 .chat-page，它带着"页面外壳"的内边距。
  在轨道里要换成更小的内边距（340px 宽下 14px 已经是可见的一圈），
  并且让它自己滚动 —— 外层 .main-body 不再为消息页提供滚动。
  :deep() 是必须的：.chat-page 是子组件的根节点，样式要穿透进去。
*/
.msg-rail :deep(.chat-page) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 10px;
}

.msg-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 右边没选会话时的引导语占满整块 */
.pane-empty {
  flex: 1;
}

/*
  ── 窄窗口：退回单栏 ───────────────────────────────────────────
  1024px 以下不值得并排（340px 轨道 + 聊天区会让两边都憋屈），
  改成"列表铺满 / 打开会话时列表让位"，也就是手机上的行为。
*/
@media (max-width: 1023px) {
  .msg-layout {
    /* 单栏下不用 flex 并排，直接用块级叠放，靠下面的 display 切换 */
    display: block;
  }

  .msg-rail {
    width: 100%;
    height: 100%;
    border-right: none;
  }

  .msg-rail :deep(.chat-page) {
    /* 单栏时列表是整页，恢复成正常的页面内边距 */
    padding: var(--chat-page-pad) var(--chat-page-pad-x);
  }

  .msg-pane {
    display: none;
    height: 100%;
  }

  /* 打开了会话：藏列表、露聊天（左边那条返回按钮负责回来） */
  .msg-layout--chat .msg-rail {
    display: none;
  }

  .msg-layout--chat .msg-pane {
    display: flex;
  }
}
</style>
