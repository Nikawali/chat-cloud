<template>
  <div class="chat-page">
    <!--
      页面标题。
      以前这一页进门就是搜索框，没有任何"这是哪一页"的文字；
      底部导航虽然高亮着，但一屏内容多的时候容易被忽略，补一个大标题最省事。
    -->
    <div class="contacts-heading"><div><h2 class="chat-title">联系人</h2><p>与你的好友和群聊保持联系</p></div><span class="contacts-label">通讯录</span></div>
    <div class="contacts-tabs" role="group" aria-label="联系人分类">
      <button type="button" :class="{selected: contactsTab === 'friends'}" :aria-pressed="contactsTab === 'friends'" @click="contactsTab = 'friends'">
        <el-icon><User /></el-icon>好友<span class="section-count">{{ friendList.length }}</span>
      </button>
      <button type="button" :class="{selected: contactsTab === 'groups'}" :aria-pressed="contactsTab === 'groups'" @click="contactsTab = 'groups'">
        <el-icon><ChatDotRound /></el-icon>群聊<span v-if="pendingGroupInvitationCount" class="request-count">{{ pendingGroupInvitationCount > 99 ? '99+' : pendingGroupInvitationCount }}</span>
      </button>
    </div>
    <GroupContacts v-if="contactsTab === 'groups'" />
    <template v-else>
    <button v-if="pendingGroupInvitationCount > 0" class="invitation-entry" type="button" @click="goGroupInvitations">
      <span class="invitation-entry-icon"><el-icon><Message /></el-icon></span>
      <span class="invitation-entry-copy"><strong>群聊邀请</strong><small>有 {{ pendingGroupInvitationCount }} 条邀请等你处理</small></span>
      <span class="request-count">{{ pendingGroupInvitationCount > 99 ? '99+' : pendingGroupInvitationCount }}</span>
      <el-icon><ArrowRight /></el-icon>
    </button>

    <!--
      顶部搜索框。
      它只过滤【已经加载出来的好友】，不发请求：
        · 好友数量是几十到几百这个量级，本地过滤比发请求快得多，也不会打字抖动
        · 后端现在也没有"搜好友"这个接口（只有按手机号精确搜人，那是"加好友"用的）
      搜索是"在通讯录里找"，和"加好友"是两件事，别混在一起。
    -->
    <div class="search-bar">
      <el-input v-model="keyword" placeholder="搜索好友（备注 / 昵称）" clearable>
        <template #prefix>
          <!--
            放大镜换成组件图标（以前用 emoji）：
            emoji 的字形和颜色由操作系统决定，同一个人像在不同机器上长得不一样，
            而线条图标用的是文字色变量，深浅色模式下都能跟着变。
          -->
          <el-icon class="search-icon"><Search /></el-icon>
        </template>
      </el-input>
    </div>

    <!--
      【新的朋友】入口：别人申请加我的。右边红点是待处理条数 ——
      这个入口很关键：没有它，别人发来的申请就永远没人能点同意。
    -->
    <div v-if="!loading && !keyword.trim()" class="add-entry" @click="goRequests">
      <el-icon class="add-icon"><User /></el-icon>
      <span class="add-text">新的朋友</span>
      <span v-if="pendingCount > 0" class="badge">{{ pendingCount }}</span>
      <el-icon class="add-arrow"><ArrowRight /></el-icon>
    </div>

    <!--
      【添加好友】入口，放在搜索框下面。
      只在"浏览全部"时显示：正在搜索的人是在找已有好友，这时候再塞一行"添加好友"会干扰结果。
      一个入口 + 点进去再分（加好友 / 以后加群），而不是在首页平铺多个入口 ——
      以后还要加"扫一扫 / 我的二维码"，平铺会把顶部挤满。
    -->
    <div v-if="!loading && !keyword.trim()" class="add-entry" @click="goAdd">
      <el-icon class="add-icon"><Plus /></el-icon>
      <span class="add-text">添加好友</span>
      <el-icon class="add-arrow"><ArrowRight /></el-icon>
    </div>

    <!-- 加载中：用骨架屏，别用转圈 —— 骨架屏能让"马上要出现列表"这件事更直观 -->
    <div v-if="loading" class="state-box">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 一个好友都没有（真的没好友，不是搜索没结果） -->
    <!--
      空态用共享的 .chat-empty（居中和留白在共享样式里定好了）。
      这里【不再套 el-empty】：它自带一张灰色插图，和上面的 .chat-empty-icon 是两套视觉，
      叠在一起会出现"两个图标"。文案保持原样，只是换了个摆放方式。
    -->
    <div v-else-if="friendList.length === 0" class="chat-empty state-box">
      <el-icon class="chat-empty-icon"><User /></el-icon>
      <p class="empty-text">还没有好友</p>
    </div>

    <!-- 搜索中但没匹配上 -->
    <div v-else-if="keyword.trim() && rows.length === 0" class="chat-empty state-box">
      <el-icon class="chat-empty-icon"><Search /></el-icon>
      <p class="empty-text">没有匹配的好友</p>
    </div>

    <template v-else>
      <!-- 只在"浏览全部"时显示条数；搜索时显示它反而碍眼 -->
      <div v-if="!keyword.trim()" class="list-meta">共 {{ friendList.length }} 位好友</div>

      <!--
        列表只循环一次。
        分组标题和好友条目在 rows 里被"拍平"成同一种行（type 区分），
        所以不用写两套一模一样的条目模板（那是最容易改一处忘一处的地方）。
      -->
      <div class="friend-list chat-card">
        <template v-for="row in rows" :key="row.key">
          <div v-if="row.type === 'header'" class="group-title">{{ row.initial }}</div>
          <div v-else class="friend-item" @click="openFriend(row.friend)">
            <el-avatar class="friend-avatar" :size="40" :src="assetUrl(row.friend.avatarUrl)">
              {{ firstCharOf(row.friend) }}
            </el-avatar>

            <div class="friend-text">
              <div class="friend-name chat-ellipsis">{{ displayName(row.friend) }}</div>
              <!-- 有备注时，把真名/昵称作为副标题显示 —— 否则"小张"是谁过两天自己都忘了 -->
              <div v-if="row.friend.remark" class="friend-sub chat-ellipsis">{{ realName(row.friend) }}</div>
            </div>

            <!-- 右边这个小箭头是"能点进去"的视觉提示（真实聊天软件里点一行就是看资料）。
                 用组件图标代替原来的 › 字符：字符箭头的大小和基线跟着字体走，
                 和左边图标不是一个"视觉重量"。 -->
            <el-icon class="friend-arrow"><ArrowRight /></el-icon>
          </div>
        </template>
      </div>
    </template>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
