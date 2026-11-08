package org.example.message.service.group;
import org.example.message.vo.GroupMessageVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.message.mapper.*;
import org.example.common.exception.BizException;
@Service
@RequiredArgsConstructor
public class GroupSyncService {
 private final GroupSyncMapper sync;
 private final GroupAccess access;
 private final TransactionTemplate transactions;
 public record Page(Long groupId,Long memberVersion,Long visibleFromSeq,Long lastSeq,List<GroupMessageVO> messages) {}
 public List<GroupSyncMapper.Head> heads(Long user,long after) {
  if(after<0) throw new BizException(400,"分页参数不合法");
  return sync.heads(user,after);
 }
 public Page pull(Long user,Long groupId,long version,long after) {
  if(groupId==null || groupId<=0 || version<=0 || after<0) throw new BizException(400,"群补拉参数不合法");
  return GroupAccess.snapshotTransaction(transactions).execute(tx->{
   var membership=access.read(groupId,user);
   var head=membership.head();var member=membership.member();
   if(member.memberVersion()!=version) throw new BizException(409,"成员版本变化，请刷新群列表");
   long until=membership.visibleLastSeq();
   long from=Math.max(after,member.visibleFromSeq()-1);
   var rows=sync.page(groupId,from,until).stream().map(GroupMessageVO::of).toList();
   return new Page(groupId,member.memberVersion(),member.visibleFromSeq(),until,rows);
  });
 }
}
