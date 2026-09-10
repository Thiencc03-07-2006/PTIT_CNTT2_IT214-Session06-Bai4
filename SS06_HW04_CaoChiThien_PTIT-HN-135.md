# Bài tập 3: Khắc phục Cascading Failure do thiếu Timeout trong RestTemplate

**Mã bài:** SPRING-CLOUD-S06-EX03
**Cấp độ:** Vận dụng chuyên sâu
**Hệ thống:** VietMart
**Service:** inventory-service

---

## 1. Mục tiêu

Bài tập nhằm phân tích và khắc phục hiện tượng **cascading failure** xảy ra khi `inventory-service` gọi `product-service` bằng `RestTemplate` nhưng không cấu hình timeout.

Các mục tiêu chính:

* Phân tích nguyên nhân gây cascading failure.
* Xác định các lỗi trong implementation ban đầu.
* Cấu hình `@LoadBalanced RestTemplate`.
* Sử dụng service-id thay cho IP hardcode.
* Thiết lập `connectTimeout = 1s`.
* Thiết lập `readTimeout = 2s`.
* Xử lý `ResourceAccessException`.
* Trả về fallback `StockInfo.unavailable()`.
* Sử dụng WireMock để mô phỏng `product-service` phản hồi chậm 5 giây.
* Chứng minh request được timeout và fallback trong dưới 3 giây.
* Đề xuất Circuit Breaker để tăng khả năng chống cascading failure.

---

# 2. Phân tích vấn đề ban đầu

Implementation ban đầu của `StockCheckClient` có dạng:

```java
@Component
public class StockCheckClient {

    @Autowired
    private RestTemplate restTemplate;

    public StockInfo checkStock(Long productId) {
        return restTemplate.getForObject(
                "http://192.168.0.12:8082/api/stock/{pid}",
                StockInfo.class,
                productId
        );
    }
}
```

Implementation này có nhiều vấn đề.

## 2.1. Không cấu hình timeout

`RestTemplate` không có `connectTimeout` và `readTimeout` phù hợp.

Khi `product-service` phản hồi chậm hoặc bị treo, request có thể giữ thread trong thời gian dài.

Ví dụ:

```text
Inventory Service
       |
       | HTTP request
       v
Product Service
       |
       | không phản hồi
       |
       | 30 giây
       v
Inventory thread bị block
```

Nếu có nhiều request đồng thời, số lượng thread bị block sẽ tăng nhanh.

---

## 2.2. Không sử dụng `@LoadBalanced`

`RestTemplate` ban đầu không được đánh dấu:

```java
@LoadBalanced
```

Do đó không thể sử dụng service discovery để resolve:

```text
http://product-service/...
```

Một RestTemplate thông thường chỉ xử lý URL mà HTTP client có thể resolve trực tiếp.

---

## 2.3. Hardcode IP và port

Code sử dụng:

```text
http://192.168.0.12:8082
```

Đây là một vấn đề trong hệ thống microservices vì:

* IP có thể thay đổi.
* Port có thể thay đổi.
* Có thể có nhiều instance của `product-service`.
* Không tận dụng được Eureka/Service Discovery.
* Khó triển khai trên Docker, Kubernetes hoặc môi trường cloud.

Giải pháp là sử dụng service-id:

```text
http://product-service/api/stock/{pid}
```

và để Service Discovery tìm instance phù hợp.

---

## 2.4. Không có fallback

Nếu `product-service` timeout hoặc không thể kết nối, exception sẽ được truyền ngược lên caller.

Implementation cần xử lý:

```java
ResourceAccessException
```

và trả về:

```java
StockInfo.unavailable()
```

---

# 3. Cơ chế Cascading Failure

Cascading failure xảy ra khi một service bị lỗi hoặc chậm làm các service phụ thuộc vào nó cũng bị ảnh hưởng theo.

Trong hệ thống VietMart, luồng lỗi có thể xảy ra như sau:

```text
Product Service chậm
        |
        v
Inventory Service gọi Product Service
        |
        v
Không có timeout
        |
        v
Inventory thread bị block
        |
        v
Thread pool dần cạn
        |
        v
Request mới phải chờ
        |
        v
Response time tăng
        |
        v
Inventory Service quá tải
        |
        v
Inventory Service có thể timeout/crash
        |
        v
Order Service gọi Inventory Service
        |
        v
Order Service cũng bị chậm
        |
        v
Cascading Failure
```

## 3.1. Ví dụ tải cao điểm

Giả sử:

```text
50 request/giây
```

và mỗi request bị block khoảng:

```text
30 giây
```

Số lượng thread-second bị tiêu tốn có thể lên tới:

```text
50 × 30 = 1500 thread-seconds
```

Nếu thread pool có giới hạn, các request mới sẽ không còn thread để xử lý.

Kết quả:

* Response time tăng.
* Request timeout.
* Connection pool bị ảnh hưởng.
* CPU/memory có thể tăng.
* Service có thể trở nên không phản hồi.
* Lỗi lan sang service gọi nó.

---

