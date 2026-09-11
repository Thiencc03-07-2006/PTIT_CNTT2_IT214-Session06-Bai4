# BÀI TẬP 4: PHÂN TÍCH HỢP ĐỒNG API VÀ TÁC ĐỘNG KHI FEIGNCLIENT INTERFACE THAY ĐỔI

**Mã bài toán:** SPRING-CLOUD-S05-EX04
**Chủ đề:** API Contract, FeignClient và API Versioning
**Ngôn ngữ:** Java 21
**Build Tool:** Gradle

---

## 1. Mục tiêu

Bài tập nhằm phân tích tác động của việc thay đổi API contract giữa `product-service` và các service sử dụng FeignClient.

Các nội dung chính:

* Phân tích tác động khi đổi tên field JSON từ `name` thành `productName`.
* Phân tích tác động khi thay đổi API path.
* Hiểu sự phụ thuộc của FeignClient vào API contract.
* Đề xuất chiến lược versioning API.
* Thiết kế DTO phía client có khả năng tương thích với cả API cũ và API mới.
* Kiểm thử khả năng deserialize cả hai định dạng JSON.

---

# 2. Kiến trúc và API Contract

Giả sử hệ thống gồm:

```text
                         +------------------+
                         |  product-service |
                         +---------+--------+
                                   |
                  +----------------+----------------+
                  |                |                |
                  v                v                v
           order-service   inventory-service   report-service
                  |                |                |
                  +------- FeignClient ------------+
```

`product-service` cung cấp thông tin sản phẩm.

API cũ:

```http
GET /api/products/{id}
```

Response:

```json
{
    "id": 100,
    "name": "Laptop Dell",
    "price": 25000000
}
```

---

# 3. Phân tích thay đổi JSON field

## 3.1. API contract cũ

API trả về:

```json
{
    "id": 100,
    "name": "Laptop Dell",
    "price": 25000000
}
```

Client sử dụng DTO:

```java
public record ProductInfo(
        Long id,
        String name,
        Long price
) {
}
```

Khi đó Jackson ánh xạ:

```text
JSON name
    ↓
ProductInfo.name
```

Dữ liệu nhận được:

```text
id    = 100
name  = "Laptop Dell"
price = 25000000
```

---

## 3.2. API thay đổi `name` thành `productName`

Nếu `product-service` thay đổi response thành:

```json
{
    "id": 100,
    "productName": "Laptop Dell",
    "price": 25000000
}
```

nhưng client vẫn sử dụng:

```java
public record ProductInfo(
        Long id,
        String name,
        Long price
) {
}
```

thì field `name` không còn được tìm thấy trong JSON.

Kết quả thông thường:

```text
ProductInfo.name = null
```

Đây là một **breaking change về schema/contract** đối với client đang phụ thuộc vào field `name`.

---

# 4. Tác động đến các service

## 4.1. order-service

`order-service` có thể sử dụng tên sản phẩm để:

* Hiển thị thông tin đơn hàng.
* Tạo order item.
* Ghi log.
* Gửi thông báo cho khách hàng.

Nếu `name = null`, dữ liệu có thể trở thành:

```text
Product: null
```

Nếu code xử lý không kiểm tra null, có thể xảy ra:

```text
NullPointerException
```

Ngoài ra, nếu hệ thống cho phép lưu dữ liệu null thì order vẫn có thể được tạo nhưng thông tin sản phẩm bị thiếu.

---

## 4.2. inventory-service

`inventory-service` có thể sử dụng thông tin sản phẩm để:

* Hiển thị sản phẩm tồn kho.
* Ghi log.
* Tạo báo cáo tồn kho.
* Kiểm tra thông tin sản phẩm.

Khi `name` trở thành `null`, các chức năng phụ thuộc vào tên sản phẩm có thể hiển thị dữ liệu không chính xác hoặc phát sinh exception nếu không xử lý null.

---

## 4.3. report-service

`report-service` có thể sử dụng:

```text
ProductInfo.name
```

để tạo báo cáo.

Khi field bị đổi tên:

```text
Laptop Dell
```

có thể trở thành:

```text
null
```

trong báo cáo.

Hậu quả:

* Báo cáo thiếu tên sản phẩm.
* Dữ liệu hiển thị không đầy đủ.
* Có thể lỗi khi xử lý chuỗi hoặc template.
* Có thể ảnh hưởng đến dữ liệu tổng hợp.

---

# 5. Phân biệt lỗi field và lỗi endpoint

Hai thay đổi sau có mức độ ảnh hưởng khác nhau.

## 5.1. Đổi tên field

Từ:

```json
"name": "Laptop Dell"
```

thành:

```json
"productName": "Laptop Dell"
```

Thông thường HTTP request vẫn thành công:

```text
HTTP 200 OK
```

nhưng DTO client có thể nhận:

```text
name = null
```

Vì vậy đây là lỗi **schema compatibility**.

