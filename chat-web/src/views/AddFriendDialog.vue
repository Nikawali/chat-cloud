<template>
  <el-dialog v-model="visible" class="add-friend-dialog" title="添加好友" width="400px" append-to-body>
    <!-- 让对方是谁一目了然：加好友最怕加错人 -->
    <div class="target">
      <el-avatar :size="44" :src="assetUrl(target?.avatarUrl)">
        {{ firstChar }}
      </el-avatar>
      <div class="target-text">
        <div class="target-name chat-ellipsis">{{ target?.nickName || '' }}</div>
        <!-- 搜索卡片里没有 username（登录名只在详情里给），所以两边分别兜底 -->
        <div class="target-sub chat-muted">{{ target?.username ? '@' + target.username : `ID ${target?.userId}` }}</div>
      </div>
    </div>

    <el-input
      v-model="message"
      class="message-input"
      type="textarea"
      :rows="3"
      maxlength="100"
      show-word-limit
      placeholder="说一句话，让对方知道你是谁（可以不填）"
    />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">发送申请</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, assetUrl } from '../api'
import { firstCharOf } from '../utils/display'

/**
 * 「添加好友」对话框（加好友页和资料页共用）。
 *
 * <p>用法：{@code <AddFriendDialog v-model="visible" :target="someone" @sent="onSent" />}
 *
 * <p>抽成组件是因为要填"验证消息"这一步在两个页面一模一样，
 * 各写一遍的话（比如以后验证消息要加敏感词过滤）必然改一处忘一处。
 *
 * <p>用 {@code defineModel()} 拿到父组件的 v-model —— 这是 Vue 3.4+ 的写法，
 * 不用再自己写 props + emit('update:modelValue') 那一套样板。
 */
const visible = defineModel({ type: Boolean, default: false })

const props = defineProps({
  /** 要加的人。两种形状都行：搜索卡片（UserCardVO）或资料详情（UserDetailVO） */
  target: { type: Object, default: null },
})

const emit = defineEmits(['sent'])

const message = ref('')
const submitting = ref(false)

/** 每次打开都把上一次的输入清掉：对话框是最容易残留旧状态的地方 */
watch(visible, (open) => {
  if (open) {
    message.value = ''
  }
})

/*
 * 头像首字：规则统一在 utils/display.js（这里传进来的 target 是搜索结果卡片，没有备注字段，
 * 所以 firstCharOf 的结果和以前按 nickName 取首字完全一致）。
 */
const firstChar = computed(() => firstCharOf(props.target))

async function submit() {
  if (!props.target?.userId) {
    return
  }
  submitting.value = true
  try {
    await api.addFriend(props.target.userId, message.value.trim() || null)
    ElMessage.success('申请已发送')
    visible.value = false
    // 让父组件去刷新"我和他的关系"（按钮会变成"已申请，等待验证"）
    emit('sent')
  } catch (e) {
    // 409 是最常见的：已经是好友 / 已经申请过 —— 后端文案已经说清楚了，直接显示
    ElMessage.error(e?.message || '发送失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.target {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 16px;
  margin-bottom: 16px;
  /* 分隔线一律用 --chat-border：原来写死的浅灰在深色模式下会整条消失 */
  border-bottom: 1px solid var(--chat-border);
}

.target-text {
  min-width: 0;
}

.target-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--chat-text);
}

.target-sub {
  /* 字号/颜色交给 .chat-muted，这里只管和名字的距离 */
  margin-top: 2px;
}

.message-input :deep(.el-textarea__inner) {
  font-family: inherit;
}
</style>

<!--
  下面这块【故意不写 scoped】。

  为什么：el-dialog 的内容会被 teleport 到 body 下面，而对话框内部的元素
  （.el-dialog__header/__body/__footer）是 element-plus 自己渲染的，
  身上没有本组件的 data-v-xxx 属性 —— 所以 scoped 样式（包括 :deep()）
  在这里一条都选不中，只能写成全局。

  为什么不会误伤别人：全部挂在 .add-friend-dialog 这个类下面，
  而这个类只出现在本组件的那一个 el-dialog 上。
-->
<style>
.el-dialog.add-friend-dialog {
  /* 对话框自己的内边距就是这个变量在管；
     多写一个 .el-dialog 是为了提高优先级，免得和 EP 自带样式的先后顺序说不准 */
  --el-dialog-padding-primary: 18px;
}

.add-friend-dialog .el-dialog__header {
  /* 默认 16px 时"添加好友"会和下面的人名糊在一起，收一点才像一组 */
  padding-bottom: 10px;
}

.add-friend-dialog .el-dialog__body {
  /* 正文（确认加谁 + 说一句话）跟页面正文同字号，不然像小字附注 */
  font-size: 14px;
  color: var(--chat-text-2);
}

.add-friend-dialog .el-dialog__footer {
  padding-top: 18px;
}

/* 两个按钮一主一次，10px 比默认的 12px 紧凑些但不会粘在一起 */
.add-friend-dialog .el-dialog__footer .el-button + .el-button {
  margin-left: 10px;
}
</style>