// 顶部/行末的图标：以前用 emoji 和「›」字符顶替，字形由系统决定，
// 换成图标组件后大小统一，也能跟着颜色变量在深色模式下自动变。
import { Search, Plus, User, ArrowRight, ChatDotRound, Message } from '@element-plus/icons-vue'
import { assetUrl } from '../api'
import { friends, identityReady } from '../utils/accountData'
import { displayName, firstCharOf } from '../utils/display'
import GroupContacts from '../components/GroupContacts.vue'
import { pendingFriendRequestCount, pendingGroupInvitationCount } from '../utils/requestReminderStore'

/**
 * 联系人页。
 *
 * <p>上面搜索框，下面按首字母分组的全部好友 —— 数据来自账号级缓存
 * （{@code utils/accountData.js}，登录时拉一次，加/删好友后刷新）。
 * 好友区读取账号缓存；切换群聊区时由GroupContacts加载群列表和待处理邀请。
 */
const router = useRouter()
const route = useRoute()
const contactsTab = computed({
  get: () => route.query.section === 'groups' ? 'groups' : 'friends',
  set: section => router.replace({ name: 'contacts', query: section === 'groups' ? { section: 'groups' } : {} }),
})
function goGroupInvitations() {
  router.push({ name: 'contacts', query: { section: 'groups', groupTab: 'invitations' } })
}

const keyword = ref('')

/** 好友数组（账号缓存里是 map，这里摊平成数组方便排序/分组） */
const friendList = computed(() => [...friends.value.values()])

