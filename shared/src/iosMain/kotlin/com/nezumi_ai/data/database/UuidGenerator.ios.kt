package com.nezumi_ai.data.database

import platform.Foundation.NSUUID

actual fun randomUuidString(): String = NSUUID().UUIDString()
