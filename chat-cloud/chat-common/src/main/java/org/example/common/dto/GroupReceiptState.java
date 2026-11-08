package org.example.common.dto;
import java.util.List;
public record GroupReceiptState(Long groupId, Long memberVersion, Long visibleFromSeq, Long lastSeq, List<Member> members) {
 public record Member(Long userId, Long memberVersion, Long visibleFromSeq, Long receivedSeq, Long readSeq) {}
}