/** 骨架屏：账号缓存还没加载过时显示，避免先闪一下"还没有好友" */
const loading = computed(() => !identityReady.value)

/** 待处理的好友申请条数（"新的朋友"右边那个红点） */
const pendingCount = pendingFriendRequestCount

/** 进"添加好友"页。用路由 name 而不是路径，以后改路径不用动这里 */
function goAdd() {
  router.push({ name: 'contacts-add' })
}

/** 进"新的朋友"页 */
function goRequests() {
  router.push({ name: 'contacts-requests' })
}

/**
 * 副标题：有备注时把对方的昵称显示出来，免得"小张"过两天自己都忘了是谁
 */
function realName(friend) {
  return friend.nickName || ''
}

/*
 * 显示名（备注 > 昵称）和头像首字以前在这里各写过一份，现在统一用 utils/display.js
 * （模板里的首字调用也跟着改成了 firstCharOf）。下面的 initialOf / 排序仍然用共享的 displayName。
 */

/**
 * 分组 / 排序用的首字母。
 *
 * <p><b>为什么优先用后端给的 {@code initial}？</b> 中文要算出拼音首字母（"王五" → W），
 * 前端得有拼音表：要么引 pinyin-pro 这类库（多一个依赖），要么自己维护一张不全的表。
 * 而后端算更合适：算一次可以存进表里当排序列，多音字（"重庆" chóng vs zhòng）
 * 也只有一处实现。
 *
 * <p><b>但后端现在【不返回】initial</b> —— 那要引一个 Java 拼音库，先不装。
 * 所以实际走的是下面的兜底：英文名取首字母，中文统一落到 {@code #} 组。
 * 注意<b>排序仍然是对的</b>（见下面的 Intl.Collator，中文按拼音排），
 * 只是分组标题粗一点 —— 真要精确分组时再加库、加列。
 */
function initialOf(friend) {
  if (friend.initial) {
    return String(friend.initial).trim().charAt(0).toUpperCase()
  }
  const ch = displayName(friend).trim().charAt(0)
  return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : '#'
}

/**
 * 中文按拼音排序。
 *
 * <p>{@code Intl.Collator('zh-Hans-CN')} 是浏览器<b>自带</b>的中文排序规则（按拼音），
 * 不用引任何库。这也说明"排序"和"取首字母"是两件事：
 * 排序浏览器能帮我们做，取首字母不行。
 */
const collator = new Intl.Collator('zh-Hans-CN', { numeric: true })

const sortedFriends = computed(() => {
  // 先复制再排：不能直接 sort，那会就地改掉响应式数组
  return [...friendList.value].sort((a, b) => {
    const ia = initialOf(a)
    const ib = initialOf(b)
    if (ia !== ib) {
      // '#' 永远排在最后（它不是字母）
      if (ia === '#') return 1
      if (ib === '#') return -1
      return ia < ib ? -1 : 1
    }
    return collator.compare(displayName(a), displayName(b))
  })
})

/**
 * 把排序后的好友按首字母分组。
 *
 * <p>{@code Map} 会保持插入顺序，而 sortedFriends 已经排好序了，
 * 所以分组结果天然就是 A→Z→# 的顺序，这里不需要再排一次。
 */
const groups = computed(() => {
  const map = new Map()
  for (const friend of sortedFriends.value) {
    const key = initialOf(friend)
    if (!map.has(key)) {
      map.set(key, [])
    }
    map.get(key).push(friend)
  }
  return [...map.entries()].map(([initial, list]) => ({ initial, friends: list }))
})

/** 搜索结果：备注 / 昵称 任一命中就算（都忽略大小写） */
const searchResult = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return []
  }
  return sortedFriends.value.filter((friend) =>
    [friend.remark, friend.nickName].some(
      (value) => value && String(value).toLowerCase().includes(kw),
    ),
  )
})

/**
 * 真正渲染的行。
 *
 * <p>搜索时是"扁平结果"（不分组）—— 分组是给"浏览全部"用的，
 * 搜索时用户只关心"找到了哪几个"，再按字母拆成好几段反而更难扫。
 */
