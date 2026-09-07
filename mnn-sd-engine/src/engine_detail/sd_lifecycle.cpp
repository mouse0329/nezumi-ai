#include "engine_detail/sd_lifecycle.h"
#include "engine_detail/sd_log.h"
#include "engine_detail/sd_path.h"
#include "engine_detail/sd_session.h"
#include "engine_internal.h"
#include "mnn_sd/model_config.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <climits>
#include <filesystem>
#include <string>
#include <vector>

#if defined(MNN_SD_HAS_MNN)
#include <MNN/Interpreter.hpp>
#endif

namespace mnn_sd_detail
{

    MnnSdError mnn_sd_initialize_sessions(MnnSdEngine *engine, MnnSdErrorInfo *out_error)
    {
        if (!engine)
        {
            set_error(out_error, MNN_SD_ERR_INTERNAL, "engine is null");
            return MNN_SD_ERR_INTERNAL;
        }

#if !defined(MNN_SD_HAS_MNN)
        set_error(out_error, MNN_SD_ERR_BACKEND_INIT_FAILED, "MNN SDK not available at build time");
        return MNN_SD_ERR_BACKEND_INIT_FAILED;
#else
        if (engine->model_dir.empty())
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "model_dir is empty");
            return MNN_SD_ERR_INVALID_PARAMS;
        }

        const bool is_sdxl = engine->model_config.is_sdxl != 0;

        const std::string unet_path = build_model_path(engine->model_dir.c_str(), engine->model_config.unet_file);
        const std::string clip_path = build_model_path(engine->model_dir.c_str(), engine->model_config.clip_file);
        const std::string vae_path = build_model_path(engine->model_dir.c_str(), engine->model_config.vae_decoder_file);
        const std::string tok_path = build_model_path(engine->model_dir.c_str(), engine->model_config.tokenizer_file);
        const std::string vae_encoder_path = engine->model_config.vae_encoder_file[0] != '\0'
                                                 ? build_model_path(engine->model_dir.c_str(), engine->model_config.vae_encoder_file)
                                                 : "";

        if (!engine->tokenizer.load(tok_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "failed to load tokenizer.json", tok_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }

        std::string clip2_path;
        std::string tok2_path;
        if (is_sdxl)
        {
            if (engine->model_config.clip2_file[0] == '\0' || engine->model_config.tokenizer2_file[0] == '\0')
            {
                set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND,
                          "sdxl model.json missing clip2/tokenizer2", engine->model_dir.c_str());
                return MNN_SD_ERR_MODEL_NOT_FOUND;
            }
            clip2_path = build_model_path(engine->model_dir.c_str(), engine->model_config.clip2_file);
            tok2_path = build_model_path(engine->model_dir.c_str(), engine->model_config.tokenizer2_file);

            if (!engine->tokenizer2.load(tok2_path))
            {
                set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "failed to load tokenizer_2.json", tok2_path.c_str());
                return MNN_SD_ERR_MODEL_NOT_FOUND;
            }
        }

        // Load xororz embedding tables (token_emb.bin, pos_emb.bin).
        //
        // Bug fix (プロンプト無視の根因):
        //   これまで token_emb.bin を必ず float32 として読み込んでいたが、
        //   xororz 互換モデルは token_emb.bin を FP16 (uint16) でパッケージング
        //   することがある (実際、100MB 超の SD1.5 レガシー FP32 版と、
        //   ~72MB の FP16 版が世に出回っている)。
        //   FP16 データを float32 として reinterpret すると:
        //     - 要素数が半分になる (vocab_size が 49408 -> 24704 相当に化ける)
        //     - 各値の bit pattern が全く別の float 値に化ける
        //   結果として CLIP に渡る input_embedding はプロンプトと無関係の
        //   数値になり、生成画像がプロンプトを無視する。
        //
        // 対処:
        //   1. ファイルサイズと emb_dim から要素数を推定し、FP16 と FP32 を自動判定
        //   2. FP16 と判定した場合は uint16 -> float の変換を挟んで格納
        //   pos_emb.bin は歴代常に float32 なので従来通り。
        {
            auto load_fp32 = [](const std::string &path, std::vector<float> &out) -> bool
            {
                FILE *f = std::fopen(path.c_str(), "rb");
                if (!f)
                    return false;
                std::fseek(f, 0, SEEK_END);
                long sz = std::ftell(f);
                std::fseek(f, 0, SEEK_SET);
                out.resize(sz / sizeof(float));
                size_t got = std::fread(out.data(), sizeof(float), out.size(), f);
                std::fclose(f);
                return got == out.size() && !out.empty();
            };

            // IEEE 754 half-precision (binary16) -> float32. Handles subnormals,
            // Inf, NaN correctly. Small, self-contained, no dependency.
            auto fp16_to_fp32 = [](uint16_t h) -> float
            {
                uint32_t sign = (uint32_t)(h & 0x8000) << 16;
                uint32_t exp = (h >> 10) & 0x1F;
                uint32_t mant = h & 0x3FF;
                uint32_t f;
                if (exp == 0)
                {
                    if (mant == 0)
                    {
                        f = sign;
                    }
                    else
                    {
                        // Subnormal: renormalize.
                        exp = 1;
                        while ((mant & 0x400) == 0)
                        {
                            mant <<= 1;
                            exp -= 1;
                        }
                        mant &= 0x3FF;
                        f = sign | ((exp + (127 - 15)) << 23) | (mant << 13);
                    }
                }
                else if (exp == 0x1F)
                {
                    f = sign | 0x7F800000 | (mant << 13);
                }
                else
                {
                    f = sign | ((exp + (127 - 15)) << 23) | (mant << 13);
                }
                float out;
                std::memcpy(&out, &f, sizeof(out));
                return out;
            };

            auto load_token_emb = [&](const std::string &path,
                                      int emb_dim,
                                      int tokenizer_vocab_size,
                                      std::vector<float> &out,
                                      int &out_vocab_size,
                                      const char *label) -> bool
            {
                FILE *f = std::fopen(path.c_str(), "rb");
                if (!f)
                    return false;
                std::fseek(f, 0, SEEK_END);
                long sz = std::ftell(f);
                std::fseek(f, 0, SEEK_SET);
                if (sz <= 0 || emb_dim <= 0)
                {
                    std::fclose(f);
                    return false;
                }

                // Compute the vocab_size implied by each interpretation:
                //   FP16: 2 bytes/element -> vocab = sz / (2 * emb_dim)
                //   FP32: 4 bytes/element -> vocab = sz / (4 * emb_dim)
                //
                // Bug fix (v4 の判定が逆転していた問題):
                //   両方 [10000, 200000] に収まる場合、旧実装は FP32 を先に採用
                //   していた。CuteYukiMix (SD1.5) の token_emb.bin は
                //   75,890,688 バイト → FP16 なら 49408 vocab、FP32 なら 24704
                //   vocab。実際は FP16 (49408) が正しい (CLIP-L の tokenizer.json
                //   側 vocab も 49408) のに FP32 (24704) を選んでしまっていた。
                //
                // 決定基準 (v5): "tokenizer_vocab_size とちょうど一致する方"
                //   を最優先で採用する。tokenizer.json は既にロード済みで実際に
                //   使う vocab の大きさを知っているので、これが最も確実。
                //   一致するものが無ければ tokenizer vocab を包含する (>=) 側で
                //   差が小さい方を選ぶ。tokenizer が未ロードの場合のみ、従来の
                //   "現実的な範囲" ヒューリスティックにフォールバック — その際
                //   FP16 を優先する (現行パッケージの主流)。
                const long fp16_elem = (long)sizeof(uint16_t);
                const long fp32_elem = (long)sizeof(float);
                bool fp16_ok = (sz % (fp16_elem * emb_dim) == 0);
                bool fp32_ok = (sz % (fp32_elem * emb_dim) == 0);
                int fp16_vocab = fp16_ok ? (int)(sz / (fp16_elem * emb_dim)) : 0;
                int fp32_vocab = fp32_ok ? (int)(sz / (fp32_elem * emb_dim)) : 0;

                bool use_fp16 = false;
                bool decided = false;

                if (tokenizer_vocab_size > 0)
                {
                    // 1. Exact match wins outright.
                    if (fp16_ok && fp16_vocab == tokenizer_vocab_size)
                    {
                        use_fp16 = true;
                        decided = true;
                    }
                    else if (fp32_ok && fp32_vocab == tokenizer_vocab_size)
                    {
                        use_fp16 = false;
                        decided = true;
                    }
                    else
                    {
                        // 2. Nearest superset of tokenizer vocab. A token
                        //    embedding table must have >= tokenizer_vocab_size
                        //    rows (extra rows are legal for special / reserved
                        //    tokens); a smaller row count means the file was
                        //    misinterpreted as the wrong dtype.
                        int fp16_delta = (fp16_ok && fp16_vocab >= tokenizer_vocab_size)
                                             ? (fp16_vocab - tokenizer_vocab_size)
                                             : INT32_MAX;
                        int fp32_delta = (fp32_ok && fp32_vocab >= tokenizer_vocab_size)
                                             ? (fp32_vocab - tokenizer_vocab_size)
                                             : INT32_MAX;
                        if (fp16_delta != INT32_MAX || fp32_delta != INT32_MAX)
                        {
                            use_fp16 = (fp16_delta <= fp32_delta);
                            decided = true;
                        }
                    }
                }

                if (!decided)
                {
                    // Fallback: prefer FP16 (modern default) among plausible
                    // vocab sizes; last resort is FP32.
                    auto plausible = [](int v)
                    { return v >= 10000 && v <= 200000; };
                    if (fp16_ok && plausible(fp16_vocab))
                        use_fp16 = true;
                    else if (fp32_ok && plausible(fp32_vocab))
                        use_fp16 = false;
                    else
                        use_fp16 = false;
                }

                if (use_fp16)
                {
                    size_t n = (size_t)(sz / fp16_elem);
                    std::vector<uint16_t> buf(n);
                    size_t got = std::fread(buf.data(), sizeof(uint16_t), n, f);
                    std::fclose(f);
                    if (got != n)
                        return false;
                    out.resize(n);
                    for (size_t i = 0; i < n; ++i)
                        out[i] = fp16_to_fp32(buf[i]);
                    out_vocab_size = (int)(n / emb_dim);
                    PROBE_LOG("%s: file=%ld bytes, format=FP16, vocab_size=%d, emb_dim=%d, tokenizer_vocab=%d",
                              label, sz, out_vocab_size, emb_dim, tokenizer_vocab_size);
                }
                else
                {
                    size_t n = (size_t)(sz / fp32_elem);
                    out.resize(n);
                    size_t got = std::fread(out.data(), sizeof(float), n, f);
                    std::fclose(f);
                    if (got != n)
                        return false;
                    out_vocab_size = (int)(n / emb_dim);
                    PROBE_LOG("%s: file=%ld bytes, format=FP32, vocab_size=%d, emb_dim=%d, tokenizer_vocab=%d",
                              label, sz, out_vocab_size, emb_dim, tokenizer_vocab_size);
                }
                return true;
            };

            // SD1.5 packages this table as token_emb.bin/pos_emb.bin. The
            // SDXL converter script names CLIP-L's table token_emb1.bin/
            // pos_emb1.bin (to pair with CLIP-G's token_emb2.bin/pos_emb2.bin
            // — see model.json's "token_embedding1"/"position_embedding1"
            // keys). Try the SD1.5 names first, then the "...1.bin" names, so
            // both packaging conventions work without needing model.json to
            // spell out exact paths.
            std::string token_emb_path = build_model_path(engine->model_dir.c_str(), "token_emb.bin");
            std::string pos_emb_path = build_model_path(engine->model_dir.c_str(), "pos_emb.bin");
            if (!file_exists(token_emb_path) || !file_exists(pos_emb_path))
            {
                std::string alt_token = build_model_path(engine->model_dir.c_str(), "token_emb1.bin");
                std::string alt_pos = build_model_path(engine->model_dir.c_str(), "pos_emb1.bin");
                if (file_exists(alt_token) && file_exists(alt_pos))
                {
                    token_emb_path = alt_token;
                    pos_emb_path = alt_pos;
                }
            }

            if (is_sdxl && (!file_exists(token_emb_path) || !file_exists(pos_emb_path)))
            {
                set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND,
                          "sdxl requires token_emb.bin/token_emb1.bin + pos_emb.bin/pos_emb1.bin (CLIP-L tables)",
                          engine->model_dir.c_str());
                return MNN_SD_ERR_MODEL_NOT_FOUND;
            }

            if (file_exists(token_emb_path) && file_exists(pos_emb_path))
            {
                // pos_emb.bin is always FP32 (77 * emb_dim floats).
                load_fp32(pos_emb_path, engine->pos_emb);

                int emb_dim = engine->model_config.text_embedding_size > 0
                                  ? engine->model_config.text_embedding_size
                                  : (int)(engine->pos_emb.size() / ClipTokenizer::MAX_LEN);
                if (emb_dim <= 0)
                    emb_dim = 768;
                PROBE_LOG("pos_emb.bin: %zu floats, emb_dim=%d",
                          engine->pos_emb.size(), emb_dim);

                int tokenizer_vocab_size = (int)engine->tokenizer.vocab.size();
                int detected_vocab = 0;
                if (!load_token_emb(token_emb_path, emb_dim,
                                    tokenizer_vocab_size,
                                    engine->token_emb, detected_vocab,
                                    "token_emb.bin"))
                {
                    PROBE_LOG("token_emb.bin: FAILED to load (path=%s)",
                              token_emb_path.c_str());
                }
                engine->token_emb_vocab_size = detected_vocab;
            }

            if (is_sdxl)
            {
                const std::string token_emb2_path = build_model_path(engine->model_dir.c_str(), "token_emb2.bin");
                const std::string pos_emb2_path = build_model_path(engine->model_dir.c_str(), "pos_emb2.bin");

                if (!file_exists(token_emb2_path) || !file_exists(pos_emb2_path))
                {
                    set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND,
                              "sdxl requires token_emb2.bin / pos_emb2.bin", token_emb2_path.c_str());
                    return MNN_SD_ERR_MODEL_NOT_FOUND;
                }

                load_fp32(pos_emb2_path, engine->pos_emb2);

                int emb_dim2 = engine->model_config.text_embedding_size_2 > 0
                                   ? engine->model_config.text_embedding_size_2
                                   : (int)(engine->pos_emb2.size() / ClipTokenizer::MAX_LEN);
                if (emb_dim2 <= 0)
                    emb_dim2 = 1280;
                PROBE_LOG("pos_emb2.bin: %zu floats, emb_dim2=%d",
                          engine->pos_emb2.size(), emb_dim2);

                int tokenizer2_vocab_size = (int)engine->tokenizer2.vocab.size();
                int detected_vocab2 = 0;
                if (!load_token_emb(token_emb2_path, emb_dim2,
                                    tokenizer2_vocab_size,
                                    engine->token_emb2, detected_vocab2,
                                    "token_emb2.bin"))
                {
                    set_error(out_error, MNN_SD_ERR_MODEL_INVALID,
                              "failed to load token_emb2.bin", token_emb2_path.c_str());
                    return MNN_SD_ERR_MODEL_INVALID;
                }
                engine->token_emb2_vocab_size = detected_vocab2;
            }
        }

        // Just-in-time loading strategy: on low-RAM devices we cannot afford
        // to keep all three (CLIP + UNet + VAE) interpreters resident at
        // once. Verify the files exist here, but defer interpreter/session
        // creation to the pipeline stages (see mnn_sd_run_pipeline). Persist
        // only the paths used to recreate them.
        if (!file_exists(unet_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "unet not found", unet_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (!file_exists(clip_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "clip not found", clip_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (!file_exists(vae_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "vae not found", vae_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (is_sdxl && !file_exists(clip2_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "clip2 not found", clip2_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        engine->clip_path = clip_path;
        engine->clip2_path = clip2_path; // empty string when not SDXL
        engine->unet_path = unet_path;
        engine->vae_path = vae_path;
        engine->vae_encoder_path = vae_encoder_path;
        engine->has_vae_encoder = !vae_encoder_path.empty() && file_exists(vae_encoder_path);

        // Precompute PNDM alphas_cumprod (scaled_linear schedule, beta_start=0.00085, beta_end=0.012, T=1000)
        {
            const int T = 1000;
            const float beta_start = 0.00085f;
            const float beta_end = 0.012f;
            engine->alphas_cumprod.resize(T);
            float cumprod = 1.0f;
            for (int t = 0; t < T; ++t)
            {
                float frac = (float)t / (T - 1);
                float beta = std::pow(std::sqrt(beta_start) + frac * (std::sqrt(beta_end) - std::sqrt(beta_start)), 2.0f);
                float alpha = 1.0f - beta;
                cumprod *= alpha;
                engine->alphas_cumprod[t] = cumprod;
            }
        }

        return MNN_SD_OK;
#endif
    }

    void mnn_sd_release_sessions(MnnSdEngine *engine)
    {
        if (!engine)
            return;
        if (engine->unet_session)
        {
            engine->unet_interpreter->releaseSession(engine->unet_session);
            engine->unet_session = nullptr;
        }
        engine->unet_interpreter.reset();

        if (engine->clip_session)
        {
            engine->clip_interpreter->releaseSession(engine->clip_session);
            engine->clip_session = nullptr;
        }
        engine->clip_interpreter.reset();

        if (engine->clip2_session)
        {
            engine->clip2_interpreter->releaseSession(engine->clip2_session);
            engine->clip2_session = nullptr;
        }
        engine->clip2_interpreter.reset();

        if (engine->vae_session)
        {
            engine->vae_interpreter->releaseSession(engine->vae_session);
            engine->vae_session = nullptr;
        }
        engine->vae_interpreter.reset();

        if (engine->vae_encoder_session)
        {
            engine->vae_encoder_interpreter->releaseSession(engine->vae_encoder_session);
            engine->vae_encoder_session = nullptr;
        }
        engine->vae_encoder_interpreter.reset();
    }

    MnnSdError mnn_sd_probe_model(
        const char *mnn_path,
        MnnSdBackend backend,
        char *out_log,
        size_t out_log_capacity,
        MnnSdErrorInfo *out_error)
    {
        if (!mnn_path || mnn_path[0] == '\0')
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "mnn_path is required");
            return MNN_SD_ERR_INVALID_PARAMS;
        }
        if (out_log && out_log_capacity > 0)
        {
            out_log[0] = '\0';
        }

#if !defined(MNN_SD_HAS_MNN)
        char line[512];
        std::snprintf(line, sizeof(line),
                      "MNN not linked. Rebuild with -DMNN_ROOT=/path/to/MNN (see README). path=%s backend=%d",
                      mnn_path, static_cast<int>(backend));
        append_log(out_log, out_log_capacity, line);
        set_error(out_error, MNN_SD_ERR_BACKEND_INIT_FAILED, "MNN SDK not available at build time");
        return MNN_SD_ERR_BACKEND_INIT_FAILED;
#else
        std::shared_ptr<MNN::Interpreter> net(MNN::Interpreter::createFromFile(mnn_path));
        if (!net)
        {
            set_error(out_error, MNN_SD_ERR_MODEL_INVALID, "failed to create MNN interpreter", mnn_path);
            return MNN_SD_ERR_MODEL_INVALID;
        }

        // Probe path uses the safest CLIP-equivalent config.
        ScheduleBundle bundle(backend, SdModelKind::CLIP, false);
        MNN::Session *session = net->createSession(bundle.schedule);
        if (!session)
        {
            set_error(out_error, MNN_SD_ERR_BACKEND_INIT_FAILED, "failed to create MNN session");
            return MNN_SD_ERR_BACKEND_INIT_FAILED;
        }

        char header[256];
        std::snprintf(header, sizeof(header), "=== probe: %s (backend=%d) ===",
                      mnn_path, static_cast<int>(backend));
        append_log(out_log, out_log_capacity, header);

        auto dump_tensor = [&](const char *kind, const MNN::Tensor *tensor, const char *name)
        {
            if (!tensor)
                return;
            char line[512];
            std::snprintf(line, sizeof(line), "%s %s shape=[", kind, name);
            append_log(out_log, out_log_capacity, line);

            std::string shape_str;
            for (int i = 0; i < tensor->dimensions(); ++i)
            {
                if (i > 0)
                    shape_str += "x";
                shape_str += std::to_string(tensor->length(i));
            }
            std::snprintf(line, sizeof(line), "%s] dtype=%d elements=%d",
                          shape_str.c_str(), static_cast<int>(tensor->getType().code), tensor->elementSize());
            append_log(out_log, out_log_capacity, line);
        };

        const auto &inputs = net->getSessionInputAll(session);
        for (const auto &item : inputs)
        {
            dump_tensor("input", item.second, item.first.c_str());
        }

        const auto &outputs = net->getSessionOutputAll(session);
        for (const auto &item : outputs)
        {
            dump_tensor("output", item.second, item.first.c_str());
        }

        net->releaseSession(session);
        return MNN_SD_OK;
#endif
    }

} // namespace mnn_sd_detail

extern "C"
{
    MnnSdError mnn_sd_initialize_sessions(MnnSdEngine *engine, MnnSdErrorInfo *out_error)
    {
        return mnn_sd_detail::mnn_sd_initialize_sessions(engine, out_error);
    }
    void mnn_sd_release_sessions(MnnSdEngine *engine)
    {
        mnn_sd_detail::mnn_sd_release_sessions(engine);
    }
    MnnSdError mnn_sd_probe_model(const char *mnn_path, MnnSdBackend backend, char *out_log, size_t out_log_capacity, MnnSdErrorInfo *out_error)
    {
        return mnn_sd_detail::mnn_sd_probe_model(mnn_path, backend, out_log, out_log_capacity, out_error);
    }
}
