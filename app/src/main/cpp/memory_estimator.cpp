/**
 * memory_estimator.cpp
 *
 * GGUF モデルを llama_model_load() でロードする *前* に、
 * 必要メモリ量を見積もるための JNI ブリッジ。
 *
 * LlamaBridge.kt の nativeEstimateMemoryUsage() と対応する。
 *
 * 設計方針:
 *   - gguf_init_from_file() を no_alloc=true で呼び、テンソルデータを
 *     実際にはロードしないままヘッダーとテンソルメタ情報だけを読む。
 *     これにより「モデルをロードせずに」ファイルサイズ相当のメモリ量を
 *     正確に算出できる（量子化後の実サイズがそのままテンソルサイズの合計になる）。
 *   - n_gpu_layers 指定を反映し、CPU 側に残る層と GPU にオフロードされる層
 *     でテンソル合計を按分する（llama.cpp の一般的なオフロード単位である
 *     「層 (block) ごと」の粒度で近似する）。
 *   - KV キャッシュサイズは GGUF のアーキテクチャ関連メタデータ
 *     (context_length, block_count, attention.head_count_kv,
 *      attention.key_length, attention.value_length) から
 *     llama.cpp の実装と同じ式で計算する:
 *       kv_size = n_ctx * n_layer * (n_embd_k_gqa + n_embd_v_gqa) * kv_type_bytes
 *   - 計算バッファ (compute buffer) は正確な値を事前計算するのが困難なため、
 *     llama.cpp 実運用でよく使われる経験的な概算値を使用する（詳細はコメント参照）。
 *
 * 戻り値は JSON 文字列（nlohmann::json）。呼び出し側 (Kotlin) でパースする。
 */

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "gguf.h"
#include "ggml.h"
#include "json.hpp"

#define LOG_TAG "memory_estimator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

namespace
{

    // KV キャッシュの型ごとのバイト数（llama.cpp の型名に準拠）。
    // "f16" 以外は将来の拡張用。現状 llama_bridge が使う既定は f16。
    size_t kv_cache_type_bytes(const std::string &type)
    {
        if (type == "f32")
            return 4;
        if (type == "q8_0")
            return 1; // ブロック単位の近似（厳密には ggml_type_size/ggml_blck_size が必要）
        if (type == "q4_0")
            return 1; // 同上（概算値）
        // 既定: f16
        return 2;
    }

    struct GgufMeta
    {
        std::string architecture;
        uint32_t context_length = 0;
        uint32_t block_count = 0;
        uint32_t embedding_length = 0;
        uint32_t attention_head_count = 0;
        uint32_t attention_head_count_kv = 0;
        uint32_t attention_key_length = 0;
        uint32_t attention_value_length = 0;
        bool has_key_value_length = false;
    };

    // gguf_context から必要なアーキテクチャメタデータを読み取る。
    // キーは "<architecture>.<field>" の形式（llama.cpp の慣例）。
    GgufMeta read_gguf_meta(struct gguf_context *ctx)
    {
        GgufMeta meta;

        int64_t arch_id = gguf_find_key(ctx, "general.architecture");
        if (arch_id >= 0 && gguf_get_kv_type(ctx, arch_id) == GGUF_TYPE_STRING)
        {
            meta.architecture = gguf_get_val_str(ctx, arch_id);
        }

        auto get_u32 = [&](const std::string &suffix, uint32_t &out) -> bool
        {
            if (meta.architecture.empty())
                return false;
            std::string key = meta.architecture + "." + suffix;
            int64_t id = gguf_find_key(ctx, key.c_str());
            if (id < 0)
                return false;
            switch (gguf_get_kv_type(ctx, id))
            {
            case GGUF_TYPE_UINT32:
                out = gguf_get_val_u32(ctx, id);
                return true;
            case GGUF_TYPE_INT32:
                out = static_cast<uint32_t>(gguf_get_val_i32(ctx, id));
                return true;
            case GGUF_TYPE_UINT64:
                out = static_cast<uint32_t>(gguf_get_val_u64(ctx, id));
                return true;
            default:
                return false;
            }
        };

        get_u32("context_length", meta.context_length);
        get_u32("block_count", meta.block_count);
        get_u32("embedding_length", meta.embedding_length);
        get_u32("attention.head_count", meta.attention_head_count);
        get_u32("attention.head_count_kv", meta.attention_head_count_kv);

        meta.has_key_value_length =
            get_u32("attention.key_length", meta.attention_key_length);
        bool has_value_length =
            get_u32("attention.value_length", meta.attention_value_length);
        meta.has_key_value_length = meta.has_key_value_length && has_value_length;

        // head_count_kv 未指定モデルは head_count と同数（MHA、GQAでない）
        if (meta.attention_head_count_kv == 0)
            meta.attention_head_count_kv = meta.attention_head_count;

        // key/value_length 未指定モデルは embedding_length / head_count から算出
        if (!meta.has_key_value_length && meta.attention_head_count > 0)
        {
            uint32_t head_dim = meta.embedding_length / std::max(1u, meta.attention_head_count);
            meta.attention_key_length = head_dim;
            meta.attention_value_length = head_dim;
        }

        return meta;
    }

