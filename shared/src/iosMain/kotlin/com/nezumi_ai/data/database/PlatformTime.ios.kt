package com.nezumi_ai.data.database

import platform.Foundation.NSProcessInfo

actual fun availableProcessorCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()