# 4. Giải pháp

Giải pháp được áp dụng gồm bốn thành phần:

1. `@LoadBalanced RestTemplate`.
2. `connectTimeout = 1 giây`.
3. `readTimeout = 2 giây`.
4. Fallback khi xảy ra `ResourceAccessException`.

Luồng sau khi sửa:

```text
Order Service
      |
      v
Inventory Service
      |
      v
@LoadBalanced RestTemplate
      |
      v
Product Service
      |
      | không phản hồi
      |
      | tối đa khoảng 2 giây
      v
Read Timeout
      |
      v
ResourceAccessException
      |
      v
StockInfo.unavailable()
      |
      v
Inventory Service trả response
```

Nhờ timeout, thread không bị giữ trong thời gian không xác định.

---

# 5. RestTemplate Configuration

`RestTemplate` được cấu hình với:

```text
connectTimeout = 1000 ms
readTimeout    = 2000 ms
```

và sử dụng:

```java
@LoadBalanced
```

Mục đích:

* `connectTimeout`: giới hạn thời gian thiết lập kết nối.
* `readTimeout`: giới hạn thời gian chờ dữ liệu phản hồi.
* `@LoadBalanced`: cho phép sử dụng service-id thông qua Service Discovery.

---

# 6. StockCheckClient sau khi sửa

Client sử dụng service-id:

```text
http://product-service/api/stock/{pid}
```

thay vì IP hardcode:

```text
http://192.168.0.12:8082/api/stock/{pid}
```

Khi request gặp lỗi kết nối hoặc timeout, `ResourceAccessException` được xử lý:

```java
catch (ResourceAccessException e) {
    return StockInfo.unavailable();
}
```

Fallback được định nghĩa trong `StockInfo`:

```java
public static StockInfo unavailable() {
    return new StockInfo(
            null,
            false,
            0,
            "UNAVAILABLE"
    );
}
```

Điều này giúp `inventory-service` vẫn trả về một kết quả có ý nghĩa thay vì để exception tiếp tục lan truyền.

---

# 7. Mô phỏng Product Service chậm

Để kiểm chứng timeout, bài sử dụng **WireMock**.

WireMock được chạy tại:

```text
http://localhost:8089
```

Endpoint giả lập:

```text
GET /api/stock/100
```

được cấu hình cố tình delay:

```text
5000 ms
```

Tức là Product Service giả lập sẽ mất 5 giây mới trả response.

Response giả lập:

```json
{
  "productId": 100,
  "available": true,
  "quantity": 50,
  "status": "AVAILABLE"
}
```

---

# 8. Integration Test

Integration test sử dụng:

```java
@SpringBootTest
```

và cấu hình Simple Discovery:

```properties
eureka.client.enabled=false
spring.cloud.discovery.client.simple.instances.product-service[0].uri=http://localhost:8089
```

Điều này cho phép test sử dụng:

```text
product-service
```

nhưng thực tế request được chuyển tới:

```text
localhost:8089
```

WireMock đóng vai trò Product Service giả lập.

Test gọi trực tiếp implementation thật:

```java
StockInfo result = stockCheckClient.checkStock(100L);
```

Không mock `StockCheckClient`.

---

# 9. Kết quả mong đợi

WireMock được cấu hình delay:

```text
5000 ms
```

Trong khi RestTemplate có:

```text
Connect Timeout = 1000 ms
Read Timeout    = 2000 ms
```

Do đó request không được phép chờ đủ 5 giây.

Khi read timeout xảy ra:

```text
ResourceAccessException
        |
        v
StockInfo.unavailable()
```

Test kiểm tra:

```java
assertNotNull(result);

assertEquals(
        "UNAVAILABLE",
        result.getStatus()
);

assertFalse(
        result.isAvailable()
);

assertEquals(
        0,
        result.getQuantity()
);

assertTrue(
        elapsedMilliseconds < 3000
);
```

---

# 10. Kết quả thực nghiệm

Kết quả test dự kiến:

```text
========================================
CASCADING FAILURE TIMEOUT TEST
========================================
WireMock delay : 5000 ms
Connect timeout: 1000 ms
Read timeout   : 2000 ms
Actual time    : ~2000 ms
Result         : StockInfo{
    productId=null,
    available=false,
    quantity=0,
    status='UNAVAILABLE'
}
========================================

BUILD SUCCESSFUL
```

Điểm quan trọng:

```text
WireMock delay = 5000 ms
Actual response < 3000 ms
```

Điều này chứng minh:

> Inventory Service không chờ Product Service đủ 5 giây mà chủ động timeout và trả fallback.

---

# 11. Ý nghĩa của Integration Test

Test không chỉ kiểm tra kết quả fallback.

Nó kiểm tra toàn bộ luồng:

```text
StockCheckClient
      |
      v
@LoadBalanced RestTemplate
      |
      v
Simple Discovery
      |
      v
product-service
      |
      v
WireMock
      |
      | delay 5 giây
      v
Read Timeout
      |
      v
ResourceAccessException
      |
      v
StockInfo.unavailable()
```