    struct TensorInfo
    {
        std::string name;
        size_t nbytes = 0;
        int block_index = -1; // "blk.N." プレフィックスから抽出。属さない場合 -1
    };

    // テンソル名 "blk.12.attn_q.weight" から層番号 12 を抽出する。
    // 属さないテンソル（トークン埋め込み、出力層、norm 等）は -1 のまま。
    int extract_block_index(const std::string &name)
    {
        const std::string prefix = "blk.";
        if (name.rfind(prefix, 0) != 0)
            return -1;
        size_t start = prefix.size();
        size_t end = name.find('.', start);
        if (end == std::string::npos)
            return -1;
        try
        {
            return std::stoi(name.substr(start, end - start));
        }
        catch (...)
        {
            return -1;
        }
    }

    struct EstimateResult
    {
        bool ok = false;
        std::string error;

        std::string architecture;
        uint64_t n_params = 0;

        uint64_t file_size_bytes = 0; // gguf 上の全テンソル合計（mmap 時に実質使うサイズ）

        uint64_t weights_cpu_bytes = 0; // CPU に残る層の重み
        uint64_t weights_gpu_bytes = 0; // GPU にオフロードされる層の重み
        int n_layer_total = 0;
        int n_layer_offloaded = 0;

        uint64_t kv_cache_bytes = 0;
        uint32_t n_ctx_used = 0;

        uint64_t compute_buffer_bytes = 0;

        uint64_t total_ram_bytes = 0; // mmap 想定: weights_cpu + kv(CPU分) + compute + overhead
        uint64_t total_vram_bytes = 0; // weights_gpu + kv(GPU分) + compute(GPU分)
        uint64_t total_bytes_no_gpu = 0; // GPU オフロードなし時の合計（参考値）
    };

