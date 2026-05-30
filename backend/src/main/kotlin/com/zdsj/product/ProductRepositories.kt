package com.zdsj.product

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ProductSpuRepository : JpaRepository<ProductSpu, Long>

interface ProductRawRepository : JpaRepository<ProductRaw, Long> {
    fun findByPlatformAndPlatformItemId(platform: String, platformItemId: String): Optional<ProductRaw>
    fun findByTitleContainingIgnoreCase(keyword: String): List<ProductRaw>
}

interface ProductSkuRepository : JpaRepository<ProductSku, Long> {
    fun findByBrandAndModelAndStorage(brand: String, model: String, storage: String?): List<ProductSku>
}

interface ProductMappingRepository : JpaRepository<ProductMapping, Long> {
    fun findByRawProductId(rawProductId: Long): Optional<ProductMapping>
    fun findBySkuId(skuId: Long): List<ProductMapping>
    fun findByReviewStatus(reviewStatus: String): List<ProductMapping>
}

interface PromotionRuleRepository : JpaRepository<PromotionRule, Long> {
    fun findByPlatform(platform: String): List<PromotionRule>
}
