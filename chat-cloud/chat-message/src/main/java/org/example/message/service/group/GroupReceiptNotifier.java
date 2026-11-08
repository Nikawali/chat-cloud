package org.example.message.service.group;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.common.dto.GroupReceiptState;
import org.example.common.protocol.ChatFrames;
import org.example.message.mapper.GroupReceiptMapper;
import org.example.message.ws.RemoteMessagePusher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Best-effort state notification after commit; existing snapshot queries repair missed notifications. */
@Service
@Slf4j
public class GroupReceiptNotifier {
    private final GroupReceiptMapper receipts;
    private final RemoteMessagePusher pusher;
    private final ThreadPoolTaskExecutor executor;

    public GroupReceiptNotifier(GroupReceiptMapper receipts, RemoteMessagePusher pusher,
            @Qualifier("groupReceiptExecutor") ThreadPoolTaskExecutor executor) {
        this.receipts=receipts;this.pusher=pusher;this.executor=executor;
    }

    public record Advance(long receivedAfter,long receivedUpTo,long readAfter,long readUpTo) {
        public boolean changed() {return receivedUpTo>receivedAfter || readUpTo>readAfter;}
    }

    public void publish(Long groupId,GroupReceiptState.Member member,Advance advance) {
        if(!advance.changed()) return;
        try {executor.execute(()->{
            try {notifySenders(groupId,member,advance);}
            catch(Exception e) {log.warn("Group receipt notification failed; snapshot query repairs: group={}, user={}",groupId,member.userId(),e);}
        });}
        catch(RuntimeException e) {log.warn("Group receipt notification queue unavailable; snapshot query repairs: group={}, user={}",groupId,member.userId());}
    }

    private void notifySenders(Long groupId,GroupReceiptState.Member member,Advance advance) {
        Map<String,Object> frame=ChatFrames.of("group-receipt","groupId",groupId,"data",member);
        long after=0;
        for(;;) {
            // An old queued event must not announce a former membership as current.
            var current=receipts.find(groupId,member.userId());
            if(current==null || !current.memberVersion().equals(member.memberVersion())
                    || !current.visibleFromSeq().equals(member.visibleFromSeq())) return;
            var senders=receipts.affectedSenders(groupId,member.userId(),advance.receivedAfter(),advance.receivedUpTo(),
                    advance.readAfter(),advance.readUpTo(),after);
            if(senders.isEmpty()) return;
            var routes=pusher.onlineRoutes(senders);
            if(!routes.isEmpty()) pusher.pushBatch(routes,frame);
            after=senders.get(senders.size()-1);
            if(senders.size()<100) return;
        }
    }
}
