<template>
  <span v-if="message.uploadPending" class="msg-file"><el-icon class="msg-file-icon"><Document /></el-icon><span class="msg-file-text"><span class="msg-file-name">{{ fileNameOf(message) }}</span><span class="msg-file-size">{{ fileSizeTextOf(message) }}</span></span></span>
  <template v-else>
              <template v-if="message.messageType === 2">
                <!--
                  附件的三条规则（图片和视频同一套，见下方 v-local-media 的说明）：
                    · 本地有副本 → 滚到这里就自动读本地显示，**一个网络请求都不发**；
                    · 本地没有 → 显示"点击加载图片"，点了才去服务器取（取回来存本地）；
                    · 取失败 → "加载失败，点击重试"。
                  为什么"本地没有"不自动取：附件在服务端是永久保存的，一进会话就把几十张图
                  全下载一遍，流量和等待都白花 —— 用户可能只是扫一眼这个会话就退出去了。
                -->
                <button
                  v-if="!imageSrcOf(message)"
                  v-local-media="message"
                  class="msg-media-fallback"
                  type="button"
                  :disabled="mediaLoading.has(message.key)"
                  :title="mediaPlaceholder(message, '图片')"
                  @click="loadMedia(message)"
                >
                  <el-icon><Picture /></el-icon>
                  <span>{{ mediaPlaceholder(message, '图片') }}</span>
                </button>
                <img
                  v-else
                  class="msg-image"
                  :src="imageSrcOf(message)"
                  alt="图片"
                  @error="onMediaBroken(message, '图片')"
                  @click="openMedia(message)"
                />
              </template>

              <template v-else-if="message.messageType === 3">
                <!--
                  视频也走 messageType=3（服务端本来就把它当"文件"），这里按扩展名/MIME 认出它，
                  换成 <video> 内嵌播放。为什么不再单独加一个"消息类型"：
                  多一个类型要同时改校验白名单、预览文案、未读统计…… 而"是不是视频"完全
                  可以从文件名判断，没必要动那条已经在跑的契约。

                  ⚠️ 千万别把 src 直接指向服务端地址 + preload="metadata"：那样一进会话
                  浏览器就会为每个视频发一次请求（读元数据），"什么都没点却已经在下载"。
                  本地有副本时滚到它附近就自动读本地（v-local-media），只有本地没有才需要点。
                -->
                <template v-if="isVideoMessage(message)">
                  <button
                    v-if="!videoSrcOf(message)"
                    v-local-media="message"
                    class="msg-media-fallback msg-media-fallback--video"
                    type="button"
                    :disabled="mediaLoading.has(message.key)"
                    :title="mediaPlaceholder(message, '视频')"
                    @click="loadMedia(message)"
                  >
                    <el-icon><VideoPlay /></el-icon>
                    <span>{{ mediaPlaceholder(message, '视频') }}</span>
                  </button>
                  <video
                    v-else
                    class="msg-video"
                    :src="videoSrcOf(message)"
                    controls
                    preload="metadata"
                    playsinline
                    @error="onMediaBroken(message, '视频')"
                  />
                </template>

                <a
                  v-else
                  class="msg-file"
                  :title="`${fileNameOf(message)}（点击保存）`"
                  @click.prevent="openAttachment(message)"
                >
                  <el-icon class="msg-file-icon"><Document /></el-icon>
                  <span class="msg-file-text">
                    <span class="msg-file-name">{{ fileNameOf(message) }}</span>
                    <span class="msg-file-size">{{ fileSizeTextOf(message) }}</span>
                  </span>
                </a>
              </template>

              <template v-else>{{ message.content }}</template>

  </template>
</template>
<script setup>
import { Document,Picture,VideoPlay } from '@element-plus/icons-vue'
const props=defineProps({message:{type:Object,required:true},media:{type:Object,required:true}})
const {imageSrcOf,videoSrcOf,fileNameOf,fileSizeTextOf,mediaPlaceholder,loadMedia,onMediaBroken,openMedia,openAttachment,isVideoMessage,mediaLoading,vLocalMedia}=props.media
</script>
<style scoped>
.msg-image {
  display: block;
  /* 上限：宽不超过气泡、高不超过 280px —— 否则一张竖图会把整个会话挤成一屏一图 */
  max-width: 100%;
  max-height: 280px;
  border-radius: var(--chat-radius-bubble);
  /* 图片边缘用一条发丝线，和"表面色"分层（方向 A：不用投影） */
  border: 1px solid var(--chat-border);
  /* 点击是"看原图"，缩放镜头是最直白的暗示 */
  cursor: zoom-in;
  /* 加载中先给块底色，避免图片"突然蹦出来"把布局顶一下 */
  background: var(--chat-surface-2);
}

/*
  内嵌视频播放器。
  尺寸上限和图片同一套逻辑：宽不超过气泡、高不超过 320px（比图片略高一点，
  16:9 的视频在 280px 高时宽度只有 500px，看着太小）。
  黑底是视频播放器的通行做法：加载中/黑边不会露出页面底色，观感更稳。
*/
.msg-video {
  display: block;
  max-width: 100%;
  max-height: 320px;
  border-radius: var(--chat-radius-bubble);
  border: 1px solid var(--chat-border);
  background: #000;
}

/* 图片加载失败时的占位：虚线框 + 一句说明，并且可以点（点了重新取一次） */
.msg-media-fallback {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 140px;
  padding: 14px 16px;
  /* 它是个 <button>：把浏览器默认的按钮外观全部清掉，只留我们自己的样式 */
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  color: var(--chat-text-3);
  background: var(--chat-surface-2);
  border: 1px dashed var(--chat-border);
  border-radius: var(--chat-radius-bubble);
  cursor: pointer;
  transition: border-color var(--chat-dur), color var(--chat-dur);
}

.msg-media-fallback:hover {
  color: var(--chat-brand);
  border-color: var(--chat-brand);
}

/* 视频的占位块给它自己的体量：视频在聊天里比图片占地方，太小的方块会让人以为只是个图标 */
.msg-media-fallback--video {
  min-width: 220px;
  min-height: 96px;
}

/* 正在下载时按钮是 disabled 的：别让它看起来还能点（重复点会重复下载） */
.msg-media-fallback:disabled {
  cursor: progress;
  color: var(--chat-text-3);
  border-color: var(--chat-border);
}

/*
  文件卡片：图标 + 两行文字（文件名 / 大小）。
  为什么不把文件名当正文显示：文件名往往又长又带扩展名，当文本会把气泡撑成
  一条很难扫的横条；做成固定宽度的卡片，一眼就能认出"这是一个待下载的文件"。
*/
.msg-file {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 180px;
  max-width: 280px;
  padding: 10px 12px;
  color: var(--chat-text);
  background: var(--chat-surface);
  border: 1px solid var(--chat-border);
  border-radius: var(--chat-radius-bubble);
  /* <a> 默认带下划线和蓝色，这里要去掉（它是卡片不是链接文字） */
  text-decoration: none;
  /* 点击行为由 openAttachment 接管（先确认本地有内容，再另存为） */
  cursor: pointer;
  transition: background-color var(--chat-dur);
}

.msg-file:hover {
  background: var(--chat-surface-2);
}

.msg-file-icon {
  flex-shrink: 0;
  font-size: 22px;
  color: var(--chat-brand);
}

.msg-file-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.msg-file-name {
  font-size: 13px;
  /* 长文件名走省略号：卡片宽度固定，撑开就会把气泡顶歪 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-file-size {
  font-size: var(--chat-fs-meta);
  color: var(--chat-text-3);
}


</style>
