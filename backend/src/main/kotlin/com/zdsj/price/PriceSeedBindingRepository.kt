package com.zdsj.price

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface PriceSeedBindingRepository : JpaRepository<PriceSeedBinding, Long> {
    fun findBySeedNameAndPlatform(seedName: String, platform: String): Optional<PriceSeedBinding>
}
