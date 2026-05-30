package com.zdsj.user

import com.zdsj.config.JwtProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(private val props: JwtProperties) {

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(props.secret.toByteArray(Charsets.UTF_8))
    }

    fun issue(userId: Long, openid: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("openid", openid)
            .issuedAt(Date(now))
            .expiration(Date(now + props.ttlSeconds * 1000))
            .signWith(key)
            .compact()
    }

    /** 校验并返回 userId，失败返回 null */
    fun verify(token: String): Long? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload
        claims.subject.toLong()
    }.getOrNull()
}
