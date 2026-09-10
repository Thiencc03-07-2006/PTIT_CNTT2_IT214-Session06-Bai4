package com.vietmart.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record ProductInfo(
        Long id,

        @JsonAlias({"name", "productName"})
        String name,

        Long price
) {
}