<template>
  <header class="conversation-header">
    <button class="header-action back-action" type="button" aria-label="返回会话列表" @click="$emit('back')"><el-icon><ArrowLeft /></el-icon></button>
    <div class="conversation-heading"><strong :title="title">{{ title }}</strong><span v-if="subtitle">{{ subtitle }}</span></div>
    <el-dropdown trigger="click" placement="bottom-end" popper-class="chat-more-menu" @command="$emit('command', $event)">
      <button class="header-action more-action" type="button" aria-label="更多聊天选项" title="更多"><el-icon><MoreFilled /></el-icon></button>
      <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="item in items" :key="item.command" :command="item.command">{{ item.label }}</el-dropdown-item></el-dropdown-menu></template>
    </el-dropdown>
  </header>
</template>
<script setup>
import { ArrowLeft, MoreFilled } from '@element-plus/icons-vue'
defineProps({ title: {type:String,default:''}, subtitle: {type:String,default:''}, items: {type:Array,default:()=>[]} })
defineEmits(['back', 'command'])
</script>
<style scoped>
.conversation-header { display:flex; align-items:center; flex-shrink:0; min-height:56px; padding:8px 12px; gap:8px; background:var(--chat-surface); border-bottom:1px solid var(--chat-border); position:relative; z-index:2; }
.conversation-heading { flex:1; min-width:0; display:flex; align-items:center; flex-direction:column; gap:4px; }
.conversation-heading strong { display:block; max-width:100%; font-size:15px; font-weight:600; color:var(--chat-text); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.conversation-heading span { color:var(--chat-text-3); font-size:11px; }
.header-action { display:flex; align-items:center; justify-content:center; width:34px; height:34px; padding:0; flex-shrink:0; background:transparent; border:0; border-radius:8px; color:var(--chat-text-2); font-size:19px; cursor:pointer; }
.header-action:hover { background:var(--chat-surface-2); color:var(--chat-brand); }
.header-action:focus-visible { outline:2px solid var(--chat-brand); outline-offset:2px; }
@media(min-width:1024px) { .back-action { visibility:hidden; } }
</style>
