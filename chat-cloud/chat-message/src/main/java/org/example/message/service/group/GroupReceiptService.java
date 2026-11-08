package org.example.message.service.group;
import org.example.message.service.delivery.GroupDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.common.dto.*;
import org.example.common.exception.BizException;
import org.example.message.mapper.*;
@Service
@RequiredArgsConstructor
public class GroupReceiptService {
 private final GroupAccess access;
 private final GroupReceiptMapper receipts;
 private final TransactionTemplate transactions;
 private final GroupReceiptNotifier notifier;
 private final GroupDeliveryService delivery;
 public GroupReceiptState report(GroupReceiptCommand c) {
  if(c==null || c.userId()==null || c.userId()<=0 || c.groupId()==null || c.groupId()<=0 || c.deviceId()==null || !c.deviceId().matches("[A-Za-z0-9_-]{1,64}")) throw new BizException(400,"群回执参数不合法");
  GroupReceiptNotifier.Advance[] advance={null};
  long[] cleanup={0,0};
  boolean updating=c.receivedSeq()!=null || c.readSeq()!=null;
  var transaction=updating?transactions:GroupAccess.snapshotTransaction(transactions);
  var state=transaction.execute(tx->{
   var membership=updating?access.lockForReceipt(c.groupId(),c.userId()):access.read(c.groupId(),c.userId());
   var head=membership.head();var member=membership.member();
   if(c.receivedSeq()!=null || c.readSeq()!=null) {
    if(!member.memberVersion().equals(c.memberVersion())) throw new BizException(409,"成员版本已变化，请重新同步");
    long floor=member.visibleFromSeq()-1;
    long ceiling=membership.visibleLastSeq();
    // Delivery notifications describe the user-level maximum, not one device catching up.
    long previousReceived=Math.max(floor,receipts.receivedAcrossDevices(c.groupId(),c.userId(),member.memberVersion()));
    Long old=receipts.received(c.groupId(),c.userId(),c.deviceId(),member.memberVersion());
    long received=old==null?floor:Math.max(floor,old);
    if(c.receivedSeq()!=null) {
     if(c.receivedSeq()<floor || c.receivedSeq()>ceiling) throw new BizException(400,"送达游标超出可见范围");
     cleanup[0]=received; cleanup[1]=Math.max(received,c.receivedSeq());
     received=Math.max(received,c.receivedSeq());
     if(old==null || received>old) receipts.receive(c.groupId(),c.userId(),c.deviceId(),member.memberVersion(),received);
    }
    if(c.readSeq()!=null) {
     if(c.readSeq()<floor || c.readSeq()>received || c.readSeq()>ceiling) throw new BizException(400,"已读不能超过本设备连续送达位置");
     if(c.readSeq()>member.readSeq()) receipts.read(c.groupId(),c.userId(),c.readSeq());
    }
    advance[0]=new GroupReceiptNotifier.Advance(previousReceived,Math.max(previousReceived,received),
            member.readSeq(),c.readSeq()==null?member.readSeq():Math.max(member.readSeq(),c.readSeq()));
   }
   return new GroupReceiptState(c.groupId(),member.memberVersion(),member.visibleFromSeq(),head.lastSeq(),(c.ownOnly() || c.receivedSeq()!=null || c.readSeq()!=null) ? receipts.ownState(c.groupId(),c.userId()) : receipts.states(c.groupId()));
  });
  // Delete Redis retry entries only after the durable receipt commits.
  if(cleanup[1]>cleanup[0]) try { delivery.acknowledge(c.groupId(),c.userId(),cleanup[0],cleanup[1]); }
  catch(Exception e) { /* retry scanner checks persisted cursor and cleans remaining entries */ }
  // Queue after commit; remote calls never delay the durable receipt result.
  if(advance[0]!=null && advance[0].changed()) {
   var own=state.members().stream().filter(m->m.userId().equals(c.userId())).findFirst().orElseThrow();
   notifier.publish(c.groupId(),own,advance[0]);
  }
  return state;
 }
}
