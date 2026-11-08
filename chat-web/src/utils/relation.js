/**
 * 「我和他是什么关系」—— 后端 {@code RelationStatus} 的镜像，以及"这个状态该显示什么按钮"。
 *
 * <p><b>为什么单独放一个文件？</b> 加好友页和资料页都要用同一套判断。
 * 各写一份的话，早晚出现"这里显示已申请、那里还显示添加"的不一致 ——
 * 这属于展示规则，只该有一份。
 */

export const RELATION = {
  /** 陌生人 */
  NONE: 'NONE',
  /** 我申请了对方，等对方处理 */
  PENDING_OUT: 'PENDING_OUT',
  /** 对方申请了我，等我处理 */
  PENDING_IN: 'PENDING_IN',
  /** 已经是好友 */
  FRIEND: 'FRIEND',
  /** 点的是自己 */
  SELF: 'SELF',
}

/**
 * 关系 → 按钮该怎么显示。
 *
 * @param {string} status RELATION 里的某个值（拿不到时按陌生人处理）
 * @returns {{kind: string, text: string, disabled: boolean}|null}
 *          {@code kind} 告诉页面点了要做什么：
 *          {@code add}=弹加好友对话框，{@code message}=发消息（还没做），
 *          {@code requests}=去"新的朋友"处理，{@code pending}=什么都不做（只能等）；
 *          返回 {@code null} 表示这个状态不该显示任何操作按钮（自己）。
 */
export function relationAction(status) {
  switch (status) {
    case RELATION.FRIEND:
      return { kind: 'message', text: '发消息', disabled: false }
    case RELATION.PENDING_OUT:
      // 已经申请过了，再点没有意义：按钮置灰，文案说明在等什么
      return { kind: 'pending', text: '已申请，等待验证', disabled: true }
    case RELATION.PENDING_IN:
      return { kind: 'requests', text: '对方申请了你，去处理', disabled: false }
    case RELATION.SELF:
      return null
    default:
      return { kind: 'add', text: '添加到通讯录', disabled: false }
  }
}
