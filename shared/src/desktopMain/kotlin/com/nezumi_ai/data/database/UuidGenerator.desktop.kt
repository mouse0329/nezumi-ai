package com.nezumi_ai.data.database

actual fun randomUuidString(): String = java.util.UUID.randomUUID().toString()