const rows = computed(() => {
  if (keyword.value.trim()) {
    return searchResult.value.map((friend) => ({
      type: 'friend',
      friend,
      key: 'f-' + friend.userId,
    }))
  }
  return groups.value.flatMap((group) => [
    { type: 'header', initial: group.initial, key: 'h-' + group.initial },
    ...group.friends.map((friend) => ({
      type: 'friend',
      friend,
      key: 'f-' + friend.userId,
    })),
  ])
})

/**
 * 点某个好友 → 进他的资料页（按 userId 查详情）。
 *
 * <p>"看到一个人 → 点进去看详细资料"是聊天软件里最自然的动作；
 * 以后还会在这一页上长出"发消息"（等消息模块做好）。
 */
function openFriend(friend) {
  // 路由参数是字符串，显式转一下，免得以后有人以为是 number
  router.push({ name: 'user-profile', params: { id: String(friend.userId) } })
}
</script>

<style scoped>
.contacts-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.contacts-heading p { margin:7px 0 0; font-size:12px; color:var(--chat-text-3); }
.contacts-label { font-size:11px; color:var(--chat-text-3); border:1px solid var(--chat-border); padding:5px 9px; border-radius:6px; }
.contacts-tabs { display:flex; gap:4px; width:fit-content; padding:4px; border-radius:10px; background:var(--chat-surface-2); margin:6px 0 10px; }
.contacts-tabs button { display:flex; align-items:center; gap:8px; padding:9px 19px; color:var(--chat-text-2); border:1px solid transparent; border-radius:7px; background:transparent; cursor:pointer; font:inherit; }
.contacts-tabs button.selected { background:var(--chat-surface); color:var(--chat-brand); border-color:var(--chat-border); font-weight:600; }
.section-count { color:var(--chat-text-3); font-size:11px; }
.request-count { display:inline-flex; align-items:center; justify-content:center; background:var(--chat-danger); color:white; min-width:18px; height:18px; padding:0 5px; box-sizing:border-box; border-radius:10px; font-size:10px; line-height:1; }
.invitation-entry { display:flex; width:100%; align-items:center; gap:12px; text-align:left; border:1px solid var(--chat-border); background:var(--chat-surface); border-radius:12px; padding:15px; color:var(--chat-text); cursor:pointer; font:inherit; }
.invitation-entry:hover { border-color:var(--chat-brand); }
.invitation-entry-icon { display:grid; place-items:center; width:42px; height:42px; border-radius:11px; background:var(--chat-surface-2); color:var(--chat-brand); font-size:20px; }
.invitation-entry-copy { display:flex; flex-direction:column; flex:1; gap:5px; }
.invitation-entry-copy strong { font-size:13px; font-weight:600; }
.invitation-entry-copy small { font-size:12px; color:var(--chat-text-3); }
/*
  注意：这里【不要】再写 height:100% + overflow-y:auto。
  滚动是外层 MainLayout 的 .main-body 负责的，这里再套一层会出现"两层滚动条"，
  而且底部导航栏附近的表现会很怪。让内容自然撑高就行。

  页面外壳（padding + 块间距）用共享的 .chat-page，这一页不再自己写 padding：
  以前各页各写各的，改一次间距要动所有文件。
  ⚠️ scoped 不会影响全局类：.chat-page / .chat-card / .chat-empty 都来自 src/style.css，
     这里只能覆盖，不能重新定义 —— 重新定义就等于又分叉出一份样式。
*/

.search-bar {
  /* 以前这一段是"贴边 + 白底 + 下边框"的整条横带；换成页面壳之后
     横带会和卡片边框打两次边，所以只留一块输入框的间距 */
  padding: 0;
}

.search-icon {
  /* 图标的默认字号是 1em，放大镜略小一点才不会顶到输入框的上下内边距 */
  font-size: 15px;
  color: var(--chat-text-3);
}

.list-meta {
  margin-top: calc(var(--chat-gap) * -0.5);
  font-size: 12px;
  color: var(--chat-text-3);
}

/* ---- 添加好友入口 ---- */

