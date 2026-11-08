package com.example.flashsale;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseServiceTest {
    RedissonClient redisson = mock(RedissonClient.class);
    RLock lock = mock(RLock.class);
    ProductRepository repository = mock(ProductRepository.class);
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    PurchaseService service = new PurchaseService(redisson, repository, transaction, 30);

    @BeforeEach
    void setup() {
        when(redisson.getLock("lock:product:1")).thenReturn(lock);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void holdsLockThroughTransactionAndUnlocks() throws Exception {
        when(lock.tryLock(30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(repository.find(1L)).thenReturn(Optional.of(new ProductRepository.Product(1L,"Phone",1,0)));
        when(repository.deduct(1L,1)).thenReturn(true);
        var purchase = new ProductRepository.Purchase(5L,1L,1);
        when(repository.savePurchase(1L,1)).thenReturn(purchase);
        assertThat(service.buy(1L,1)).isEqualTo(purchase);
        var order = inOrder(lock,transaction,repository);
        order.verify(lock).tryLock(30,TimeUnit.SECONDS);
        order.verify(transaction).execute(any());
        order.verify(repository).find(1L);
        order.verify(repository).deduct(1L,1);
        order.verify(repository).savePurchase(1L,1);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
    }

    @Test
    void soldOutStillUnlocks() throws Exception {
        when(lock.tryLock(30,TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(repository.find(1L)).thenReturn(Optional.of(new ProductRepository.Product(1L,"Phone",0,1)));
        assertThatThrownBy(() -> service.buy(1L,1)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Sản phẩm đã hết hàng");
        verify(lock).unlock();
        verify(repository,never()).deduct(anyLong(),anyInt());
        verify(repository,never()).savePurchase(anyLong(),anyInt());
    }

    @Test
    void transactionFailureStillUnlocks() throws Exception {
        when(lock.tryLock(30,TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("DB commit failed")).when(transaction).execute(any());
        assertThatThrownBy(() -> service.buy(1L,1)).isInstanceOf(IllegalStateException.class);
        verify(lock).unlock();
    }

    @Test
    void unsuccessfulAcquisitionCannotReadStockOrUnlock() throws Exception {
        when(lock.tryLock(30,TimeUnit.SECONDS)).thenReturn(false);
        assertThatThrownBy(() -> service.buy(1L,1)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(transaction,repository);
        verify(lock,never()).unlock();
    }

    @Test
    void interruptedWaitRestoresInterruptAndDoesNotUnlock() throws Exception {
        when(lock.tryLock(30,TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> service.buy(1L,1)).isInstanceOf(ResponseStatusException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(transaction,repository);
            verify(lock,never()).unlock();
        } finally { Thread.interrupted(); }
    }

    @Test
    void invalidQuantityNeverRequestsLock() {
        assertThatThrownBy(() -> service.buy(1L,0)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(redisson,transaction,repository);
    }
}