---

## 5.2. Đổi API path

API cũ:

```http
GET /api/products/100
```

API mới:

```http
GET /api/v2/products/100
```

Nếu FeignClient vẫn gọi:

```java
@GetMapping("/api/products/{id}")
```

nhưng server chỉ còn:

```http
/api/v2/products/{id}
```

thì request sẽ nhận:

```text
HTTP 404 Not Found
```

FeignClient có thể phát sinh:

```text
FeignException.NotFound
```

Đây là lỗi **endpoint compatibility** và nghiêm trọng hơn việc thiếu một field JSON.

---

# 6. FeignClient hiện tại

Client sử dụng API cũ:

```java
package com.vietmart.client;

import com.vietmart.dto.ProductInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    ProductInfo getById(@PathVariable("id") Long id);
}
```

FeignClient đang phụ thuộc vào hai thành phần của API contract:

```text
1. Endpoint:
   /api/products/{id}

2. Response:
   id
   name
   price
```

Do đó, thay đổi một trong hai thành phần mà không có kế hoạch migration có thể làm client hoạt động sai.

---

# 7. Chiến lược versioning API

## 7.1. Chiến lược 1 – URI Versioning

Giữ API cũ và bổ sung API phiên bản mới.

**API cũ**

```http
GET /api/products/{id}
```
**API mới (V2)**

```http
GET /api/v2/products/{id}
```

Ví dụ:

### API cũ

```json
{
    "id": 100,
    "name": "Laptop Dell",
    "price": 25000000
}
```

### API mới (V2)

```json
{
    "id": 100,
    "productName": "Laptop Dell",
    "price": 25000000
}
```

Trong thời gian migration, product-service có thể hỗ trợ đồng thời:

```text
/api/products/{id}
        ↓
       API cũ

/api/v2/products/{id}
        ↓
       API mới (V2)
```

### Ưu điểm

* Dễ nhận biết version.
* Dễ debug.
* Client có thể migrate từng bước.
* Không cần cập nhật tất cả service cùng lúc.

### Nhược điểm

* Phải duy trì nhiều API version.
* Code server có thể bị duplicate.
* Cần có chính sách deprecation và thời điểm ngừng V1.

---

# 8. Chiến lược 2 – Backward-Compatible API Evolution

Thay vì xóa ngay field `name`, server có thể trả cả hai field trong thời gian migration:

```json
{
    "id": 100,
    "name": "Laptop Dell",
    "productName": "Laptop Dell",
    "price": 25000000
}
```

Các client cũ tiếp tục sử dụng:

```text
name
```

Các client mới chuyển sang:

```text
productName
```

Sau khi tất cả client đã migration thành công, server mới loại bỏ:

```text
name
```

### Ưu điểm

* Không làm client cũ lỗi ngay lập tức.
* Có thể migration từng service.
* Phù hợp với hệ thống có nhiều microservice.
* Hạn chế downtime.

### Nhược điểm

* Response tạm thời chứa dữ liệu duplicate.
* Phải quản lý thời gian deprecation.
* Server phải duy trì compatibility trong một khoảng thời gian.

---

# 9. So sánh hai chiến lược

| Tiêu chí        | URI Versioning                | Backward-Compatible      |
| --------------- | ----------------------------- | ------------------------ |
| Cách thực hiện  | Giữ /api/products/{id} và bổ sung /api/v2/products/{id}         | Giữ API cũ và thêm field |
| Migration       | Theo từng version             | Theo từng client         |
| Breaking change | Được cô lập trong version mới | Hạn chế breaking change  |
| Độ rõ ràng      | Cao                           | Trung bình               |
| Chi phí duy trì | Cao hơn                       | Thấp hơn trong ngắn hạn  |
| Phù hợp         | Thay đổi contract lớn         | Thay đổi nhỏ/additive    |
| Deprecation     | Có                            | Có                       |

Đối với thay đổi `name` → `productName`, backward-compatible evolution là lựa chọn phù hợp nếu chỉ cần thay đổi tên field.

Nếu API V2 có nhiều thay đổi lớn về schema hoặc semantics, URI versioning sẽ rõ ràng và an toàn hơn.

---

# 10. Giải pháp DTO Migration bằng `@JsonAlias`

Client cần nhận được cả:

```text
name
```

và:

```text
productName
```

Có thể sử dụng Jackson `@JsonAlias`.

## `ProductInfo.java`

```java
package com.vietmart.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record ProductInfo(
        Long id,

        @JsonAlias({"name", "productName"})
        String name,

        Long price
) {
}
```

Annotation:

```java
@JsonAlias({"name", "productName"})
```

cho phép field `name` nhận dữ liệu từ cả hai JSON property:

```json
"name": "Laptop Dell"
```

hoặc:

```json
"productName": "Laptop Dell"
```

Trong cả hai trường hợp:

```java
productInfo.name()
```