    // メイン見積もりロジック。
    EstimateResult estimate(
        const std::string &model_path,
        int32_t n_ctx,
        int32_t n_gpu_layers,
        int32_t n_batch,
        const std::string &kv_cache_type)
    {
        EstimateResult result;

        struct gguf_init_params params = {};
        params.no_alloc = true; // テンソルデータは読み込まない（ヘッダー + メタのみ）
        params.ctx = nullptr;

        struct gguf_context *ctx = gguf_init_from_file(model_path.c_str(), params);
        if (!ctx)
        {
            result.error = "gguf_init_from_file failed (invalid or unreadable GGUF file)";
            return result;
        }

        GgufMeta meta = read_gguf_meta(ctx);
        result.architecture = meta.architecture.empty() ? "unknown" : meta.architecture;

        // ── テンソルサイズの走査 ────────────────────────────────
        int64_t n_tensors = gguf_get_n_tensors(ctx);
        std::vector<TensorInfo> tensors;
        tensors.reserve(static_cast<size_t>(n_tensors));

        uint64_t total_elements = 0;

        for (int64_t i = 0; i < n_tensors; ++i)
        {
            const char *name = gguf_get_tensor_name(ctx, i);
            enum ggml_type type = gguf_get_tensor_type(ctx, i);
            const int64_t *ne = gguf_get_tensor_ne(ctx, i);
            size_t nbytes = gguf_get_tensor_size(ctx, i);

            TensorInfo info;
            info.name = name ? name : "";
            info.nbytes = nbytes;
            info.block_index = extract_block_index(info.name);
            tensors.push_back(info);

            result.file_size_bytes += nbytes;

            // パラメータ数の概算（量子化タイプに関わらず要素数を積算）
            int64_t elems = 1;
            for (int d = 0; d < GGML_MAX_DIMS; ++d)
                elems *= (ne[d] > 0 ? ne[d] : 1);
            total_elements += static_cast<uint64_t>(elems);
            (void)type;
        }

        result.n_params = total_elements;

        // ── 層ごとのオフロード按分 ──────────────────────────────
        int n_layer_total = static_cast<int>(meta.block_count);
        result.n_layer_total = n_layer_total;

        int n_layer_offloaded = 0;
        if (n_gpu_layers < 0)
        {
            // 負値 = 全層オフロード（llama.cpp の慣例: -1 は "全部" の意）
            n_layer_offloaded = n_layer_total;
        }
        else
        {
            n_layer_offloaded = std::min(n_gpu_layers, n_layer_total);
        }
        result.n_layer_offloaded = n_layer_offloaded;

        for (const auto &t : tensors)
        {
            bool is_offloaded = false;
            if (t.block_index >= 0)
            {
                // block_index は 0 始まりなので block_index < n_layer_offloaded ならGPU側
                is_offloaded = t.block_index < n_layer_offloaded;
            }
            // blk.* に属さないテンソル（埋め込み/出力層/norm）は
            // n_gpu_layers 指定が全層カバーしている場合のみCPU->GPUの対象とする
            // （llama.cpp の "-ngl 99" 全オフロード運用に合わせた簡易近似）。
            else if (n_gpu_layers < 0 || n_gpu_layers >= n_layer_total)
            {
                is_offloaded = true;
            }

            if (is_offloaded)
                result.weights_gpu_bytes += t.nbytes;
            else
                result.weights_cpu_bytes += t.nbytes;
        }

        // ── KV キャッシュサイズの計算 ───────────────────────────
        // kv_size = n_ctx * n_layer * (n_embd_k_gqa + n_embd_v_gqa) * type_bytes
        uint32_t n_ctx_used = n_ctx > 0 ? static_cast<uint32_t>(n_ctx)
                                         : (meta.context_length > 0 ? meta.context_length : 4096u);
        result.n_ctx_used = n_ctx_used;

        uint64_t n_embd_k_gqa =
            static_cast<uint64_t>(meta.attention_key_length) * meta.attention_head_count_kv;
        uint64_t n_embd_v_gqa =
            static_cast<uint64_t>(meta.attention_value_length) * meta.attention_head_count_kv;

        size_t type_bytes = kv_cache_type_bytes(kv_cache_type);

        result.kv_cache_bytes =
            static_cast<uint64_t>(n_ctx_used) *
            static_cast<uint64_t>(n_layer_total) *
            (n_embd_k_gqa + n_embd_v_gqa) *
            type_bytes;

        // KV キャッシュも層ごとにCPU/GPUへ按分（オフロード層数の比率で近似）
        uint64_t kv_gpu_bytes = 0;
        uint64_t kv_cpu_bytes = result.kv_cache_bytes;
        if (n_layer_total > 0)
        {
            kv_gpu_bytes = result.kv_cache_bytes *
                            static_cast<uint64_t>(n_layer_offloaded) /
                            static_cast<uint64_t>(n_layer_total);
            kv_cpu_bytes = result.kv_cache_bytes - kv_gpu_bytes;
        }

        // ── 計算バッファ (compute buffer) の概算 ───────────────
        // 正確な値は動的グラフ構築に依存し事前計算が難しいため、
        // llama.cpp 実運用でよく観測される経験則を採用する:
        //   compute_buffer ≈ n_batch * n_embd * 数バイト * 定数係数
        // ここでは 「n_batch * embedding_length * 4 byte(f32) * layer安全係数」
        // を基準に、最低 32MB を確保する形の概算とする。
        uint32_t n_batch_used = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 512u;
        uint64_t compute_estimate =
            static_cast<uint64_t>(n_batch_used) *
            static_cast<uint64_t>(meta.embedding_length) *
            4 /* f32 */ *
            4 /* 中間バッファの安全係数（attention/ffn 中間テンソル分） */;
        result.compute_buffer_bytes = std::max<uint64_t>(compute_estimate, 32ull * 1024 * 1024);

        // ── 合計値 ───────────────────────────────────────────
        // オーバーヘッド: llama.cpp のコンテキスト管理構造体など（経験的に数十MB）
        const uint64_t kFixedOverheadBytes = 64ull * 1024 * 1024;

        result.total_ram_bytes =
            result.weights_cpu_bytes + kv_cpu_bytes + result.compute_buffer_bytes + kFixedOverheadBytes;
        result.total_vram_bytes =
            result.weights_gpu_bytes + kv_gpu_bytes + (n_layer_offloaded > 0 ? result.compute_buffer_bytes : 0);
        result.total_bytes_no_gpu =
            result.file_size_bytes + result.kv_cache_bytes + result.compute_buffer_bytes + kFixedOverheadBytes;

        result.ok = true;

        gguf_free(ctx);
        return result;
    }

