package com.coupon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class CouponApplication

fun main(args: Array<String>) {
    runApplication<CouponApplication>(*args)
}
