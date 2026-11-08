package com.example.flashsale;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PurchaseService {
    private final RedissonClient redisson;
    private final ProductRepository repository;
    private final TransactionTemplate transaction;
    private final long waitSeconds;

    public PurchaseService(RedissonClient redisson, ProductRepository repository,
            TransactionTemplate transaction, @Value("${app.lock.wait-seconds:30}") long waitSeconds) {
        this.redisson = redisson;
        this.repository = repository;
        this.transaction = transaction;
        this.waitSeconds = waitSeconds;
    }

    public ProductRepository.Purchase buy(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng phải lớn hơn 0");
        }
        RLock lock = redisson.getLock("lock:product:" + productId);
        boolean acquired = false;
        try {
            // No fixed lease: Redisson watchdog renews while the owning JVM is alive.
            acquired = lock.tryLock(waitSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Hệ thống đang bận, vui lòng thử lại");
            }
            // execute() commits BEFORE returning, so unlock never precedes DB commit.
            return transaction.execute(status -> {
                ProductRepository.Product product = repository.find(productId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm"));
                if (product.stock() < quantity || !repository.deduct(productId, quantity)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Sản phẩm đã hết hàng");
                }
                return repository.savePurchase(productId, quantity);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Yêu cầu bị gián đoạn, vui lòng thử lại", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
