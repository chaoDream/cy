package com.zdsj.catalog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface CatalogBrandRepository : JpaRepository<CatalogBrand, Long> {
    fun findByCanonicalName(canonicalName: String): Optional<CatalogBrand>
    fun findByStatus(status: String): List<CatalogBrand>
}

interface CatalogModelRepository : JpaRepository<CatalogModel, Long> {
    fun findByBrandAndModel(brand: String, model: String): Optional<CatalogModel>
    fun findByBrandAndStatus(brand: String, status: String): List<CatalogModel>
}
