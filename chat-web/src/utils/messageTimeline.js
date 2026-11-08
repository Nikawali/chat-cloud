const TIME_DIVIDER_GAP_MS = 5 * 60 * 1000
const WEEKDAY_TEXT = ['星期日','星期一','星期二','星期三','星期四','星期五','星期六']
function isSameLocalDay(a, b) {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()
}

/** 当天 0 点的时间戳：算"几天前"用它，跨月跨年都不会错 */
function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/**
 * 时间分隔条上的文字。
 *
 * <p>越近越精确，和微信一致：
 * <ul>
 *   <li>今天 → {@code 14:32}</li>
 *   <li>昨天 → {@code 昨天 14:32}</li>
 *   <li>2~6 天前 → {@code 星期三 14:32}（"上周三我们聊的那件事"比"10-05"更容易对上号）</li>
 *   <li>更早 → {@code 3月5日 14:32}；跨年再加年份 {@code 2025年3月5日 14:32}</li>
 * </ul>
 *
 * <p>⚠️ 服务端给的是本地时间字符串（{@code 2026-10-07T16:02:27.2877832}），
 * 小数位有 6 位和 7 位两种写法，{@code new Date()} 都能解析（和 Message.vue 那套一致）。
 * 解析不出来就返回空串 —— 模板会把整条分隔条跳过去，不显示一块空白。
 */
export function formatDividerTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const hm = `${pad(date.getHours())}:${pad(date.getMinutes())}`

  if (isSameLocalDay(date, now)) {
    return hm
  }
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (isSameLocalDay(date, yesterday)) {
    return `昨天 ${hm}`
  }
  const daysAgo = Math.round((startOfDay(now) - startOfDay(date)) / 86400000)
  if (daysAgo >= 0 && daysAgo < 7) {
    return `${WEEKDAY_TEXT[date.getDay()]} ${hm}`
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}月${date.getDate()}日 ${hm}`
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${hm}`
}

/**
 * 渲染用的列表：消息之间夹着"时间分隔条"。
 *
 * <p>三条规则（和微信一致）：
 * <ol>
 *   <li>第一条消息前一定要有一条；</li>
 *   <li>之后每条消息和<b>上一条分隔条的时间</b>比，差 {@link TIME_DIVIDER_GAP_MS} 以上才插新的；</li>
 *   <li><b>跨天强制插一条</b>，哪怕只差 1 分钟 —— 否则"昨天 23:58 / 今天 00:01"会共享一个时间，
 *       看起来像同一时刻发的。</li>
 * </ol>
 *
 * <p>分隔条是"和消息同构的伪对象"：真正的消息对象原样放进去（没有复制、也没有加字段），
 * 所以模板里所有 {@code message.xxx} 的引用都不用动，只需按 {@code kind} 分支渲染。
 * 按当前消息列表线性遍历，不限制消息条数。
 */
export function buildMessageTimeline(messages) {
  const items = []
  let lastShownAt = 0
  let lastShownDay = null

  for (const message of messages) {
    const date = new Date(message.createdAt)
    const at = date.getTime()
    if (Number.isFinite(at)) {
      const day = `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
      const needDivider = lastShownAt === 0
        || at - lastShownAt >= TIME_DIVIDER_GAP_MS
        || day !== lastShownDay
      if (needDivider) {
        const text = formatDividerTime(message.createdAt)
        if (text) {
          items.push({ kind: 'time', key: `t-${message.key}`, text })
        }
        // 解析不出时间（理论上不会发生）也要更新锚点，否则后面每条都会再试一次
        lastShownAt = at
        lastShownDay = day
      }
    }
    items.push(message)
  }
  return items
}

