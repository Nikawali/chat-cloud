<template>
  <footer class="conversation-composer">
    <div class="composer-shell">
      <el-input :model-value="modelValue" type="textarea" :autosize="{minRows:1,maxRows:4}" resize="none"
        :placeholder="placeholder" :disabled="disabled" :maxlength="maxlength"
        @update:model-value="$emit('update:modelValue', $event)" @keydown="keydown" />
      <el-button class="composer-send" type="primary" :disabled="disabled || !modelValue.trim() || sending" :loading="sending" @click="send">{{ sendLabel }}</el-button>
      <el-dropdown trigger="click" placement="top-end" :disabled="disabled || sending || attachmentsDisabled" @command="$emit('attachment', $event)">
        <button class="composer-attachment" type="button" :disabled="disabled || sending || attachmentsDisabled" :title="attachmentHint" aria-label="添加图片或文件"><el-icon><Plus /></el-icon></button>
        <template #dropdown><el-dropdown-menu><el-dropdown-item command="image"><el-icon><Picture /></el-icon>图片</el-dropdown-item><el-dropdown-item command="file"><el-icon><Document /></el-icon>文件</el-dropdown-item></el-dropdown-menu></template>
      </el-dropdown>
    </div>
    <slot />
  </footer>
</template>
<script setup>
import { Plus, Picture, Document } from '@element-plus/icons-vue'
const props = defineProps({
  modelValue: {type:String,default:''}, sending: Boolean, disabled:Boolean, attachmentsDisabled:Boolean,
  placeholder: {type:String,default:'输入消息…（回车发送，Shift+回车换行）'},
  sendLabel: {type:String,default:'发送'}, maxlength: {type:Number,default:undefined},
  attachmentHint: {type:String,default:'发送图片或文件（单个最大 50MB）'},
})
const emit = defineEmits(['update:modelValue', 'send', 'attachment'])
function send() { if (!props.disabled && !props.sending && props.modelValue.trim()) emit('send') }
function keydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault(); send()
}
</script>
<style scoped>
.conversation-composer { flex-shrink:0; padding:10px 12px; background:var(--chat-surface); border-top:1px solid var(--chat-border); position:relative; z-index:1; }
.composer-shell { display:flex; align-items:flex-end; gap:6px; width:100%; max-width:900px; margin:0 auto; padding:6px; border:1px solid var(--chat-border); border-radius:20px; background:var(--chat-surface-2); transition:border-color .16s; }
.composer-shell:focus-within { border-color:var(--chat-brand); background:var(--chat-surface); }
.composer-shell :deep(.el-textarea) { flex:1; min-width:0; }
.composer-shell :deep(.el-textarea__inner) { font-family:inherit; font-size:14px; line-height:1.6; padding:5px 8px; color:var(--chat-text); background:transparent; border:0; border-radius:0; box-shadow:none; }
.composer-shell :deep(.el-textarea__inner:hover), .composer-shell :deep(.el-textarea__inner:focus) { background:transparent; box-shadow:none; }
.composer-send { flex-shrink:0; height:32px; padding:0 16px; border-radius:16px; }
.composer-attachment { display:flex; align-items:center; justify-content:center; flex-shrink:0; width:32px; height:32px; padding:0; border:0; border-radius:50%; background:transparent; color:var(--chat-text-3); font-size:17px; cursor:pointer; }
.composer-attachment:hover:not(:disabled) { background:var(--chat-surface); color:var(--chat-brand); }
.composer-attachment:disabled { opacity:.45; cursor:not-allowed; }
</style>
