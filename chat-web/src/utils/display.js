// 展示相关的公共小工具：显示名、头像首字、性别文案。
//
// 为什么抽出来：这几个函数以前在 6~7 个页面里各写了一遍（签名还不一样：有的收对象、
// 有的收字符串），而它们的规则其实**必须一致** —— "备注优先于昵称""没有名字就用 ? 占位"。
// 各写一份的结果就是"联系人页显示备注、消息页显示昵称"这种改一处漏一处的不一致。

/**
 * 显示名：<b>备注 &gt; 昵称</b>。
 *
 * <p>顺序是有讲究的：备注是我自己起的（我认得它），昵称是对方自己设的。
 * 两个都没有就返回空串 —— <b>不编"用户123"这种假名字</b>：
 * 它看着像 bug，而且真出现空名字，十有八九是数据真缺了（比如好友列表里查不到这个人）。
 *
 * @param {{remark?: string, nickName?: string}} [user] 好友卡片 / 用户卡片
 * @returns {string} 可能是空串
 */
export function displayName(user) {
  return user?.remark || user?.nickName || ''
}

/**
 * 头像上显示的首字（收字符串）。
 *
 * @param {string} [name] 显示名
 * @returns {string} 没有名字时返回 {@code '?'} —— 占位符比编一个假名字诚实
 */
export function firstChar(name) {
  const text = String(name || '').trim()
  return text ? text.charAt(0).toUpperCase() : '?'
}

/**
 * 头像上显示的首字（收用户对象）—— 等价于 {@code firstChar(displayName(user))}。
 *
 * <p>两个都导出是因为调用方手里有时是对象、有时已经是算好的显示名，
 * 免得每处都写一遍 {@code firstChar(displayName(x))}。
 */
export function firstCharOf(user) {
  return firstChar(displayName(user))
}

/**
 * 性别文案：后端存的是单字符 {@code M} / {@code F}，界面要显示中文。
 *
 * @param {string} [gender]
 * @returns {string} 未知/未填时返回空串（调用方用 filter(Boolean) 拼副标题）
 */
export function genderText(gender) {
  if (gender === 'M') {
    return '男'
  }
  if (gender === 'F') {
    return '女'
  }
  return ''
}
