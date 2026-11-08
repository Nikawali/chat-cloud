// 「退出登录」要做的那一串清理 —— 只此一份。
//
// ── 为什么必须抽出来（而不是在登录页和设置页各写一遍）────────────
// 这段序列里每一步都有它非做不可的理由，而且**顺序有讲究**：
//
//   ① api.logout()           告诉后端把这个 token 从 Redis 删掉（失败也要继续往下走）
//   ② stopChatSync()         停掉全局同步：它拿着定时器和帧订阅，而 token 马上要失效了
//   ③ disconnectChatPermanently()
//                            断开实时通道。顺序在 ② 之后：先停掉"会重连的人"，再断连接，
//                            否则同步模块会立刻把连接又拉起来
//   ④ useLocalAccount(null)  清掉本地消息库的**内存身份**。少了这一步，下一个登录的人
//                            会在自己的身份到达之前读到上一个人的本地会话列表
//   ⑤ clearLogin()           最后才清 token —— 前四步都还可能要读 token
//
// 漏掉其中任何一步都是能看见的 bug（推送达不到、串号、卡在"已登录"状态）。
// 复制这段代码的下场就是"改了登录页、忘了设置页"，所以这里只留一份。
//
// ⚠️ 这里只做"状态清理"，不碰界面：提示文案、清空表单、跳转到登录页由调用方负责
//    —— 登录页要留在原地显示"已退出"，设置页却要跳走，界面的事各页不一样。
import { api } from '../api'
import { clearLogin } from './auth'
import { clearAccountData } from './accountData'
import { disconnectChatPermanently } from './chatSocket'
import { stopChatSync } from './chatSync'
import { useLocalAccount } from './messageStore'

/**
 * 清掉这次登录留下的一切痕迹（服务端 token + 客户端连接 + 本地身份 + 凭证）。
 *
 * <p>本地聊天记录**不会被删**：它按账号存在 {@code messages/u<我的 userId>/} 里，
 * 这里只是不再以"某个人"的名义读写它。下次同一个人登回来，历史原样还在。
 *
 * @returns {Promise<{ serverLogoutOk: boolean }>} 后端登出是否成功。
 *          失败也照常清本地（否则用户会卡在"已登录但每个请求都失败"的状态），
 *          调用方可以据此给一句不同的提示。
 */
export async function performLogout() {
  let serverLogoutOk = true
  try {
    await api.logout()
  } catch (e) {
    // 后端说不清（网络断、服务没起）也要继续清本地 —— 本地清干净了，
    // 用户至少能重新登录；反过来留着本地凭证才是真的卡死
    serverLogoutOk = false
    console.warn('[logout] 通知后端登出失败，已继续清理本地登录状态', e)
  }

  // 顺序见文件头：先停同步（它持有重连逻辑），再断连接
  stopChatSync()
  disconnectChatPermanently()
  // 本地消息库的内存身份也要清：它是"当前是谁"的唯一来源，留着就会串号
  useLocalAccount(null)
  // 账号维度的内存缓存（我是谁/好友/申请）也清：下一个人登录前不能残留上一个的
  clearAccountData()
  clearLogin()

  return { serverLogoutOk }
}
