package org.example.message.service.delivery;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/** 公共批次执行器；停止条件、预取内容和退避参数仍由单聊/群聊各自决定。 */
public final class DeliveryRetryRunner {
    private DeliveryRetryRunner() {}

    public record BatchResult(int claimed, int retried, int dropped) {}

    public static <T> BatchResult run(
            DeliveryQueue queue, int limit, long leaseMillis,
            Function<List<DeliveryQueue.ClaimedDelivery>, T> load,
            BiPredicate<DeliveryQueue.ClaimedDelivery, T> handle,
            BiConsumer<List<DeliveryQueue.ClaimedDelivery>, Exception> loadFailure,
            BiConsumer<DeliveryQueue.ClaimedDelivery, Exception> itemFailure) {
        var claims = queue.claimDue(limit, leaseMillis);
        if (claims.isEmpty()) return new BatchResult(0, 0, 0);

        T batch;
        try {
            batch = load.apply(claims);
        } catch (Exception e) {
            loadFailure.accept(claims, e);
            return new BatchResult(0, 0, 0);
        }

        int retried = 0;
        int dropped = 0;
        for (var claim : claims) {
            try {
                if (handle.test(claim, batch)) retried++;
                else dropped++;
            } catch (Exception e) {
                itemFailure.accept(claim, e);
            }
        }
        return new BatchResult(claims.size(), retried, dropped);
    }

    public static boolean pushAndReschedule(
            DeliveryQueue queue, DeliveryQueue.ClaimedDelivery claim,
            long delay, Supplier<Boolean> push) {
        try {
            return push.get();
        } finally {
            // ACK 和新租约仍由 DeliveryQueue 的 Lua 条件更新阻止旧任务重新入队。
            queue.reschedule(claim, delay);
        }
    }
}
