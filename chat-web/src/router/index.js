import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '../utils/auth'
import MainLayout from '../views/MainLayout.vue'
// 消息区的主从布局壳（列表轨道 + 聊天区）。静态 import 的理由同 MainLayout：
// 它是"消息"这个 tab 的框架，登录后默认就落在这里，懒加载只会多等一次 JS。
import MessageLayout from '../views/MessageLayout.vue'

/**
 * 光杆 /message 的占位组件。
 *
 * <p>为什么不单独写一个 .vue 文件：它【什么都不渲染】——
 * 宽窗口下右边那句"从左边选一个会话"是 MessageLayout 自己画的，
 * 这里只需要一个空组件把路由占住（不然 vue-router 会因为父路由没有默认子路由
 * 而把整个 router-view 渲染成空，改动路由时容易踩）。少一个文件少一份噪音。
 */
const MessageBlank = {
  name: 'MessageBlank',
  render: () => null,
}

// 用 hash 模式：Electron 打包后页面是 file:// 协议，hash 模式最稳，不会 404
//
// 【meta.public】标记"不需要登录就能看"的页面（用户还没证明自己是谁）。
// 这个标记有两个用处：
//   ① 路由守卫据此判断"这一页要不要登录"——默认要登录，白名单是例外
//   ② App.vue 据此决定要不要显示 Electron 演示按钮：只在这两页显示，
//      否则那个 fixed 在右下角的按钮会压在主页面的底部导航栏上
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/Register.vue'),
    meta: { public: true },
  },

  // ---- 主页面：底部导航栏 + 三个子页面 ----
  //
  // 用【嵌套路由】而不是"一个页面里用变量切视图"，好处见 MainLayout.vue 的说明。
  // 子路由写成相对路径（'message'），拼出来就是 /message、/contacts、/settings。
  {
    path: '/',
    // 布局组件用静态 import：它是进入应用后马上要用的主框架，
    // 懒加载反而让"登录成功后第一次进主页面"多等一次 JS 下载
    component: MainLayout,
    children: [
      // 根路径 → 消息页（默认落在"消息"这个 tab）
      { path: '', redirect: '/message' },
      {
        // 消息这一组：会话列表 + 单聊页，包在一个"主从布局"壳里（MessageLayout）。
        //
        // 为什么要多这一层壳：桌面窗口有 ~1000px 宽，会话列表铺满整宽会变成
        // "名字在最左、时间在最右、中间一片空白"。桌面版的解法是并排 ——
        // 列表占左边一条固定轨道，聊天占满右边（Telegram/Slack 都是这样）。
        // 窄窗口时壳自己会退回单栏（见 MessageLayout 里的媒体查询）。
        //
        // ⚠️ 列表组件是在壳里【直接渲染】的，不走这里的 router-view：
        //    嵌套路由的 router-view 一次只能渲染匹配到的那一个子路由，
        //    而这里需要同时显示列表和聊天。所以壳里写死列表，router-view 只负责右边。
        path: 'message',
        component: MessageLayout,
        children: [
          // 光杆 /message：右边显示"选一个会话"的引导语（窄窗口下整块不显示）
          { path: '', name: 'message', component: MessageBlank },
          {
            path: 'group/:groupId',
            name: 'group-chat',
            component: () => import('../views/GroupChat.vue'),
            props: true,
            meta: { hideTabBar: true },
          },
          {
            // 单聊页：/message/chat/2
            //
            // props: true 把路由参数当 prop 传进组件（组件里就不用 useRoute() 掏参数了）。
            // meta.hideTabBar：窄窗口下聊天页是三段式布局，输入框要贴着窗口底部，
            // 底下再压一排 tab 会很挤（真实聊天软件也都这么做）。
            // 宽窗口下底部导航会由 CSS 重新显示出来 —— 那时左边还留着会话轨道，
            // 把导航藏掉反而显得整个应用缺了一块（见 MainLayout 的媒体查询）。
            path: 'chat/:userId',
            name: 'chat',
            component: () => import('../views/Chat.vue'),
            props: true,
            meta: { hideTabBar: true },
          },
        ],
      },
      {
        // 【这一层故意不写 component】：它只用来把 /contacts 和 /contacts/add 归成一组，
        // 好让底部导航的"联系人"在"添加好友"页也保持高亮。
        //
        // 高亮是按"当前匹配到的路由记录里有没有我"算的：/contacts/add 匹配到的记录里
        // 包含 contacts 这条，所以联系人 tab 会亮；如果把它做成兄弟路由（/add-friend），
        // 三个 tab 就会一个都不亮。
        //
        // RouterView 会自动跳过没有 component 的记录（vue-router 源码里的
        // `while (matchedRoute && !matchedRoute.components)`），所以这两个子页面
        // 都直接渲染在 MainLayout 的 router-view 里，不需要多余的空壳组件。
        path: 'contacts',
        children: [
          { path: '', name: 'contacts', component: () => import('../views/Contacts.vue') },
          {
            path: 'add',
            name: 'contacts-add',
            component: () => import('../views/ContactsAdd.vue'),
          },
          {
            // 「新的朋友」：别人申请加我的，待我处理。
            // 它是加好友链路的另一半 —— 没有它申请就永远没人同意。
            path: 'requests',
            name: 'contacts-requests',
            component: () => import('../views/FriendRequests.vue'),
          },
          {
            // 资料页：/contacts/user/2
            //
            // 路径写成 user/:id 而不是直接 :id：后者会和上面的 'add' 争
            // （vue-router 里静态段优先于变量段，能跑对，但一眼看不出谁赢，
            //  下次有人加 'addXxx' 之类的路由就容易踩坑）—— 写清楚最省事。
            //
            // props: true 把路由参数当 prop 传给组件，组件里就不用 useRoute() 掏参数了。
            path: 'user/:id',
            name: 'user-profile',
            component: () => import('../views/UserProfile.vue'),
            props: true,
          },
        ],
      },
      { path: 'settings', name: 'settings', component: () => import('../views/Settings.vue') },
    ],
  },

  // 兜底：未知路径回根路径，再由上面的 '' → /message 和守卫决定去哪。
  //
  // 注意这里【不要】直接 redirect 到 /login：
  // 那样"已登录的人手抖打了个错地址"会被扔回登录页，而正确行为是回主页面。
  // 统一走 '/' 分流，登录态判断只有一处。
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 全局路由守卫：没登录就别想看主页面。
 *
 * <p><b>判断依据</b>：本地有没有未过期的 token（{@code isLoggedIn()} →
 * 从凭证存储里读，过期会顺手清掉）。所以"已登录的人重开 App"会直接落到消息页，
 * 不用再登一次。
 *
 * <p><b>这只是界面层的拦截，不是安全边界</b>：真正的安全在网关 ——
 * 前端改任何本地状态都伪造不了身份，因为网关会把请求头里的 {@code X-User-Id}
 * 剥掉，再用 token 去 Redis 换一次。这里唯一的目的是别让用户看到一片空页面。
 */
router.beforeEach((to) => {
  // 登录/注册页放行（它们标了 meta.public）
  if (to.meta.public) {
    return true
  }
  if (isLoggedIn()) {
    return true
  }
  // 没登录 → 回登录页。
  // 刻意不记录"本来想去哪"、登录后再跳回去：目前登录成功统一进消息页，
  // 等真的有深链接需求（比如从外部链接打开某个会话）再加，现在加就是没人用的逻辑
  return { name: 'login' }
})

export default router
