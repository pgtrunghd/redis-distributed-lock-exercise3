# Bài tập 3 — Chống bán lố bằng Redis Distributed Lock

Đề bài: https://portal.rikkei.edu.vn/learn/159/homework/8349

Ứng dụng Spring Boot 3.5.6, Java 21, Redisson 3.51.0, Redis và PostgreSQL. Nhiều instance có thể cùng phục vụ API mua hàng với một kho chung.

## Cơ chế

`PurchaseService.buy` lấy `RLock` theo đúng key **`lock:product:{id}`**. Chỉ sau khi giành được khóa, service mới đọc tồn kho và trừ số lượng.

```text
tryLock -> transaction { đọc kho -> trừ kho -> ghi giao dịch mua -> commit }
        -> finally { kiểm tra quyền sở hữu -> unlock }
```

- `tryLock(waitSeconds, TimeUnit.SECONDS)` chờ tối đa 30 giây, không đặt lease cố định để Redisson watchdog gia hạn khóa. Watchdog timeout là 30 giây.
- `TransactionTemplate.execute` commit xong trước khi trả về, vì vậy `finally` không mở khóa trước commit. Việc trừ kho và lưu giao dịch mua cùng transaction: thất bại sẽ rollback.
- Luôn chạy `unlock` trong `finally`, chỉ unlock khi đã lấy khóa và thread hiện tại còn là chủ sở hữu. Không dùng `forceUnlock`.
- Tồn kho không đủ trả **409** với thông báo **“Sản phẩm đã hết hàng”**. Sản phẩm không tồn tại trả 404; quantity không hợp lệ trả 400.
- Hết thời gian chờ khóa trả 429 để phân biệt quá tải với hết hàng. Ngắt luồng giữ lại cờ interrupt và trả 503. Redis lỗi không có đường fallback bỏ qua khóa.
- Cập nhật SQL có điều kiện `stock >= quantity`, kèm CHECK không âm tại PostgreSQL. Đây là lớp bảo vệ thêm nếu JVM pause quá lâu khiến khóa mất hiệu lực. Trường hợp cập nhật không thành công không tạo giao dịch mua.

## Chạy ứng dụng

Cần JDK 21, Docker và Python 3 nếu chạy kiểm thử đồng thời.

```bash
docker compose up -d --wait
./gradlew test bootJar
./gradlew bootRun
```

macOS có thể chọn Java: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

Mặc định API cổng **8085**, PostgreSQL **5444**, Redis **6384**. Cấu hình qua `SERVER_PORT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_ADDRESS`, `LOCK_WAIT_SECONDS`. Tài khoản `postgres/postgres` trong compose chỉ dùng cho thực hành local. Database được giữ trong Docker volume.

## Thử API

Tạo một sản phẩm còn 1 món:

```bash
curl -sS -X POST localhost:8085/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Flash Sale Phone","stock":1}'
```

Lấy ID trả về, thay `1` bên dưới:

```bash
curl -i -X POST localhost:8085/api/products/1/purchases \
  -H 'Content-Type: application/json' -d '{"quantity":1}'

# Lần mua tiếp theo nhận 409: Sản phẩm đã hết hàng.
curl -i -X POST localhost:8085/api/products/1/purchases \
  -H 'Content-Type: application/json' -d '{"quantity":1}'

curl -sS localhost:8085/api/products/1
# stock = 0; sold = 1
```

Có thể chạy thêm instance trong terminal khác:

```bash
SERVER_PORT=8086 ./gradlew bootRun
```

Cả hai phải dùng chung DB và Redis. Không dùng `synchronized` hay khóa JVM cục bộ để thay thế distributed lock.

## Kiểm thử

```bash
./gradlew test bootJar
python3 scripts/concurrency_test.py
```

- 6 unit test: thứ tự lấy khóa/transaction/unlock; vẫn unlock khi hết hàng hoặc transaction lỗi; không đọc kho/unlock nếu chưa lấy khóa; giữ cờ interrupt; chặn quantity sai trước khi lấy khóa.
- Script tích hợp tự chạy **hai JVM** trên cổng 18085 và 18086, cùng PostgreSQL và Redis thật. Mỗi đợt tạo sản phẩm mới rồi phát **100 request đồng thời**, chia đều qua hai instance.
- Kiểm tra tồn kho 1 chỉ có 1 người mua thành công và 99 thông báo hết hàng; tồn kho 10 có đúng 10 thành công; tồn kho 3 với quantity 2 chỉ có 1 thành công, còn 1 món. Đối chiếu cả `stock` và tổng `sold` đã ghi trong DB, xác nhận Redis không còn key lock sau xử lý.
- Kiểm tra quantity 0 trả 400, yêu cầu hợp lệ sau đó vẫn chạy, sản phẩm thiếu trả 404. Script dừng hai JVM sau khi chạy; dữ liệu sản phẩm kiểm thử được giữ lại trong DB local để xem xét.

## Phạm vi

Demo dùng một Redis standalone; không tuyên bố bảo đảm khóa tuyệt đối khi Redis failover hoặc network partition. SQL có điều kiện giữ tồn kho không âm trong các trường hợp khóa mất hiệu lực. API tạo sản phẩm dùng để chuẩn bị dữ liệu thực hành, chưa có xác thực quản trị. API mua chưa có idempotency key: client cần tránh tự retry yêu cầu có kết quả không rõ ràng để không mua lặp khi sản phẩm vẫn còn hàng.

Tham khảo: [Redisson — Locks and synchronizers](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/).
