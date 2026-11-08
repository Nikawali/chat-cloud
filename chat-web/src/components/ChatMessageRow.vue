<template>
  <div class="msg" :class="mine ? 'msg--mine' : 'msg--theirs'">
    <el-avatar class="msg-avatar" :size="36" :src="avatarUrl || undefined">{{ firstChar(avatarName) }}</el-avatar>
    <div class="msg-bubble-wrap">
      <span v-if="senderName" class="msg-sender">{{ senderName }}</span>
      <div class="msg-bubble" :class="{ 'msg-bubble--media': media }"><slot /></div>
      <div v-if="mine" class="msg-status" :class="['msg-status--' + status, { 'msg-status--retryable': status === 'failed' && retryable }]"
        :title="status === 'failed' && retryable ? '点击重发' : (error || '')" @click="status === 'failed' && retryable && $emit('retry')">{{ statusText }}</div>
    </div>
  </div>
</template>
<script setup>
import { firstChar } from '../utils/display'
defineProps({ mine:Boolean, avatarUrl:String, avatarName:String, senderName:String, media:Boolean,
  status:String, statusText:String, error:String, retryable:{type:Boolean,default:true} })
defineEmits(['retry'])
</script>
<style scoped>
.msg-sender { font-size:var(--chat-fs-meta); color:var(--chat-text-3); }
.msg {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

/* 我发的：整行反转 —— 头像自动跑到右边，气泡贴右边 */
.msg--mine {
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 500;
  /* 头像用"表面色 + 描边"而不是写死的灰蓝：深色模式下才不会突然亮一块 */
  color: var(--chat-text-2);
  background: var(--chat-surface);
  border: 1px solid var(--chat-border);
}

.msg-bubble-wrap {
  display: flex;
  flex-direction: column;
  /* 长单词/长链接会把气泡顶宽，不写 min-width:0 时 max-width 形同虚设 */
  min-width: 0;
  max-width: 68%;
  /* 气泡和它下面的状态文字之间用 gap，两边的表现才一致 */
  gap: 4px;
}

/* 我发的消息：状态文字靠右，贴着气泡下方 */
.msg--mine .msg-bubble-wrap {
  align-items: flex-end;
}

.msg-bubble {
  padding: 9px 13px;
  /* 方向 A 的字号整体小半档：13.5px 比 14px 更"工具感"，也让一屏能多放两行 */
  font-size: 13.5px;
  line-height: 1.5;
  border-radius: var(--chat-radius-bubble);
  /* 透明描边占位：这样两种气泡的内边距一致，加上真实的边框也不会让文字位置跳动 */
  border: 1px solid transparent;
  /* 保留原文换行，同时不让长串英文/链接溢出容器 */
  white-space: pre-wrap;
  word-break: break-word;
}

/*
  靠着"尾巴"那一侧的角给小圆角（4px），另一侧用大圆角。
  这是气泡唯一能一眼看出"谁在说话"的【形状】线索 —— 只靠颜色的话，
  色弱用户和某些深色配色下两种气泡会长得很像。
*/
.msg--theirs .msg-bubble {
  color: var(--chat-text);
  background: var(--chat-surface);
  border-color: var(--chat-border);
  border-radius: var(--chat-radius-tail) var(--chat-radius-bubble)
                 var(--chat-radius-bubble) var(--chat-radius-bubble);
}

.msg--mine .msg-bubble {
  /* 主色底上的文字色走 --chat-brand-fg，而不是写白色：
     深色主题里主色是"亮靛蓝"，配白字对比度不够 —— 那份主题下这个变量是深色字 */
  color: var(--chat-brand-fg);
  background: var(--chat-brand);
  border-radius: var(--chat-radius-bubble) var(--chat-radius-tail)
                 var(--chat-radius-bubble) var(--chat-radius-bubble);
}

/*
  图片 / 文件气泡：去掉文字气泡的留白和底色 —— 否则图片外面会多一圈主色边，
  图片自己的圆角就白设了。
  ⚠️ 选择器带上 .msg--mine / .msg--theirs：那两个方向的气泡规则优先级更高
  （0,2,0），这里必须同级、并且写在后面才盖得住。
*/
.msg--mine .msg-bubble--media,
.msg--theirs .msg-bubble--media {
  padding: 0;
  background: transparent;
  border-color: transparent;
  /* 媒体内容不需要"保留换行"那套 */
  white-space: normal;
}

.msg-status {
  font-size: var(--chat-fs-meta);
  line-height: 1.4;
  color: var(--chat-text-3);
  /* 状态文字不参与选中：复制一段聊天记录时不该把"已读"一起带走 */
  user-select: none;
}

.msg-status--read {
  color: var(--chat-brand);
}

.msg-status--failed {
  color: var(--chat-danger);
}

/* 失败的状态文字是可以点的（点了重发），给它一点"可点"的暗示 */
.msg-status--retryable {
  cursor: pointer;
  text-decoration: underline dotted;
  transition: color var(--chat-dur);
}

.msg-status--retryable:hover {
  /* 悬停只压一点透明度：换成另一个色号会让人误以为状态变了 */
  opacity: 0.75;
}



</style>
