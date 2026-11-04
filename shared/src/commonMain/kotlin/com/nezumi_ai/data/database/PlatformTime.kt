package com.nezumi_ai.data.database

import kotlin.time.Clock

/** 現在時刻（ミリ秒、UNIX エポック基準）。標準ライブラリの Clock を使うため全ターゲット共通。 */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 利用可能な論理プロセッサ数。 */
expect fun availableProcessorCount(): Int
