import { useRouter } from 'vue-router'

/**
 * "返回上一页"的统一实现（聊天页 / 资料页 / 添加好友 / 新的朋友各写过一份，只有兜底路由不同）。
 *
 * <p><b>为什么要判断 {@code window.history.state?.back}</b>：直接用 {@code router.back()} 的话，
 * 用户"直接刷新本页"（或者从外部深链接进来）时 history 里没有上一页，
 * back() 会什么都不做 —— 界面像是卡死了。所以没有上一页时兜底跳一次。
 *
 * <p>有上一页就真的返回，而不是无脑 push 到某个固定页面：这样能保留用户原来的浏览轨迹
 * （比如"联系人 → 资料页 → 聊天页"，返回时应该一层层退回去）。
 *
 * @param {string} fallbackRouteName 没有上一页时回哪个路由。
 *        用【路由 name】而不是路径：以后改路径这里一行都不用动。
 * @returns {() => void} 可以直接绑到模板的 {@code @click} 上
 */
export function useBack(fallbackRouteName) {
  const router = useRouter()
  return function goBack() {
    if (window.history.state?.back) {
      router.back()
    } else {
      router.push({ name: fallbackRouteName })
    }
  }
}
