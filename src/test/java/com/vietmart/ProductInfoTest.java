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
        assertEquals("Laptop Dell", productInfo.name());
        assertEquals(25000000L, productInfo.price());
    }
}