    json to_json(const EstimateResult &r)
    {
        json j;
        j["ok"] = r.ok;
        if (!r.ok)
        {
            j["error"] = r.error;
            return j;
        }

        j["architecture"] = r.architecture;
        j["n_params"] = r.n_params;
        j["file_size_bytes"] = r.file_size_bytes;

        j["n_layer_total"] = r.n_layer_total;
        j["n_layer_offloaded"] = r.n_layer_offloaded;

        j["weights_cpu_bytes"] = r.weights_cpu_bytes;
        j["weights_gpu_bytes"] = r.weights_gpu_bytes;

        j["n_ctx_used"] = r.n_ctx_used;
        j["kv_cache_bytes"] = r.kv_cache_bytes;

        j["compute_buffer_bytes"] = r.compute_buffer_bytes;

        j["total_ram_bytes"] = r.total_ram_bytes;
        j["total_vram_bytes"] = r.total_vram_bytes;
        j["total_bytes_no_gpu"] = r.total_bytes_no_gpu;

        return j;
    }

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeEstimateMemoryUsage(
    JNIEnv *env,
    jobject /* obj */,
    jstring j_model_path,
    jint n_ctx,
    jint n_gpu_layers,
    jint n_batch,
    jstring j_kv_cache_type)
{
    if (!j_model_path)
    {
        json err = {{"ok", false}, {"error", "model_path is null"}};
        return env->NewStringUTF(err.dump().c_str());
    }

    const char *path_chars = env->GetStringUTFChars(j_model_path, nullptr);
    std::string model_path(path_chars);
    env->ReleaseStringUTFChars(j_model_path, path_chars);

    std::string kv_cache_type = "f16";
    if (j_kv_cache_type)
    {
        const char *kv_chars = env->GetStringUTFChars(j_kv_cache_type, nullptr);
        kv_cache_type = kv_chars;
        env->ReleaseStringUTFChars(j_kv_cache_type, kv_chars);
    }

    EstimateResult result = estimate(
        model_path,
        static_cast<int32_t>(n_ctx),
        static_cast<int32_t>(n_gpu_layers),
        static_cast<int32_t>(n_batch),
        kv_cache_type);

    if (!result.ok)
    {
        LOGE("nativeEstimateMemoryUsage failed: %s", result.error.c_str());
    }
    else
    {
        LOGI("nativeEstimateMemoryUsage: arch=%s params=%llu file=%lluMB ram=%lluMB vram=%lluMB",
             result.architecture.c_str(),
             static_cast<unsigned long long>(result.n_params),
             static_cast<unsigned long long>(result.file_size_bytes / (1024 * 1024)),
             static_cast<unsigned long long>(result.total_ram_bytes / (1024 * 1024)),
             static_cast<unsigned long long>(result.total_vram_bytes / (1024 * 1024)));
    }

    json j = to_json(result);
    std::string dumped = j.dump();

    // dump() の結果はASCII安全なUTF-8なので NewStringUTF で問題ない
    // （architecture名などに非ASCIIが混入する可能性は低いが、
    //  安全のため env->NewStringUTF はUTF-8前提で動作する）
    return env->NewStringUTF(dumped.c_str());
}
