package com.nezumi_ai.data.database

actual fun availableProcessorCount(): Int = Runtime.getRuntime().availableProcessors()
