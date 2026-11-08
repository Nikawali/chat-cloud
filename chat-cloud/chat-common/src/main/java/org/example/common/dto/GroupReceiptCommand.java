package org.example.common.dto;
public record GroupReceiptCommand(Long userId, Long groupId, String deviceId, Long memberVersion, Long receivedSeq, Long readSeq, boolean ownOnly) {
 public GroupReceiptCommand(Long userId,Long groupId,String deviceId,Long memberVersion,Long receivedSeq,Long readSeq) {this(userId,groupId,deviceId,memberVersion,receivedSeq,readSeq,false);}
}