.add-entry {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 0 14px;
  background: var(--chat-surface);
  border: 1px solid var(--chat-border);
  border-radius: var(--chat-radius);
  cursor: pointer;
  /* 悬停/按下的反馈统一用 --chat-dur：各页自己写 0.15s / 0.2s 会让手感忽快忽慢 */
  transition: background-color var(--chat-dur);
}

.add-entry:hover {
  background: var(--chat-surface-2);
}

.add-icon {
  /* 固定成和好友头像一样宽（40px），两行入口里的图标才能和列表头像对成一条竖线 */
  flex-shrink: 0;
  width: 40px;
  text-align: center;
  font-size: 18px;
  color: var(--chat-brand);
}

.add-text {
  flex: 1;
  font-size: 13.5px;
  color: var(--chat-text);
}

.add-arrow {
  /* 箭头是"可以点进去"的提示，属于次要信息，所以用最弱的一档灰 */
  flex-shrink: 0;
  font-size: 15px;
  color: var(--chat-text-3);
}

/* 待处理申请的红点（数字） */
.badge {
  flex-shrink: 0;
  min-width: 18px;
  padding: 0 5px;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  /*
    红底白字是"未读条数"的固定语义。
    白字必须用 --el-color-white 而不是 --chat-surface：后者在深色模式是"深灰表面色"，
    配红底会变成"红底黑字"，对比度掉一半（EP 的暗色主题不重定义 --el-color-white）。
  */
  color: var(--el-color-white);
  background: var(--chat-danger);
  border-radius: 9px;
}

/*
  好友列表。
  整块用一张 .chat-card（见模板上的 chat-card 类），行与行之间用 1px 分隔线，
  而不是每行一张卡片：列表型界面里"每行一个圆角卡片"会让行距看起来忽大忽小，
  扫一列名字时也更容易串行。
  overflow:hidden 是必需的 —— 卡片有 12px 圆角，行自己不圆角，
  不裁的话第一行和最后一行的悬停底色会从圆角外面露出直角。
*/
.friend-list {
  overflow: hidden;
}

.group-title {
  /* 分节标题：小一号、字距拉开一点，读起来像"标签"而不是一条正文，
     这样它才不会和下面的好友名字抢注意力 */
  padding: 10px 14px 6px;
  font-size: var(--chat-fs-meta);
  font-weight: 600;
  letter-spacing: 0.6px;
  color: var(--chat-text-3);
  /* 分节标题字号跟时间/说明同一档 */
  /* 用底色而不是再加一条下边框：标题和后面的行贴在一起时才不会出现"双层线" */
  background: var(--chat-surface-2);
}

.friend-item {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 行高走统一令牌（60px）：整个列表成为一条整齐的网格 */
  min-height: var(--chat-row-h);
  padding: 0 14px;
  cursor: pointer;
  /* 分隔线只画在行底部。最后一行会多出一条贴着卡片下沿的线，
     所以下面用 :last-child 去掉它 */
  border-bottom: 1px solid var(--chat-border);
  transition: background-color var(--chat-dur);
}

.friend-item:last-child {
  border-bottom: none;
}

.friend-item:hover {
  background: var(--chat-surface-2);
}

/* 按下（点下去还没松开）时再深一档反馈，不然只有悬停的话"点没点上"要靠眼睛判断 */
.friend-item:active {
  background: var(--chat-border);
}

.friend-avatar {
  /* 别让文字把头像压扁 */
  flex-shrink: 0;
  font-size: 15px;
}

.friend-text {
  /* flex:1 让右边的箭头被推到最右边；
     min-width:0 是让名字的省略号真正生效的关键：
     flex 子项默认 min-width:auto，长名字会把容器撑开而不是省略 */
  flex: 1;
  min-width: 0;
}

.friend-arrow {
  flex-shrink: 0;
  font-size: 15px;
  color: var(--chat-text-3);
}

.friend-name {
  font-size: var(--chat-fs-name);
  color: var(--chat-text);
}

.friend-sub {
  margin-top: 2px;
  font-size: var(--chat-fs-meta);
  color: var(--chat-text-3);
}

.state-box {
  /* 骨架屏和空态都放在页面壳里，这里不再加白底和边框 ——
     再套一层卡片会出现"卡片里的卡片"这种双重边框 */
  background: transparent;
}
</style>