Ngoài ra test xác nhận WireMock thực sự nhận request bằng:

```java
wireMockServer.verify(
        1,
        getRequestedFor(
                urlPathEqualTo("/api/stock/100")
        )
);
```

Do đó fallback không phải do test tự tạo ra mà là kết quả của việc timeout trong client.

---

# 12. Tại sao Timeout giúp giảm Cascading Failure?

Không có timeout:

```text
Product Service
      |
      | treo
      v
Inventory thread
      |
      | block lâu
      v
Thread pool cạn
```

Có timeout:

```text
Product Service
      |
      | treo
      v
Inventory thread
      |
      | tối đa khoảng 2s
      v
Timeout
      |
      v
Fallback
      |
      v
Thread được giải phóng
```

Timeout biến một dependency failure kéo dài thành một lỗi được giới hạn về thời gian.

Tuy nhiên, timeout **không hoàn toàn loại bỏ cascading failure**.

---

# 13. Sau khi có Timeout, Order Service còn có thể bị ảnh hưởng không?

**Có.**

Timeout chỉ giới hạn thời gian mỗi request gọi `product-service`.

Nếu `inventory-service` vẫn nhận một lượng request cực lớn, nó vẫn có thể bị quá tải.

Ví dụ:

```text
1000 request/giây
       |
       v
Inventory Service
       |
       +----> Product Service
       |
       +----> Product Service
       |
       +----> Product Service
       |
       v
Nhiều request timeout
       |
       v
CPU / thread / connection pool tăng tải
       |
       v
Inventory Service quá tải
       |
       v
Order Service tiếp tục bị ảnh hưởng
```

Vì vậy timeout là cần thiết nhưng chưa đủ.

---

# 14. Biện pháp bổ sung: Circuit Breaker

Biện pháp được đề xuất là sử dụng **Circuit Breaker**, ví dụ với Resilience4j.

Circuit Breaker theo dõi tỷ lệ lỗi và timeout của các request tới `product-service`.

Khi số lượng lỗi vượt ngưỡng:

```text
CLOSED
   |
   | nhiều timeout/failure
   v
OPEN
```

Ở trạng thái `OPEN`, các request mới không tiếp tục gọi Product Service mà trả fallback ngay.

Sau một khoảng thời gian:

```text
OPEN
  |
  | wait duration
  v
HALF_OPEN
  |
  | thử một số request
  v
+------------------+
|                  |
v                  v
SUCCESS          FAILURE
  |                  |
  v                  v
CLOSED             OPEN
```

## Lợi ích

Circuit Breaker giúp:

* Không tiếp tục gửi hàng nghìn request tới service đang lỗi.
* Giảm tải cho Product Service.
* Giảm số lượng thread bị giữ tại Inventory Service.
* Fallback nhanh hơn.
* Ngăn lỗi lan sang Order Service.
* Tăng khả năng phục hồi của hệ thống microservices.

Có thể kết hợp:

```text
Timeout
   +
Circuit Breaker
   +
Fallback
```

để tạo cơ chế bảo vệ nhiều lớp.

---

# 15. So sánh trước và sau khi sửa

| Tiêu chí                | Trước khi sửa  | Sau khi sửa               |
| ----------------------- | -------------- | ------------------------- |
| Timeout                 | Không có       | Có                        |
| Connect timeout         | Không cấu hình | 1 giây                    |
| Read timeout            | Không cấu hình | 2 giây                    |
| Service ID              | Không          | Có                        |
| Hardcode IP             | Có             | Không                     |
| Load balancing          | Không          | `@LoadBalanced`           |
| Exception handling      | Không          | `ResourceAccessException` |
| Fallback                | Không          | `StockInfo.unavailable()` |
| Dependency chậm 5s      | Có thể chờ lâu | Timeout khoảng 2s         |
| Thread bị block lâu     | Có             | Được giải phóng sớm       |
| Chống cascading failure | Kém            | Tốt hơn                   |
| Circuit Breaker         | Không          | Đề xuất bổ sung           |

---

# 16. Kết luận

Bài tập đã xác định nguyên nhân chính gây cascading failure là việc sử dụng `RestTemplate` không có timeout khi gọi dependency.

Giải pháp đã triển khai:

```text
@LoadBalanced RestTemplate
        +
Connect Timeout = 1s
        +
Read Timeout = 2s
        +
ResourceAccessException handling
        +
StockInfo.unavailable()
```

Integration Test sử dụng WireMock mô phỏng Product Service delay 5 giây.

Kết quả mong đợi:

```text
Server delay = 5 giây
Response      < 3 giây
Fallback      = UNAVAILABLE
```

Điều này chứng minh timeout hoạt động và Inventory Service không bị giữ thread chờ dependency trong thời gian 5 giây.

Để tăng khả năng chống cascading failure trong môi trường production, có thể kết hợp thêm **Circuit Breaker với Resilience4j**, giúp tạm thời ngắt các request tới dependency đang lỗi và trả fallback ngay.

---