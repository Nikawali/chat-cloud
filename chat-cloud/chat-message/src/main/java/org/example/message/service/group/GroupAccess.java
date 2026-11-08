package org.example.message.service.group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.example.message.mapper.GroupMessageMapper;
import org.example.message.mapper.GroupReceiptMapper;
import org.example.common.exception.BizException;
/** 发送锁群再锁成员；回执只锁成员；普通查询使用一致性快照。 */
@Component
@RequiredArgsConstructor
public class GroupAccess {
 private final GroupMessageMapper messages;
 private final GroupReceiptMapper members;
 public GroupMessageMapper.GroupHead lockForSend(Long groupId,Long userId) {
  var head=messages.lockGroup(groupId);
  if(head==null || head.groupStatus()!=1) throw new BizException(404,"群不存在或已解散");
  if(messages.lockActiveMember(groupId,userId)==null) throw new BizException(403,"你不是该群成员");
  return head;
 }
 public record Membership(GroupMessageMapper.GroupHead head,GroupReceiptMapper.Member member) {
  public long visibleLastSeq() {return member.visibleToSeq()==null?head.lastSeq():Math.min(head.lastSeq(),member.visibleToSeq());}
 }
 public Membership read(Long groupId,Long userId) {
  return checked(messages.readGroup(groupId),members.find(groupId,userId));
 }
 public Membership lockForReceipt(Long groupId,Long userId) {
  // 先取得成员当前读，等待成员变更结束后才建立普通读快照。
  // 此路径不再获取群锁，避免与发送/入群的“群→成员”顺序形成锁环。
  var member=members.lock(groupId,userId);
  return checked(messages.readGroup(groupId),member);
 }
 private Membership checked(GroupMessageMapper.GroupHead head,GroupReceiptMapper.Member member) {
  if(head==null || head.groupStatus()!=1 || member==null) throw new BizException(403,"不是有效群成员");
  return new Membership(head,member);
 }
 public static org.springframework.transaction.support.TransactionTemplate snapshotTransaction(
   org.springframework.transaction.support.TransactionTemplate source) {
  var snapshot=new org.springframework.transaction.support.TransactionTemplate(source.getTransactionManager());
  snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  snapshot.setReadOnly(true);
  return snapshot;
 }
}
