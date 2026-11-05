package com.nezumi_ai.data.database

/**
 * DB ファイルの保存方針 (Phase 2)。
 *
 * - ファイル名は全プラットフォームで `nezumi_ai.db` を維持する。
 * - Android: 従来どおり Context の内部ストレージ (Room.databaseBuilder の既定)。
 *   既存ユーザーの DB を引き継ぐためパスは変更しない。
 * - iOS: アプリコンテナ内の Documents または Application Support 配下に同名ファイルを置く。
 *   初回起動時は空 DB を作成し、マイグレーションは Room の共通 Migration 群を適用する。
 *
 * スキーマバージョンは現在 37。Entity / DAO は commonMain 済み。
 * Database 抽象クラスと Migration の commonMain 移設は次のステップ。
 */
object DatabaseLocationPolicy {
    const val FILE_NAME = "nezumi_ai.db"
    const val SCHEMA_VERSION = 37
}