đều trả về:

```text
Laptop Dell
```

---

# 11. Kiểm thử DTO migration

## `ProductInfoTest.java`

```java
package com.vietmart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vietmart.dto.ProductInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductInfoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeOldResponseFormat() throws Exception {

        String json = """
                {
                    "id": 100,
                    "name": "Laptop Dell",
                    "price": 25000000
                }
                """;

        ProductInfo productInfo =
                objectMapper.readValue(json, ProductInfo.class);

        assertEquals(100L, productInfo.id());
        assertNotNull(productInfo.name());
        assertEquals("Laptop Dell", productInfo.name());
        assertEquals(25000000L, productInfo.price());
    }

    @Test
    void shouldDeserializeNewResponseFormat() throws Exception {

        String json = """
                {
                    "id": 100,
                    "productName": "Laptop Dell",
                    "price": 25000000
                }
                """;

        ProductInfo productInfo =
                objectMapper.readValue(json, ProductInfo.class);

        assertEquals(100L, productInfo.id());
        assertNotNull(productInfo.name());
        assertEquals("Laptop Dell", productInfo.name());
        assertEquals(25000000L, productInfo.price());
    }
}
```

---

# 12. Kết quả mong đợi

Chạy:

```bash
./gradlew test
```

Windows:

```cmd
gradlew.bat test
```

Kết quả mong đợi:

```text
ProductInfoTest > shouldDeserializeOldResponseFormat PASSED

ProductInfoTest > shouldDeserializeNewResponseFormat PASSED

2 tests completed, 0 failed
```

Điều này chứng minh DTO client có thể tương thích với cả:

```text
API V1:
name
```

và:

```text
API V2:
productName
```

mà không làm `ProductInfo.name()` trở thành `null`.

---

# 13. Cấu trúc source code

```text
bai4/
└── product-client-demo/
    ├── build.gradle
    ├── settings.gradle
    └── src/
        ├── main/
        │   └── java/
        │       └── com/
        │           └── vietmart/
        │               ├── client/
        │               │   └── ProductClient.java
        │               └── dto/
        │                   └── ProductInfo.java
        │
        └── test/
            └── java/
                └── com/
                    └── vietmart/
                        └── ProductInfoTest.java
```

---

# 14. Dependency

Các thư viện chính:

```gradle
dependencies {

    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'

    implementation 'com.fasterxml.jackson.core:jackson-databind'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

JUnit 5 được cung cấp thông qua:

```text
spring-boot-starter-test
```

Không cần khai báo JUnit riêng.

---

# 15. Phân tích mức độ ảnh hưởng

Có thể phân loại tác động của API contract như sau:

```text
API Contract
     |
     +---- Endpoint thay đổi
     |        |
     |        +---- HTTP 404
     |        +---- FeignException
     |        +---- Business request thất bại
     |
     +---- JSON field thay đổi
              |
              +---- Field = null
              +---- Dữ liệu không đầy đủ
              +---- Có thể NPE
              +---- Có thể sai business logic
```

Điều này cho thấy FeignClient tạo ra sự phụ thuộc trực tiếp giữa consumer và provider.

---

# 16. Khuyến nghị khi thay đổi API

Khi thay đổi API của `product-service`, cần thực hiện theo quy trình:

```text
1. Xác định breaking change
          ↓
2. Kiểm tra tất cả consumer
          ↓
3. Thiết kế migration strategy
          ↓
4. Implement backward compatibility
          ↓
5. Update từng FeignClient
          ↓
6. Integration testing
          ↓
7. Theo dõi production
          ↓
8. Deprecate API cũ
          ↓
9. Remove API cũ
```

Không nên thay đổi trực tiếp API đang được nhiều service sử dụng mà không có migration plan.

---

# 17. Kết luận

FeignClient giúp các microservice giao tiếp thuận tiện nhưng đồng thời tạo ra sự phụ thuộc vào API contract của service cung cấp.

Hai thay đổi được phân tích:

### Thay đổi field

```text
name → productName
```

có thể khiến client nhận:

```text
name = null
```

và gây lỗi hoặc dữ liệu không chính xác.

### Thay đổi endpoint

```text
/api/products/{id}
        ↓
/api/v2/products/{id}
```

có thể khiến FeignClient cũ nhận:

```text
HTTP 404
```

và phát sinh exception.

Để giảm rủi ro, có thể sử dụng:

1. **URI Versioning** – duy trì V1 và V2 song song.
2. **Backward-Compatible Evolution** – giữ API cũ trong thời gian migration.

Đối với trường hợp đổi tên field `name` thành `productName`, sử dụng:

```java
@JsonAlias({"name", "productName"})
```

giúp client nhận được cả hai định dạng trong giai đoạn migration.

Do đó, việc quản lý API contract và có migration strategy là yếu tố quan trọng để tránh breaking change trong kiến trúc microservices.
