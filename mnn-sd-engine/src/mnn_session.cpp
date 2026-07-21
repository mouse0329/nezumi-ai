#include "mnn_sd/engine.h"
#include "engine_internal.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <climits>
#include <memory>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#if defined(MNN_SD_HAS_MNN)
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#ifdef ANDROID
#include <android/log.h>
#define PROBE_LOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "MnnSdJni", fmt, ##__VA_ARGS__)
#else
#define PROBE_LOG(fmt, ...) std::fprintf(stderr, fmt "\n", ##__VA_ARGS__)
#endif
#endif

// Bug fix (SIGABRT during VAE createFromFile after UNet ran):
//   Android bionicのscudoはUNetをreleaseSession/resetしても、
//   一時的に使った大きなチャンクを即座にOSに返さないことがある。
//   直後にVAEをInterpreter::createFromFileすると、scudoが新規を
//   割り当てられず internal map failure -> SIGABRT でプロセスが落ちる。
//   malloc_trim(0) を予備式に呼んで、UNet 解放後のフリーリストをOSに
//   返しておくと VAE ロードが安定する。
//   bionic の malloc_trim は malloc.h にないので、弱参照で宣言し、
//   シンボルがない環境でもリンクが通るようにする。
#if defined(__ANDROID__)
extern "C" int malloc_trim(size_t pad) __attribute__((weak));
namespace {
inline void trim_heap_to_os() noexcept
{
    if (&malloc_trim != nullptr) {
        (void)malloc_trim(0);
    }
}
}
#else
namespace { inline void trim_heap_to_os() noexcept {} }
#endif

namespace
{

    void set_error(MnnSdErrorInfo *out, MnnSdError code, const char *message, const char *cause = "")
    {
        if (!out)
            return;
        out->code = code;
        std::snprintf(out->message, sizeof(out->message), "%s", message ? message : "");
        std::snprintf(out->cause, sizeof(out->cause), "%s", cause ? cause : "");
    }

    void append_log(char *out_log, size_t capacity, const char *line)
    {
        if (!out_log || capacity == 0 || !line)
            return;
        size_t used = std::strlen(out_log);
        if (used >= capacity - 1)
            return;
        std::snprintf(out_log + used, capacity - used, "%s\n", line);
    }

#if defined(MNN_SD_HAS_MNN)

    // Which SD sub-model the schedule bundle is for. Precision must be picked
    // per model because SD1.5 VAE cannot survive full FP16 on OpenCL.
    enum class SdModelKind
    {
        CLIP,
        UNET,
        VAE,
    };

    // Bug fix: OpenCL 推論が起動直後に abort する / 出力が真っ黒になる問題は
    // 立てていなかったこと (MNN_GPU_MEMORY_BUFFER + MNN_GPU_TUNING_FAST) と、
    // CPU 側にスレッド数 / Memory_Low ヒントを与えていなかったことが原因。
    //
    // Bug fix (GPU で真っ白画像になる問題 / 2026-07):
    //   これまで OpenCL でも Precision_Low (=全 FP16) を全モデルに強制していた。
    //   SD1.5 の VAE decoder は FP16 で中間活性が 65504 を超えて +Inf 化しやすく
    //   (madebyollin/sdxl-vae-fp16-fix が対処している SDXL の症状と同型)、
    //   最終段の [-1, 1] クランプで全ピクセルが +1.0 に潰れ、RGB=255 の真っ白
    //   画像になる。CPU バックエンドは Precision_Low でも実質 fp32 で回るため
    //   症状が出ず、「CPU では動くが GPU では真っ白」の挙動と一致する。
    //
    //   さらに UNet を Precision_Low で回すと、一部端末で cross-attention の
    //   softmax 前 logits がオーバーフローして NaN を吐き、そのまま VAE 出口で
    //   真っ白になるパスもある。
    //
    //   対処:
    //     - VAE: Precision_High (fp32) 固定。速度低下は decode 1 回分だけなので許容。
    //     - UNet: Precision_Normal (mixed: matmul は fp16、蓄積・正規化は fp32)。
    //     - CLIP: Precision_Normal (十分安定・十分速い)。
    //     - Memory_Normal / TUNING_NORMAL に緩める (Memory_Low + TUNING_FAST の
    //       組み合わせは Adreno 系で稀に activation を丸めすぎる)。
    struct ScheduleBundle
    {
        MNN::BackendConfig backend_config{};
        MNN::ScheduleConfig schedule{};

        ScheduleBundle(MnnSdBackend backend, SdModelKind kind, bool low_memory_unet)
        {
            backend_config.power = MNN::BackendConfig::Power_High;
            if (backend == MNN_SD_BACKEND_OPENCL)
            {
                schedule.type = MNN_FORWARD_OPENCL;
                // MNN_GPU_MEMORY_BUFFER: OpenCL 側で cl_mem を Buffer として確保する。
                //   Image ベースだと 512x512 UNet の中間テンソルで Mali/Adreno が
                //   CL_INVALID_IMAGE_SIZE を返してドライバごと abort する端末があった。
                // MNN_GPU_TUNING_NORMAL: FAST だとカーネル選択が粗く、UNet の一部で
                //   FP16 精度側にフォールバックしてしまう端末があった。NORMAL に上げる。
                schedule.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_NORMAL;
                switch (kind)
                {
                case SdModelKind::VAE:
                    // ★ 真っ白画像を潰す本命 ★ fp32 で走らせる。
                    backend_config.precision = MNN::BackendConfig::Precision_High;
                    backend_config.memory = MNN::BackendConfig::Memory_Normal;
                    break;
                case SdModelKind::UNET:
                    // Bug fix #2 (2026-07 単色青画像):
                    //   前回パッチで UNet を Precision_Normal (mixed fp16/32) に
                    //   落としたが、一部端末 (ARMv8 Cortex-A78 + Adreno 系で実測)
                    //   では cross-attention の softmax 前 logits が FP16 の
                    //   65504 を超えてオーバーフロー、NaN が伝播して UNet 出力
                    //   がほぼゼロになる。すると VAE (fp32) は学習済みバイアスだけを
                    //   デコードし、SD1.5 の学習セット平均色 (薄い青) を出す。
                    //   これが「真っ白 → 青一色」の正体。
                    //
                    //   UNet 20 step × 2(uncond+cond)=40 forward の実測時間が
                    //   63 秒 (=1 forward 1.5 秒) と、正常時 4-8 秒より明らかに
                    //   短いことも、途中で NaN 化して以降のカーネルが早期終了
                    //   している傍証。
                    //
                    // CuteYukiMix overflows on several mobile OpenCL drivers
                    // in FP16 (the first UNet output becomes Inf/NaN). Keep
                    // FP32 arithmetic, but honor the low-memory request for
                    // the allocator so activation buffers are not retained.
                    backend_config.precision = MNN::BackendConfig::Precision_High;
                    backend_config.memory = low_memory_unet
                                                ? MNN::BackendConfig::Memory_Low
                                                : MNN::BackendConfig::Memory_Normal;
                    break;
                case SdModelKind::CLIP:
                default:
                    backend_config.precision = MNN::BackendConfig::Precision_Normal;
                    backend_config.memory = MNN::BackendConfig::Memory_Normal;
                    break;
                }
            }
            else
            {
                schedule.type = MNN_FORWARD_CPU;
                schedule.numThread = 4;
                // CPU は Precision_Low でも実質 fp32 演算 (メモリ節約フラグ扱い)。
                backend_config.precision = MNN::BackendConfig::Precision_Low;
                backend_config.memory = MNN::BackendConfig::Memory_Low;
            }
            schedule.backendConfig = &backend_config;
        }
    };

#endif

} // namespace

namespace
{

    std::string build_model_path(const char *model_dir, const char *filename)
    {
        std::string path(model_dir);
        if (!path.empty() && path.back() != '/' && path.back() != '\\')
        {
            path += '/';
        }
        path += filename;
        return path;
    }

    bool file_exists(const std::string &path)
    {
        FILE *f = std::fopen(path.c_str(), "rb");
        if (!f)
            return false;
        std::fclose(f);
        return true;
    }

#if defined(MNN_SD_HAS_MNN)

    MnnSdError create_interpreter_and_session(
        const std::string &model_path,
        MnnSdBackend backend,
        SdModelKind kind,
        bool low_memory_unet,
        std::shared_ptr<MNN::Interpreter> &interpreter,
        MNN::Session *&session,
        MnnSdErrorInfo *out_error)
    {
        if (!file_exists(model_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "model file missing", model_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }

        interpreter = std::shared_ptr<MNN::Interpreter>(MNN::Interpreter::createFromFile(model_path.c_str()));
        if (!interpreter)
        {
            set_error(out_error, MNN_SD_ERR_MODEL_INVALID, "failed to create MNN interpreter", model_path.c_str());
            return MNN_SD_ERR_MODEL_INVALID;
        }

        ScheduleBundle bundle(backend, kind, low_memory_unet);
        PROBE_LOG("MNN session: model=%s backend=%d kind=%d unet_low_memory=%d",
                  model_path.c_str(), static_cast<int>(backend), static_cast<int>(kind),
                  low_memory_unet ? 1 : 0);
        session = interpreter->createSession(bundle.schedule);
        if (!session)
        {
            interpreter.reset();
            set_error(out_error, MNN_SD_ERR_BACKEND_INIT_FAILED, "failed to create MNN session", model_path.c_str());
            return MNN_SD_ERR_BACKEND_INIT_FAILED;
        }

        return MNN_SD_OK;
    }

#endif

} // namespace

extern "C"
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

        const std::string unet_path = build_model_path(engine->model_dir.c_str(), engine->model_config.unet_file);
        const std::string clip_path = build_model_path(engine->model_dir.c_str(), engine->model_config.clip_file);
        const std::string vae_path = build_model_path(engine->model_dir.c_str(), engine->model_config.vae_decoder_file);
        const std::string tok_path = build_model_path(engine->model_dir.c_str(), engine->model_config.tokenizer_file);

        if (!engine->tokenizer.load(tok_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "failed to load tokenizer.json", tok_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
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

            const std::string token_emb_path = build_model_path(engine->model_dir.c_str(), "token_emb.bin");
            const std::string pos_emb_path = build_model_path(engine->model_dir.c_str(), "pos_emb.bin");

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
        engine->clip_path = clip_path;
        engine->unet_path = unet_path;
        engine->vae_path = vae_path;

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

        if (engine->vae_session)
        {
            engine->vae_interpreter->releaseSession(engine->vae_session);
            engine->vae_session = nullptr;
        }
        engine->vae_interpreter.reset();
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

} // extern "C"

// ============================================================
// BPE Tokenizer (ClipTokenizer)
// ============================================================

namespace
{
    // ---------------------------------------------------------------------
    // Structural JSON scanners for tokenizer.json.
    //
    // These are NOT a general-purpose JSON parser; they only handle the
    // subset produced by HuggingFace's `tokenizers` library for a BPE
    // model (an object with a "model" sub-object containing a "vocab"
    // object and a "merges" array of strings).
    //
    // Previous version of this file used std::string::find('"') to iterate
    // through the JSON text without tracking structural context. That
    // walked over the inter-entry whitespace/comma as if it were part of
    // the next entry, silently losing ~40% of the merges table on the
    // real CLIP tokenizer.json. Concretely, in
    //     "merges": [
    //       "i n",
    //       "t h",
    //       ...
    // the naive scan produced garbage entries like  (",\n", "     ")
    // and pushed real merges past their true rank, so lookups such as
    // ("h","ello</w>") returned "not found" and BPE stopped at 'h'+'ello</w>'
    // (IDs 71 + 2512) instead of merging to 'hello</w>' (ID 3306) --
    // hence the "prompt is being ignored" symptom.
    //
    // The new scanner walks the token stream while respecting JSON syntax:
    // after a value in an array or object, the next meaningful character
    // is either ',' or ']'/'}' (whitespace is skipped). That is enough to
    // separate entries reliably without a full JSON grammar.

    inline void json_skip_ws(const std::string &s, size_t &i)
    {
        while (i < s.size())
        {
            char c = s[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
                ++i;
            else
                break;
        }
    }

    // Read a JSON string literal starting at s[i] == '"'. On success sets
    // i to just past the closing quote and returns the unescaped content.
    bool json_read_string(const std::string &s, size_t &i, std::string &out)
    {
        if (i >= s.size() || s[i] != '"')
            return false;
        ++i;
        out.clear();
        while (i < s.size())
        {
            unsigned char c = (unsigned char)s[i];
            if (c == '"')
            {
                ++i;
                return true;
            }
            if (c == '\\')
            {
                if (i + 1 >= s.size())
                    return false;
                char esc = s[i + 1];
                switch (esc)
                {
                case '"':
                    out += '"';
                    break;
                case '\\':
                    out += '\\';
                    break;
                case '/':
                    out += '/';
                    break;
                case 'b':
                    out += '\b';
                    break;
                case 'f':
                    out += '\f';
                    break;
                case 'n':
                    out += '\n';
                    break;
                case 'r':
                    out += '\r';
                    break;
                case 't':
                    out += '\t';
                    break;
                case 'u':
                {
                    if (i + 5 >= s.size())
                        return false;
                    unsigned int cp = 0;
                    for (int k = 0; k < 4; ++k)
                    {
                        char h = s[i + 2 + k];
                        cp <<= 4;
                        if (h >= '0' && h <= '9')
                            cp |= (h - '0');
                        else if (h >= 'a' && h <= 'f')
                            cp |= (h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F')
                            cp |= (h - 'A' + 10);
                        else
                            return false;
                    }
                    if (cp < 0x80)
                    {
                        out += (char)cp;
                    }
                    else if (cp < 0x800)
                    {
                        out += (char)(0xC0 | (cp >> 6));
                        out += (char)(0x80 | (cp & 0x3F));
                    }
                    else
                    {
                        out += (char)(0xE0 | (cp >> 12));
                        out += (char)(0x80 | ((cp >> 6) & 0x3F));
                        out += (char)(0x80 | (cp & 0x3F));
                    }
                    i += 6;
                    continue;
                }
                default:
                    out += '\\';
                    out += esc;
                    break;
                }
                i += 2;
                continue;
            }
            out += (char)c;
            ++i;
        }
        return false;
    }

    // Scan the value that starts at s[i], leaving i just past it.
    // Handles strings, objects, arrays, numbers, true/false/null.
    void json_skip_value(const std::string &s, size_t &i)
    {
        json_skip_ws(s, i);
        if (i >= s.size())
            return;
        char c = s[i];
        if (c == '"')
        {
            std::string dummy;
            json_read_string(s, i, dummy);
            return;
        }
        if (c == '{' || c == '[')
        {
            char open_c = c;
            char close_c = (c == '{') ? '}' : ']';
            ++i;
            int depth = 1;
            bool in_str = false;
            while (i < s.size() && depth > 0)
            {
                char ch = s[i];
                if (in_str)
                {
                    if (ch == '\\' && i + 1 < s.size())
                    {
                        i += 2;
                        continue;
                    }
                    if (ch == '"')
                        in_str = false;
                    ++i;
                    continue;
                }
                if (ch == '"')
                {
                    in_str = true;
                    ++i;
                    continue;
                }
                if (ch == open_c)
                    ++depth;
                else if (ch == close_c)
                    --depth;
                ++i;
            }
            return;
        }
        // number / bool / null: skip until control char at depth 0
        while (i < s.size())
        {
            char ch = s[i];
            if (ch == ',' || ch == '}' || ch == ']' ||
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
                break;
            ++i;
        }
    }

    // Find `"key"` inside the object whose opening `{` is at start.
    // On success sets out_after_colon to point just past the ':' (whitespace
    // may follow before the value). Returns false if the key is missing.
    bool json_find_key(const std::string &s, size_t start, const std::string &key, size_t &out_after_colon)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '{')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == '}')
                return false;
            if (s[i] == ',')
            {
                ++i;
                continue;
            }
            std::string k;
            if (!json_read_string(s, i, k))
                return false;
            json_skip_ws(s, i);
            if (i >= s.size() || s[i] != ':')
                return false;
            ++i;
            json_skip_ws(s, i);
            if (k == key)
            {
                out_after_colon = i;
                return true;
            }
            json_skip_value(s, i);
        }
        return false;
    }

    bool json_read_int(const std::string &s, size_t &i, long &out)
    {
        json_skip_ws(s, i);
        size_t start = i;
        if (i < s.size() && (s[i] == '-' || s[i] == '+'))
            ++i;
        while (i < s.size() && std::isdigit((unsigned char)s[i]))
            ++i;
        if (i == start)
            return false;
        try
        {
            out = std::stol(s.substr(start, i - start));
        }
        catch (...)
        {
            return false;
        }
        return true;
    }

    // Parse `{ "tok": id, ... }` at s[start]==`{`.
    bool clip_parse_vocab_object(const std::string &s, size_t start,
                                 std::unordered_map<std::string, int> &vocab)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '{')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == '}')
            {
                ++i;
                return true;
            }
            if (s[i] == ',')
            {
                ++i;
                continue;
            }
            std::string tok;
            if (!json_read_string(s, i, tok))
                return false;
            json_skip_ws(s, i);
            if (i >= s.size() || s[i] != ':')
                return false;
            ++i;
            long id = 0;
            if (!json_read_int(s, i, id))
                return false;
            vocab[tok] = (int)id;
        }
        return false;
    }

    // Parse `[ "a b", "c d", ... ]` (legacy string form) OR
    //       `[ ["a", "b"], ... ]` (modern list form) at s[start]==`[`.
    bool clip_parse_merges_array(const std::string &s, size_t start,
                                 std::vector<std::pair<std::string, std::string>> &merges)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '[')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == ']')
            {
                ++i;
                return true;
            }
            if (s[i] == ',')
            {
                ++i;
                continue;
            }

            if (s[i] == '"')
            {
                std::string entry;
                if (!json_read_string(s, i, entry))
                    return false;
                auto sp = entry.find(' ');
                if (sp != std::string::npos)
                    merges.emplace_back(entry.substr(0, sp), entry.substr(sp + 1));
            }
            else if (s[i] == '[')
            {
                ++i;
                std::string a, b;
                json_skip_ws(s, i);
                if (!json_read_string(s, i, a))
                    return false;
                json_skip_ws(s, i);
                if (i >= s.size() || s[i] != ',')
                    return false;
                ++i;
                json_skip_ws(s, i);
                if (!json_read_string(s, i, b))
                    return false;
                json_skip_ws(s, i);
                if (i >= s.size() || s[i] != ']')
                    return false;
                ++i;
                merges.emplace_back(std::move(a), std::move(b));
            }
            else
            {
                ++i;
            }
        }
        return false;
    }
} // namespace

bool ClipTokenizer::load(const std::string &path)
{
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f)
        return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::string json(sz, '\0');
    std::fread(&json[0], 1, sz, f);
    std::fclose(f);

    size_t p = 0;
    json_skip_ws(json, p);
    if (p >= json.size() || json[p] != '{')
        return false;

    // Descend to "model": { ... }
    size_t model_start = 0;
    if (!json_find_key(json, p, "model", model_start))
        return false;
    json_skip_ws(json, model_start);
    if (model_start >= json.size() || json[model_start] != '{')
        return false;

    // Descend to "model.vocab": { ... }
    size_t vocab_start = 0;
    if (!json_find_key(json, model_start, "vocab", vocab_start))
        return false;
    if (!clip_parse_vocab_object(json, vocab_start, vocab))
        return false;

    // Descend to "model.merges": [ ... ]
    size_t merges_start = 0;
    if (!json_find_key(json, model_start, "merges", merges_start))
        return false;
    if (!clip_parse_merges_array(json, merges_start, merges))
        return false;

    return !vocab.empty() && !merges.empty();
}

// CLIP byte-level unicode mapping (same as GPT-2)
std::string ClipTokenizer::bytes_to_unicode(unsigned char c)
{
    // printable ASCII (33-126) and latin-1 supplement (161-172, 174-255) map to themselves
    if ((c >= 33 && c <= 126) || (c >= 161 && c <= 172) || (c >= 174 && c <= 255))
        return std::string(1, (char)c);
    // remaining 256 bytes map to U+0100..U+013F range encoded as UTF-8
    // offset: 0->256, 1->257, ... but we need the actual unicode codepoint
    // The mapping fills gaps: 0-32, 127-160, 173 -> codepoints 256+
    static const int remap[] = {
        256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271,
        272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287,
        288, // 32 entries for 0-32
        289, // 127
        290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305,
        306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321,
        322, // 160
        323  // 173
    };
    // Build lookup on first call
    static std::unordered_map<int, int> byte_to_cp;
    if (byte_to_cp.empty())
    {
        int n = 0;
        for (int b = 0; b < 256; ++b)
        {
            bool printable = (b >= 33 && b <= 126) || (b >= 161 && b <= 172) || (b >= 174 && b <= 255);
            if (!printable)
                byte_to_cp[b] = 256 + n++;
            else
                byte_to_cp[b] = b;
        }
    }
    int cp = byte_to_cp[c];
    // Encode codepoint as UTF-8
    std::string out;
    if (cp < 0x80)
    {
        out += (char)cp;
    }
    else if (cp < 0x800)
    {
        out += (char)(0xC0 | (cp >> 6));
        out += (char)(0x80 | (cp & 0x3F));
    }
    else
    {
        out += (char)(0xE0 | (cp >> 12));
        out += (char)(0x80 | ((cp >> 6) & 0x3F));
        out += (char)(0x80 | (cp & 0x3F));
    }
    return out;
}

namespace
{
    // Lazy rank table cached per merges vector. The engine loads exactly
    // one tokenizer per process lifetime, so a single cached table is
    // sufficient. Key format is  "a\0b"  (first token, NUL, second token)
    // to avoid needing a custom hash for std::pair<string,string>.
    struct BpeRankTable
    {
        const void *owner = nullptr;
        std::unordered_map<std::string, int> rank;
    };
    inline BpeRankTable &bpe_rank_table_for(const std::vector<std::pair<std::string, std::string>> &merges)
    {
        static BpeRankTable table;
        if (table.owner != (const void *)&merges)
        {
            table.owner = (const void *)&merges;
            table.rank.clear();
            table.rank.reserve(merges.size() * 2);
            for (size_t i = 0; i < merges.size(); ++i)
            {
                std::string key;
                key.reserve(merges[i].first.size() + 1 + merges[i].second.size());
                key.append(merges[i].first);
                key.push_back('\0');
                key.append(merges[i].second);
                table.rank.emplace(std::move(key), (int)i);
            }
        }
        return table;
    }
}

std::string ClipTokenizer::bpe(const std::string &token) const
{
    if (token.empty())
        return token;
    // Split token into UTF-8 characters, append </w> to last
    std::vector<std::string> chars;
    size_t i = 0;
    while (i < token.size())
    {
        unsigned char c = (unsigned char)token[i];
        int len = 1;
        if (c >= 0xF0)
            len = 4;
        else if (c >= 0xE0)
            len = 3;
        else if (c >= 0xC0)
            len = 2;
        chars.push_back(token.substr(i, len));
        i += len;
    }
    if (!chars.empty())
        chars.back() += "</w>";

    // BPE merge loop
    auto &rank_table = bpe_rank_table_for(merges).rank;
    while (chars.size() > 1)
    {
        // Find the highest-priority merge pair (lowest rank number).
        int best_rank = -1;
        size_t best_pos = 0;
        std::string key;
        for (size_t k = 0; k + 1 < chars.size(); ++k)
        {
            key.clear();
            key.reserve(chars[k].size() + 1 + chars[k + 1].size());
            key.append(chars[k]);
            key.push_back('\0');
            key.append(chars[k + 1]);
            auto it = rank_table.find(key);
            if (it == rank_table.end())
                continue;
            int r = it->second;
            if (best_rank < 0 || r < best_rank)
            {
                best_rank = r;
                best_pos = k;
            }
        }
        if (best_rank < 0)
            break;
        chars[best_pos] += chars[best_pos + 1];
        chars.erase(chars.begin() + best_pos + 1);
    }

    std::string result;
    for (size_t k = 0; k < chars.size(); ++k)
    {
        if (k > 0)
            result += ' ';
        result += chars[k];
    }
    return result;
}

namespace
{
    // ---- UTF-8 helpers ----------------------------------------------------
    // Read a single UTF-8 codepoint starting at bytes[pos]. Advances pos.
    // On malformed input, treats each stray byte as its own codepoint.
    static inline uint32_t utf8_next(const std::string &s, size_t &pos)
    {
        if (pos >= s.size())
            return 0;
        unsigned char c = (unsigned char)s[pos];
        uint32_t cp;
        int extra;
        if (c < 0x80)
        {
            cp = c;
            extra = 0;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            cp = c & 0x1F;
            extra = 1;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            cp = c & 0x0F;
            extra = 2;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            cp = c & 0x07;
            extra = 3;
        }
        else
        {
            pos += 1;
            return c;
        }
        if (pos + 1 + extra > s.size())
        {
            pos += 1;
            return c;
        }
        for (int k = 0; k < extra; ++k)
        {
            unsigned char nc = (unsigned char)s[pos + 1 + k];
            if ((nc & 0xC0) != 0x80)
            {
                pos += 1;
                return c;
            }
            cp = (cp << 6) | (nc & 0x3F);
        }
        pos += 1 + extra;
        return cp;
    }

    // ASCII category tables adequate for CLIP (which lowercases + NFC first).
    // For the non-ASCII plane we conservatively call every non-ASCII codepoint
    // a "letter" (matches \p{L} for the great majority of prompts; wrong for
    // stray punctuation but harmless because ByteLevel + BPE will still map
    // known n-grams correctly).
    static inline bool cp_is_space(uint32_t cp)
    {
        return cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0B ||
               cp == 0x0C || cp == 0x0D || cp == 0xA0;
    }
    static inline bool cp_is_letter(uint32_t cp)
    {
        if (cp < 0x80)
            return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || cp == '_';
        // Treat any non-ASCII printable as a letter for splitting purposes.
        return true;
    }
    static inline bool cp_is_digit(uint32_t cp)
    {
        return cp >= '0' && cp <= '9';
    }
    static inline void cp_encode_utf8(uint32_t cp, std::string &out)
    {
        if (cp < 0x80)
        {
            out += (char)cp;
        }
        else if (cp < 0x800)
        {
            out += (char)(0xC0 | (cp >> 6));
            out += (char)(0x80 | (cp & 0x3F));
        }
        else if (cp < 0x10000)
        {
            out += (char)(0xE0 | (cp >> 12));
            out += (char)(0x80 | ((cp >> 6) & 0x3F));
            out += (char)(0x80 | (cp & 0x3F));
        }
        else
        {
            out += (char)(0xF0 | (cp >> 18));
            out += (char)(0x80 | ((cp >> 12) & 0x3F));
            out += (char)(0x80 | ((cp >> 6) & 0x3F));
            out += (char)(0x80 | (cp & 0x3F));
        }
    }

    // Bug fix (プロンプト無視): CLIP のプリトークナイザ (HuggingFace の
    //   Regex(r"'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"))
    // と等価な分割を行う。旧実装は空白のみで分割していたため "a red, cute cat" が
    //   "a"  "red,"  "cute"  "cat"
    // に化け、"red,</w>" が語彙に存在せずに silent-drop されていた。正しくは
    //   "a"  "red"  ","  "cute"  "cat"
    // の 5 断片に割り、それぞれを ByteLevel 経由で BPE にかける。
    static std::vector<std::string> pre_tokenize_clip(const std::string &lower_text)
    {
        std::vector<std::string> pieces;
        const std::string &s = lower_text;
        size_t i = 0;
        while (i < s.size())
        {
            // Skip whitespace runs (regex removes them).
            size_t save = i;
            uint32_t cp = utf8_next(s, i);
            if (cp_is_space(cp))
                continue;
            i = save; // rewind

            // Try apostrophe contractions: 's 't 're 've 'm 'll 'd
            if ((unsigned char)s[i] == '\'' && i + 1 < s.size())
            {
                static const char *contractions[] = {"'s", "'t", "'re", "'ve", "'m", "'ll", "'d"};
                bool matched = false;
                for (const char *c : contractions)
                {
                    size_t L = std::strlen(c);
                    if (i + L <= s.size() && s.compare(i, L, c) == 0)
                    {
                        pieces.emplace_back(c);
                        i += L;
                        matched = true;
                        break;
                    }
                }
                if (matched)
                    continue;
            }

            // Peek codepoint category.
            size_t start = i;
            uint32_t first = utf8_next(s, i);

            if (cp_is_letter(first))
            {
                // Consume run of letters.
                while (i < s.size())
                {
                    size_t sv = i;
                    uint32_t nc = utf8_next(s, i);
                    if (!cp_is_letter(nc))
                    {
                        i = sv;
                        break;
                    }
                }
                pieces.emplace_back(s.substr(start, i - start));
            }
            else if (cp_is_digit(first))
            {
                // Single digit per token (CLIP's [\p{N}] is not +, it splits each digit).
                pieces.emplace_back(s.substr(start, i - start));
            }
            else
            {
                // Run of non-space, non-letter, non-digit.
                while (i < s.size())
                {
                    size_t sv = i;
                    uint32_t nc = utf8_next(s, i);
                    if (cp_is_space(nc) || cp_is_letter(nc) || cp_is_digit(nc))
                    {
                        i = sv;
                        break;
                    }
                }
                pieces.emplace_back(s.substr(start, i - start));
            }
        }
        return pieces;
    }
} // namespace

std::vector<int> ClipTokenizer::encode_single(const std::string &text) const
{
    std::vector<int> ids;
    ids.push_back(BOS_ID);

    // NFC would go here in HuggingFace; skipped because prompts are typically
    // already normalized and MNN's CLIP is trained on lowercased text.
    std::string lower;
    lower.reserve(text.size());
    for (unsigned char c : text)
        lower += (char)std::tolower(c);

    // Bug fix (プロンプト無視): CLIP プリトークナイザに合わせて分割する。
    //   詳細は pre_tokenize_clip() のコメント参照。
    auto pieces = pre_tokenize_clip(lower);

    for (const auto &piece : pieces)
    {
        if (piece.empty())
            continue;

        // Byte-level encode: each raw byte -> unicode escape (CLIP/GPT-2 shared table)
        std::string byte_word;
        byte_word.reserve(piece.size() * 2);
        for (unsigned char c : piece)
            byte_word += bytes_to_unicode(c);

        // BPE merge
        std::string bpe_result = bpe(byte_word);

        // Split on space; look up each sub-token in vocab. If a sub-token is
        // missing, fall back one level: replace it with its per-character
        // ByteLevel sub-tokens (which are guaranteed to exist as individual
        // codepoints in the CLIP vocab). This preserves at least the "shape"
        // of the input instead of silently dropping it.
        std::istringstream bpe_ss(bpe_result);
        std::string sub;
        while (bpe_ss >> sub)
        {
            auto it = vocab.find(sub);
            if (it != vocab.end())
            {
                ids.push_back(it->second);
                continue;
            }
            // Fallback: emit each unicode-encoded byte one by one.
            // sub is a UTF-8 string of ByteLevel-escaped codepoints; iterate
            // codepoints and look them up individually.
            size_t p = 0;
            while (p < sub.size())
            {
                size_t before = p;
                uint32_t cp = utf8_next(sub, p);
                std::string one;
                cp_encode_utf8(cp, one);
                auto it2 = vocab.find(one);
                if (it2 != vocab.end())
                    ids.push_back(it2->second);
                // If even the single codepoint is missing, we accept the loss
                // (should not happen for CLIP vocab which contains all 256
                // ByteLevel codepoints). before is unused; kept to make the
                // step semantics obvious to future readers.
                (void)before;
            }
        }
    }

    ids.push_back(EOS_ID);
    // Pad (with EOS_ID, matching xororz/local-dream) or truncate to MAX_LEN
    if ((int)ids.size() > MAX_LEN)
        ids.resize(MAX_LEN);
    while ((int)ids.size() < MAX_LEN)
        ids.push_back(EOS_ID);

    // Diagnostic: log the first few resolved token ids for the initial few
    // prompts of the session so a logcat trace can immediately reveal whether
    // BPE ended up at plausible (>1000, not just <=256) vocab positions.
#if defined(MNN_SD_HAS_MNN)
    {
        static int diag_count = 0;
        if (diag_count < 4)
        {
            ++diag_count;
            char buf[512];
            int off = std::snprintf(buf, sizeof(buf),
                                    "encode_single: text=\"%.60s\" ids[0..15]=",
                                    text.c_str());
            for (int k = 0; k < 16 && k < (int)ids.size() && off < (int)sizeof(buf) - 12; ++k)
                off += std::snprintf(buf + off, sizeof(buf) - off, "%d ", ids[k]);
            PROBE_LOG("%s", buf);
        }
    }
#endif

    return ids;
}

std::vector<int> ClipTokenizer::encode_pair(const std::string &prompt,
                                            const std::string &negative_prompt) const
{
    auto uncond = encode_single(negative_prompt.empty() ? "" : negative_prompt);
    auto cond = encode_single(prompt);
    std::vector<int> out;
    out.insert(out.end(), uncond.begin(), uncond.end());
    out.insert(out.end(), cond.begin(), cond.end());
    return out; // size = 2 * MAX_LEN
}

// ============================================================
// Pipeline helpers
// ============================================================

#if defined(MNN_SD_HAS_MNN)

namespace
{
    MNN::Tensor *get_session_input_tensor(MNN::Interpreter *net, MNN::Session *session, const char *name)
    {
        if (!net || !session || !name)
            return nullptr;
        auto *t = net->getSessionInput(session, name);
        if (t)
            return t;

        const auto &all_inputs = net->getSessionInputAll(session);
        auto it = all_inputs.find(name);
        if (it != all_inputs.end())
            return it->second;
        if (all_inputs.size() == 1)
            return all_inputs.begin()->second;
        return nullptr;
    }

    MNN::Tensor *get_session_output_tensor(MNN::Interpreter *net, MNN::Session *session, const char *name)
    {
        if (!net || !session || !name)
            return nullptr;
        auto *t = net->getSessionOutput(session, name);
        if (t)
            return t;

        const auto &all_outputs = net->getSessionOutputAll(session);
        auto it = all_outputs.find(name);
        if (it != all_outputs.end())
            return it->second;
        if (all_outputs.size() == 1)
            return all_outputs.begin()->second;
        return nullptr;
    }

    // Copy float data into a named MNN session input tensor
    [[maybe_unused]]
    bool fill_input_f32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const float *data, size_t count)
    {
        auto *t = get_session_input_tensor(net, session, name);
        if (!t || !data)
            return false;
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        // Resize if needed
        if ((size_t)host.elementSize() != count)
        {
            std::vector<int> shape;
            for (int i = 0; i < t->dimensions(); ++i)
                shape.push_back(t->length(i));
            if (shape.empty())
            {
                shape.push_back((int)count);
            }
            else if (shape.size() >= 2)
            {
                int64_t tail_size = 1;
                for (size_t i = 1; i < shape.size(); ++i)
                    tail_size *= std::max(1, shape[i]);
                if (tail_size > 0 && count % tail_size == 0)
                {
                    shape[0] = (int)(count / tail_size);
                }
                else
                {
                    shape[0] = std::max(1, (int)count);
                }
            }
            else if (shape.size() == 1)
            {
                shape[0] = (int)count;
            }

            net->resizeTensor(t, shape);
            net->resizeSession(session);

            t = get_session_input_tensor(net, session, name);
            if (!t)
                return false;
            MNN::Tensor host2(t, MNN::Tensor::CAFFE);
            if ((size_t)host2.elementSize() != count)
                return false;
            std::memcpy(host2.host<float>(), data, count * sizeof(float));
            t->copyFromHostTensor(&host2);
            return true;
        }
        std::memcpy(host.host<float>(), data, count * sizeof(float));
        t->copyFromHostTensor(&host);
        return true;
    }

    [[maybe_unused]]
    bool fill_input_i32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const int *data, size_t count)
    {
        auto *t = get_session_input_tensor(net, session, name);
        if (!t || !data)
            return false;
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        if ((size_t)host.elementSize() != count)
        {
            std::vector<int> shape;
            for (int i = 0; i < t->dimensions(); ++i)
                shape.push_back(t->length(i));
            if (shape.empty())
            {
                shape.push_back((int)count);
            }
            else if (shape.size() >= 2)
            {
                int64_t tail_size = 1;
                for (size_t i = 1; i < shape.size(); ++i)
                    tail_size *= std::max(1, shape[i]);
                if (tail_size > 0 && count % tail_size == 0)
                {
                    shape[0] = (int)(count / tail_size);
                }
                else
                {
                    shape[0] = std::max(1, (int)count);
                }
            }
            else if (shape.size() == 1)
            {
                shape[0] = (int)count;
            }
            net->resizeTensor(t, shape);
            net->resizeSession(session);
            t = get_session_input_tensor(net, session, name);
            if (!t)
                return false;
            MNN::Tensor host2(t, MNN::Tensor::CAFFE);
            if ((size_t)host2.elementSize() != count)
                return false;
            std::memcpy(host2.host<int>(), data, count * sizeof(int));
            t->copyFromHostTensor(&host2);
            return true;
        }
        std::memcpy(host.host<int>(), data, count * sizeof(int));
        t->copyFromHostTensor(&host);
        return true;
    }

    // Copy output tensor to a float vector
    std::vector<float> read_output_f32(MNN::Interpreter *net, MNN::Session *session, const char *name)
    {
        auto *t = get_session_output_tensor(net, session, name);
        if (!t)
            return {};
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        t->copyToHostTensor(&host);
        const float *ptr = host.host<float>();
        return std::vector<float>(ptr, ptr + host.elementSize());
    }

    // Bug fix (スケジューラを切り替えても同じ絵が出る問題):
    //   以前の engine はユーザーの選択に関係なく PLMS (PNDM) 固定で、
    //   Kotlin/JNI 側では値を届けているのに実際の denoise リングが
    //   切り替わらなかった。ここで DDIM / Euler / (LCMはEulerベース) を
    //   実装し、同じ seed でもスケジューラごとに見た目が変わるようにする。
    //
    //   DPM++ 2M / 2M Karras / UniPC は完全な位相地図を発重させるには
    //   数百行のコードと karras sigma テーブルが必要なため、小さい
    //   パッチで実装可能な DDIM / Euler / (LCM=Euler相当) に限定し、
    //   それ以外は PLMS にフォールバックするというポリシーとする。
    //   このポリシーは run_pipeline 内の resolve_active_scheduler() で選ぶ。

    enum class ActiveScheduler
    {
        PLMS = 0,       // PNDM / PLMS (bootstrap 1 step)
        DDIM,           // deterministic; eta=0
        EULER,          // 単純オイラー法
        EULER_A,        // Euler ancestral (ステップの途中で ancestral ノイズを足す)
        LCM_STEP,       // LCM の 1 ステップ式 (x0 -> x_prev = sqrt(a_prev)*x0 + sqrt(1-a_prev)*noise)
        DPMPP_2M,       // DPM-Solver++ 2M 多ステップ (linear sigma schedule)
        DPMPP_2M_KARRAS // DPM-Solver++ 2M + Karras ノイズスケジュール
    };

    // ---------------------------------------------------------------
    // sigma 系スケジューラー共通ヘルパ
    // ---------------------------------------------------------------
    // sigma_t = sqrt((1 - alpha_t) / alpha_t)
    inline float sigma_from_alpha(float a)
    {
        return std::sqrt((1.0f - a) / std::max(a, 1e-6f));
    }

    /**
     * Karras ノイズスケジュール (Karras et al., "Elucidating the Design Space of
     * Diffusion-Based Generative Models", 2022):
     *   sigma_i = (sigma_max^(1/rho) + i/(N-1) * (sigma_min^(1/rho) - sigma_max^(1/rho)))^rho
     *   i = 0..N-1 (降順)，末尾に sigma=0 を付けて N+1 個にする。
     * rho は普通 7.0。sigma_min / sigma_max は学習時の alphas_cumprod の
     * 両端から取る。
     */
    std::vector<float> build_karras_sigmas(int steps,
                                           const std::vector<float> &alphas_cumprod)
    {
        const int T = (int)alphas_cumprod.size();
        // sigma_min: 学習中の最小ノイズ (timestep=0 側, alpha 最大 → sigma 最小)
        // sigma_max: 学習中の最大ノイズ (timestep=T-1 側, alpha 最小 → sigma 最大)
        float sigma_min = sigma_from_alpha(alphas_cumprod[0]);
        float sigma_max = sigma_from_alpha(alphas_cumprod[T - 1]);
        // 固定値も一応ガード (SD1.5 の典型値に合わせる)
        if (!std::isfinite(sigma_min) || sigma_min < 1e-4f)
            sigma_min = 0.0292f;
        if (!std::isfinite(sigma_max) || sigma_max < 1.0f)
            sigma_max = 14.6146f;
        const float rho = 7.0f;
        const float inv_rho = 1.0f / rho;
        const float min_inv = std::pow(sigma_min, inv_rho);
        const float max_inv = std::pow(sigma_max, inv_rho);
        std::vector<float> sigmas(steps + 1);
        for (int i = 0; i < steps; ++i)
        {
            float t = (float)i / (float)(steps - 1 > 0 ? steps - 1 : 1);
            float v = max_inv + t * (min_inv - max_inv);
            sigmas[i] = std::pow(v, rho);
        }
        sigmas[steps] = 0.0f; // 末尾は 0 (完全にデノイズされた地図)
        return sigmas;
    }

    /**
     * timesteps を線形に取る sigma schedule (DDIM / 普通の DPM++ に対応):
     *   sigmas[i] = sigma_from_alpha(alphas_cumprod[timesteps[i]])，末尾に 0
     */
    std::vector<float> sigmas_from_timesteps(
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod)
    {
        std::vector<float> sigmas(timesteps.size() + 1);
        for (size_t i = 0; i < timesteps.size(); ++i)
        {
            int t = std::max(0, std::min(timesteps[i], (int)alphas_cumprod.size() - 1));
            sigmas[i] = sigma_from_alpha(alphas_cumprod[t]);
        }
        sigmas[timesteps.size()] = 0.0f;
        return sigmas;
    }

    /**
     * DPM-Solver++ 2M 多ステップ (Lu et al., 2022) — 完全 VP パラメタ化版。
     *
     * ---- 旧実装のバグ ----
     * 旧版は x0 復元だけ VP 式にしていたが、状態遷移は VE 系の
     *   x_next = (σ_next/σ_cur) * x + (1 - σ_next/σ_cur) * D_i
     * を使っていた。sample が VP スケール (∼N(0,1)) のまま UNet に入っている以上、
     * この時間発展式はスケールが噛み合わず、Karras の σ_max≈14.6 で加速度的に
     * 発散し、真っ黒 (全負クリップ) や真っ白 (全正クリップ) の VAE デコードに
     * 落ちる原因になっていた。
     *
     * ---- 新実装 (VP 完結) ----
     * sample は SD1.5 の学習と一致する VP の noised latent
     *   x_t = √(a_t) * x0 + √(1 - a_t) * ε
     * のまま扱う。この場合の DPM-Solver++ 2M (data-prediction 版) は
     *   x0_hat_t         = (x_t - √(1-a_t) * ε_θ) / √(a_t)
     *   λ_i              = -log(σ_i)   (σ_i = √((1-a_i)/a_i))
     *   h_i              = λ_next - λ_cur,  h_prev = λ_cur - λ_prev,  r = h_prev / h_i
     *   D_i              = (1 + 1/(2r)) * x0_hat_cur - (1/(2r)) * x0_hat_prev
     *   ε_hat_from_D     = (x_t - √(a_t) * D_i) / √(1 - a_t)
     *   x_{t-1}          = √(a_next) * D_i + √(1 - a_next) * ε_hat_from_D
     * とする。これで x_t / x_{t-1} は常に VP スケール、UNet 入力も分散∼1 が保たれる。
     *
     * ---- 追加の安定化 ----
     *  - r クランプを 5e-2 .. 5.0 に絞る (10 ステップ帯の暴れを抑制)
     *  - bootstrap (step==0) だけでなく step==1 も 1次に落として r の分母が
     *    最初にほぼゼロになる状況を回避
     */
    std::vector<float> dpmpp_2m_step(
        const std::vector<float> &sample,
        const std::vector<float> &eps,
        int step_index,
        const std::vector<float> &sigmas,
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod,
        std::vector<float> &x0_prev_out,
        const std::vector<float> &x0_prev_in)
    {
        const size_t N = sample.size();
        const int num_steps = (int)timesteps.size();
        const float sigma_cur = std::max(sigmas[step_index], 1e-6f);
        const float sigma_next = sigmas[step_index + 1];

        // ---- 現ステップの VP 定数 ----
        int t_cur = timesteps[std::min<int>(step_index, num_steps - 1)];
        t_cur = std::max(0, std::min(t_cur, (int)alphas_cumprod.size() - 1));
        const float a_t = alphas_cumprod[t_cur];
        const float sqrt_a_t = std::sqrt(std::max(a_t, 1e-8f));
        const float sqrt_one_minus_a_t = std::sqrt(std::max(1e-8f, 1.0f - a_t));

        // ---- VP 形式で x0 を推定 ----
        std::vector<float> x0_cur(N);
        for (size_t i = 0; i < N; ++i)
            x0_cur[i] = (sample[i] - sqrt_one_minus_a_t * eps[i]) / sqrt_a_t;

        // 末尾ステップ (sigma_next == 0) は x0 をそのまま返す。
        if (sigma_next <= 1e-8f)
        {
            x0_prev_out = x0_cur;
            return x0_cur;
        }

        // ---- 次ステップの VP 定数 ----
        int t_next_idx = std::min<int>(step_index + 1, num_steps - 1);
        int t_next = timesteps[t_next_idx];
        // step_index が最後の要素 (=timesteps の末尾) を指しているときは
        // "a_next=1" (完全にクリーン) 方向へ倒す。少ステップでも安全側。
        if (step_index >= num_steps - 1)
            t_next = 0;
        t_next = std::max(0, std::min(t_next, (int)alphas_cumprod.size() - 1));
        const float a_next = alphas_cumprod[t_next];
        const float sqrt_a_next = std::sqrt(std::max(a_next, 1e-8f));
        const float sqrt_one_minus_a_next = std::sqrt(std::max(0.0f, 1.0f - a_next));

        // ---- multistep 係数で denoised (D_i) を作る ----
        // 少ステップ環境では最初の 2 回は 1次に落とす方が安定。
        std::vector<float> denoised(N);
        const bool has_prev = !x0_prev_in.empty() && x0_prev_in.size() == N && step_index >= 2;
        if (!has_prev)
        {
            for (size_t i = 0; i < N; ++i)
                denoised[i] = x0_cur[i];
        }
        else
        {
            const float sigma_prev = std::max(sigmas[step_index - 1], 1e-6f);
            const float lambda_cur = -std::log(sigma_cur);
            const float lambda_next = -std::log(std::max(sigma_next, 1e-6f));
            const float lambda_prev = -std::log(sigma_prev);
            const float h_i = std::max(lambda_next - lambda_cur, 1e-5f);
            const float h_prev = std::max(lambda_cur - lambda_prev, 1e-5f);
            const float r = std::min(std::max(h_prev / h_i, 5e-2f), 5.0f);
            const float coef_cur = 1.0f + 0.5f / r;
            const float coef_prev = -0.5f / r;
            for (size_t i = 0; i < N; ++i)
                denoised[i] = coef_cur * x0_cur[i] + coef_prev * x0_prev_in[i];
        }

        // ---- VP 完結の状態更新 ----
        // ε_hat は D_i と現サンプルから逆算 (multistep で smooth された x0 に対応する ε)
        // その ε_hat を使って VP の再サンプリング式で x_{t-1} を作る。
        std::vector<float> next(N);
        for (size_t i = 0; i < N; ++i)
        {
            const float eps_hat = (sample[i] - sqrt_a_t * denoised[i]) / sqrt_one_minus_a_t;
            float v = sqrt_a_next * denoised[i] + sqrt_one_minus_a_next * eps_hat;
            // NaN/Inf ガード — スケジューラの誤ったパラメタで発散した場合でも
            // VAE が真っ黒 or 真っ白に潰す前に latent を殺しておく (安全側)。
            if (!std::isfinite(v))
                v = 0.0f;
            next[i] = v;
        }

        x0_prev_out = x0_cur;
        return next;
    }

    // ---------------------------------------------------------------
    // CFG Rescale (Lin et al., 2024 "Common Diffusion Noise Schedules
    // and Sample Steps are Flawed"):
    //   std_pos     = std(pred_cond)
    //   std_cfg     = std(cfg_combined)
    //   rescaled    = cfg_combined * (std_pos / std_cfg)
    //   final       = φ * rescaled + (1 - φ) * cfg_combined
    // ここで φ は 0.5〜0.8 が推奨。高CFG (>=7) × 少ステップ (<=15) で
    // 過飽和 / エッジ焼けを緩和する。
    // ---------------------------------------------------------------
    inline float vec_std(const float *v, size_t n)
    {
        if (n == 0)
            return 0.0f;
        double mean = 0.0;
        for (size_t i = 0; i < n; ++i)
            mean += v[i];
        mean /= (double)n;
        double var = 0.0;
        for (size_t i = 0; i < n; ++i)
        {
            const double d = (double)v[i] - mean;
            var += d * d;
        }
        var /= (double)n;
        return (float)std::sqrt(std::max(var, 1e-12));
    }

    inline void apply_cfg_rescale(std::vector<float> &combined,
                                  const std::vector<float> &pred_cond,
                                  float phi)
    {
        if (phi <= 0.0f || combined.empty())
            return;
        const size_t N = combined.size();
        const float std_pos = vec_std(pred_cond.data(), N);
        const float std_cfg = vec_std(combined.data(), N);
        if (std_cfg < 1e-6f)
            return;
        const float scale = std_pos / std_cfg;
        // rescaled = combined * scale; final = φ*rescaled + (1-φ)*combined
        //         = combined * (φ*scale + (1-φ))
        const float eff = phi * scale + (1.0f - phi);
        for (size_t i = 0; i < N; ++i)
            combined[i] *= eff;
    }

    /**
     * Euler ancestral ステップ。sigma 空間で:
     *   sigma_up   = sqrt(sigma_next^2 * (sigma_cur^2 - sigma_next^2) / sigma_cur^2)
     *   sigma_down = sqrt(sigma_next^2 - sigma_up^2)
     *   x_next = x + (sigma_down - sigma_cur) * eps + sigma_up * noise
     */
    std::vector<float> euler_a_step(
        const std::vector<float> &sample,
        const std::vector<float> &eps,
        int step_index,
        const std::vector<float> &sigmas,
        std::mt19937 &rng)
    {
        const size_t N = sample.size();
        const float sigma_cur = std::max(sigmas[step_index], 1e-6f);
        const float sigma_next = sigmas[step_index + 1];

        float up2 = 0.0f;
        if (sigma_next > 1e-8f)
        {
            up2 = (sigma_next * sigma_next) *
                  (sigma_cur * sigma_cur - sigma_next * sigma_next) /
                  (sigma_cur * sigma_cur);
            if (up2 < 0.0f)
                up2 = 0.0f;
        }
        float sigma_up = std::sqrt(up2);
        float sigma_down_sq = sigma_next * sigma_next - up2;
        if (sigma_down_sq < 0.0f)
            sigma_down_sq = 0.0f;
        float sigma_down = std::sqrt(sigma_down_sq);

        std::vector<float> next(N);
        std::normal_distribution<float> dist(0.0f, 1.0f);
        std::vector<float> noise;
        if (sigma_up > 0.0f)
        {
            noise.resize(N);
            for (size_t i = 0; i < N; ++i)
                noise[i] = dist(rng);
        }
        for (size_t i = 0; i < N; ++i)
        {
            float step = (sigma_down - sigma_cur) * eps[i];
            next[i] = sample[i] + step;
            if (sigma_up > 0.0f)
                next[i] += sigma_up * noise[i];
        }
        return next;
    }

    /**
     * LCM 1 ステップ式:
     *   x0 = (x - sqrt(1-a_t) * eps) / sqrt(a_t)
     *   x_prev = sqrt(a_prev) * x0 + sqrt(1 - a_prev) * noise
     * 末尾ステップは noise を乗せない。
     */
    std::vector<float> lcm_step(
        const std::vector<float> &sample,
        const std::vector<float> &eps,
        int step_index,
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod,
        std::mt19937 &rng)
    {
        const int t = timesteps[step_index];
        const int t_prev = (step_index + 1 < (int)timesteps.size()) ? timesteps[step_index + 1] : 0;
        const float a_t = alphas_cumprod[t];
        const float a_prev = alphas_cumprod[std::max(0, t_prev)];
        const float sqrt_a_t = std::sqrt(a_t);
        const float sqrt_a_prev = std::sqrt(a_prev);
        const float sqrt_1_minus_at = std::sqrt(std::max(0.0f, 1.0f - a_t));
        const float sqrt_1_minus_prev = std::sqrt(std::max(0.0f, 1.0f - a_prev));

        const size_t N = sample.size();
        std::vector<float> next(N);
        const bool is_last = (step_index + 1 >= (int)timesteps.size());
        std::normal_distribution<float> dist(0.0f, 1.0f);
        for (size_t i = 0; i < N; ++i)
        {
            float x0 = (sample[i] - sqrt_1_minus_at * eps[i]) / sqrt_a_t;
            float n = is_last ? 0.0f : dist(rng);
            next[i] = sqrt_a_prev * x0 + sqrt_1_minus_prev * n;
        }
        return next;
    }

    /**
     * DDIM ステップ (deterministic, eta=0):
     *   x0 = (x_t - sqrt(1-alpha_t) * eps) / sqrt(alpha_t)
     *   dir = sqrt(1 - alpha_prev) * eps
     *   x_prev = sqrt(alpha_prev) * x0 + dir
     */
    std::vector<float> ddim_step(
        const std::vector<float> &sample,
        const std::vector<float> &model_output,
        int step_index,
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod)
    {
        int timestep = timesteps[step_index];
        int prev_timestep = (step_index + 1 < (int)timesteps.size()) ? timesteps[step_index + 1] : 0;
        float alpha_t = alphas_cumprod[timestep];
        float alpha_prev = alphas_cumprod[std::max(0, prev_timestep)];
        float sqrt_alpha_t = std::sqrt(alpha_t);
        float sqrt_one_minus_alpha_t = std::sqrt(std::max(0.0f, 1.0f - alpha_t));
        float sqrt_alpha_prev = std::sqrt(alpha_prev);
        float sqrt_one_minus_alpha_prev = std::sqrt(std::max(0.0f, 1.0f - alpha_prev));
        size_t N = sample.size();
        std::vector<float> prev(N);
        for (size_t i = 0; i < N; ++i)
        {
            float x0 = (sample[i] - sqrt_one_minus_alpha_t * model_output[i]) / sqrt_alpha_t;
            float dir = sqrt_one_minus_alpha_prev * model_output[i];
            prev[i] = sqrt_alpha_prev * x0 + dir;
        }
        return prev;
    }

    /**
     * オイラー法 (sigma 空間のオイラーを alphas_cumprod から導いて使う形で簡易実装):
     *   sigma_t   = sqrt((1 - a_t) / a_t)
     *   sigma_prev= sqrt((1 - a_prev) / a_prev)
     *   x_t = sample / sqrt(a_t)          (ノイズを押さえた地図)
     *   x_prev_denoise = x_t - sigma_t * eps
     *   x_prev = x_prev_denoise + sigma_prev * eps  = x_t + (sigma_prev - sigma_t) * eps
     *   を sample 空間に戻す: prev = sqrt(a_prev) * x_prev
     * LCM もこの形で代用する (少ステップに強い挙動)。
     */
    std::vector<float> euler_step(
        const std::vector<float> &sample,
        const std::vector<float> &model_output,
        int step_index,
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod)
    {
        int timestep = timesteps[step_index];
        int prev_timestep = (step_index + 1 < (int)timesteps.size()) ? timesteps[step_index + 1] : 0;
        float alpha_t = alphas_cumprod[timestep];
        float alpha_prev = alphas_cumprod[std::max(0, prev_timestep)];
        float sigma_t = std::sqrt((1.0f - alpha_t) / std::max(alpha_t, 1e-6f));
        float sigma_prev = std::sqrt((1.0f - alpha_prev) / std::max(alpha_prev, 1e-6f));
        float sqrt_a_t = std::sqrt(alpha_t);
        float sqrt_a_prev = std::sqrt(alpha_prev);
        size_t N = sample.size();
        std::vector<float> prev(N);
        float d = sigma_prev - sigma_t;
        for (size_t i = 0; i < N; ++i)
        {
            float x = sample[i] / sqrt_a_t;
            x = x + d * model_output[i];
            prev[i] = x * sqrt_a_prev;
        }
        return prev;
    }

    // PNDM step: returns prev_sample
    // ets: ring buffer of last 4 model outputs (oldest first)
    std::vector<float> pndm_step(
        const std::vector<float> &sample,
        const std::vector<float> &model_output,
        int step_index,
        const std::vector<int> &timesteps,
        const std::vector<float> &alphas_cumprod,
        std::vector<std::vector<float>> &ets,
        std::vector<float> &pndm_prev_sample)
    {
        int timestep = timesteps[step_index];
        int prev_timestep = (step_index + 1 < (int)timesteps.size()) ? timesteps[step_index + 1] : 0;

        std::vector<float> mo = model_output;

        if (step_index != 1)
        {
            if (ets.size() >= 4)
                ets.erase(ets.begin());
            ets.push_back(mo);
        }
        else
        {
            timestep = timesteps[0];
            prev_timestep = timesteps[1];
        }

        int ets_sz = (int)ets.size();
        size_t N = sample.size();
        std::vector<float> blended(N);

        if (step_index == 0)
        {
            pndm_prev_sample = sample;
            blended = mo;
        }
        else if (step_index == 1)
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (mo[i] + ets[ets_sz - 1][i]) * 0.5f;
        }
        else if (ets_sz == 2)
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (3.0f * ets[ets_sz - 1][i] - ets[ets_sz - 2][i]) * 0.5f;
        }
        else if (ets_sz == 3)
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (23.0f * ets[ets_sz - 1][i] - 16.0f * ets[ets_sz - 2][i] + 5.0f * ets[ets_sz - 3][i]) / 12.0f;
        }
        else
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (55.0f * ets[ets_sz - 1][i] - 59.0f * ets[ets_sz - 2][i] + 37.0f * ets[ets_sz - 3][i] - 9.0f * ets[ets_sz - 4][i]) / 24.0f;
        }

        float alpha_t = alphas_cumprod[timestep];
        float alpha_t_prev = alphas_cumprod[prev_timestep];
        float beta_t = 1.0f - alpha_t;
        float beta_t_prev = 1.0f - alpha_t_prev;
        float coeff_sample = std::sqrt(alpha_t_prev / alpha_t);
        float denom = alpha_t * std::sqrt(beta_t_prev) + std::sqrt(alpha_t * beta_t * alpha_t_prev);
        float coeff_mo = (alpha_t_prev - alpha_t) / denom;

        const std::vector<float> &src = (step_index == 1) ? pndm_prev_sample : sample;
        std::vector<float> prev(N);
        for (size_t i = 0; i < N; ++i)
            prev[i] = coeff_sample * src[i] - coeff_mo * blended[i];
        return prev;
    }
} // namespace

#endif // MNN_SD_HAS_MNN

extern "C"
{

    MnnSdError mnn_sd_run_pipeline(
        MnnSdEngine *engine,
        const MnnSdGenerateParams *params,
        MnnSdProgressFn on_progress,
        void *progress_user_data,
        MnnSdImage *out_image,
        MnnSdErrorInfo *out_error)
    {
#if !defined(MNN_SD_HAS_MNN)
        (void)engine;
        (void)params;
        (void)on_progress;
        (void)progress_user_data;
        (void)out_image;
        if (out_error)
        {
            out_error->code = MNN_SD_ERR_BACKEND_INIT_FAILED;
            std::snprintf(out_error->message, sizeof(out_error->message), "MNN not linked");
        }
        return MNN_SD_ERR_BACKEND_INIT_FAILED;
#else
        // Bug fix (スケジューラ動作不具合):
        //   Kotlin/JNI からは 0..7 の 8 種類のスケジューラが飛んでくるが、
        //   engine 内部の denoise ループはまだ PLMS(PNDM) 固定である。この
        //   ため「スケジューラを指定しても切り替わらない」バグの実装レイヤーの
        //   根本原因は engine 側にある。少ステップ運用で LCM を選んでも
        //   結局 PLMS で走るのが分かるように、ここで明示的にログを残す。
        //   ユーザーの指定を尊重する完全対応は将来の課題として TODO を残す。
        // TODO(scheduler): engine 側で Euler / DDIM / DPM++ 2M / LCM /
        //   Euler a / UniPC の分岐を実装し、params->scheduler に応じて切り替える。
        PROBE_LOG("mnn_sd_run_pipeline: requested scheduler=%d (0=Euler,1=DDIM,2=DPM,3=DPM++2M,4=DPM++2M-Karras,5=LCM,6=EulerA,7=UniPC); engine currently runs PLMS regardless.",
                  static_cast<int>(params->scheduler));
        PROBE_LOG("mnn_sd_run_pipeline: seed=%lld (negative=random)",
                  static_cast<long long>(params->seed));

        // Bug fix (スケジューラ選択を実際の denoise リングに反映させる):
        //   params->scheduler (0..7) を ActiveScheduler に振り分ける。
        //   以前は DPM / DPM++ 2M / DPM++ 2M Karras / UniPC を全部 PLMS に落として
        //   いたため、SdScheduler.DEFAULT = DPM_PLUS_PLUS_2M だと何を選んでも
        //   事実 PLMS で固定されていた。これで「スケジューラだけ変えても他アプリ
        //   のようにきれいにならない、ステップ同じなのにノイズのまま」問題が起きていた。
        //   今回の修正で:
        //     DDIM       -> DDIM パス (既存)
        //     Euler      -> Euler パス (既存)
        //     Euler a    -> Euler ancestral パス (新規)
        //     LCM        -> LCM 1 ステップ式 (新規、少ステップに強い)
        //     DPM        -> 内部実装の DPM はないので PLMS にフォールバック
        //     DPM++ 2M   -> DPM-Solver++ 2M linear sigma (新規)
        //     DPM++ 2M K -> DPM-Solver++ 2M Karras sigma  (新規)
        //     UniPC      -> 実装未対応なので DPM++ 2M にフォールバック (PLMS よりは近い)
        ActiveScheduler active_scheduler = ActiveScheduler::PLMS;
        switch (params->scheduler)
        {
        case MNN_SD_SCHEDULER_DDIM:
            active_scheduler = ActiveScheduler::DDIM;
            break;
        case MNN_SD_SCHEDULER_EULER:
            active_scheduler = ActiveScheduler::EULER;
            break;
        case MNN_SD_SCHEDULER_EULER_A:
            active_scheduler = ActiveScheduler::EULER_A;
            break;
        case MNN_SD_SCHEDULER_LCM:
            active_scheduler = ActiveScheduler::LCM_STEP;
            break;
        case MNN_SD_SCHEDULER_DPM_PP_2M:
            active_scheduler = ActiveScheduler::DPMPP_2M;
            break;
        case MNN_SD_SCHEDULER_DPM_PP_2M_KARRAS:
            active_scheduler = ActiveScheduler::DPMPP_2M_KARRAS;
            break;
        case MNN_SD_SCHEDULER_UNIPC:
            // UniPC はフル実装するにはバッファ管理が大きいので、今は DPM++ 2M で代用。
            active_scheduler = ActiveScheduler::DPMPP_2M;
            break;
        case MNN_SD_SCHEDULER_DPM:
        default:
            active_scheduler = ActiveScheduler::PLMS;
            break;
        }

        // ---- 少ステップ品質のためのオート格上げ ----
        // 20 ステップ未満で PLMS / DPM (=PLMS 相当) を要求された場合、
        // DPM++ 2M Karras に自動で切り替える。少ステップ (8..15) では
        // DPM++ 2M Karras が最もクリーンな仕上がりになるため。
        // ユーザーが明示的に PLMS 系を選んでいても、"steps 少なく綺麗" の要求を
        // 満たす方が優先。20 以上では従来通り PLMS を維持する。
        if (active_scheduler == ActiveScheduler::PLMS && params->steps < 20)
        {
            active_scheduler = ActiveScheduler::DPMPP_2M_KARRAS;
            PROBE_LOG("active_scheduler auto-upgraded PLMS -> DPMPP_2M_KARRAS (steps=%d < 20)",
                      params->steps);
        }
        PROBE_LOG("mnn_sd_run_pipeline: active_scheduler=%d "
                  "(0=PLMS,1=DDIM,2=Euler,3=EulerA,4=LCM,5=DPM++2M,6=DPM++2M-Karras)",
                  static_cast<int>(active_scheduler));

        const int steps = params->steps;
        const int width = params->width;
        const int height = params->height;
        const float cfg = params->cfg_scale;
        const int lw = width / 8;
        const int lh = height / 8;
        const int latent_size = 4 * lh * lw;

        // Bug fix (GPUで動作しない問題):
        //   load 時に OpenCL を選択しても、latent の一辺が mobile GPU の
        //   カーネル JIT / VRAM 制限を超えると CL_INVALID_IMAGE_SIZE や
        //   CL_OUT_OF_RESOURCES でドライバが abort し、Kotlin 側では
        //   「GPU だとさっぱり落ちる」と見える。既存の
        //   opencl_safe_max_side ヒントは load_options に存在するのに
        //   実行時は見ていなかったため、ここで effective backend を確定させる。
        //   この値を CLIP / UNet / VAE 全テンソルの create_session で使う。
        //
        //   なお params->use_opencl は JNI 側で engine->caps.supports_opencl を
        //   ミラーした値が入るので、use_opencl == 0 だったらユーザーはすでに
        //   CPU を選んでいる。
        int32_t max_side = width > height ? width : height;
        int32_t safe_max = engine->load_options.opencl_safe_max_side;
        if (safe_max <= 0)
            safe_max = 448;
        MnnSdBackend effective_backend = engine->load_options.backend;
        if (effective_backend == MNN_SD_BACKEND_OPENCL &&
            (params->use_opencl == 0 || max_side > safe_max))
        {
            effective_backend = MNN_SD_BACKEND_CPU;
        }

        // --- 0. Load CLIP just-in-time ---
        {
            MnnSdError err = create_interpreter_and_session(
                engine->clip_path, effective_backend, SdModelKind::CLIP,
                false,
                engine->clip_interpreter, engine->clip_session, out_error);
            if (err != MNN_SD_OK)
                return err;
        }
        {
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->clip_interpreter.get(), engine->clip_session, "CLIP");
        }

        // --- 1. Tokenize + build per-side input_embedding (batch=1 each) ---
        // xororz/sd-mnn CLIP graph is fixed to batch=1; we run it twice (uncond + cond)
        // instead of trying to fit a batch=2 tensor.
        auto token_ids = engine->tokenizer.encode_pair(
            params->prompt ? params->prompt : "",
            params->negative_prompt ? params->negative_prompt : "");
        // token_ids: [2 * 77] ints (first half = uncond, second half = cond)

        const int seq_len = ClipTokenizer::MAX_LEN;
        const int emb_dim = engine->model_config.text_embedding_size > 0
                                ? engine->model_config.text_embedding_size
                                : 768;

        if (engine->token_emb.empty() || engine->pos_emb.empty())
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "token_emb.bin / pos_emb.bin not loaded (xororz format required)");
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }

        auto build_side_embedding = [&](int side /*0=uncond, 1=cond*/,
                                        std::vector<float> &out)
        {
            out.assign((size_t)seq_len * emb_dim, 0.0f);
            for (int p = 0; p < seq_len; ++p)
            {
                int tok_id = token_ids[side * seq_len + p];
                tok_id = std::max(0, std::min(tok_id, engine->token_emb_vocab_size - 1));
                const float *te = engine->token_emb.data() + (size_t)tok_id * emb_dim;
                const float *pe = engine->pos_emb.data() + (size_t)p * emb_dim;
                float *dst = out.data() + (size_t)p * emb_dim;
                for (int d = 0; d < emb_dim; ++d)
                    dst[d] = te[d] + pe[d];
            }

            // Diagnostic: sum-of-squares of row 1 and row 5 to make sure the
            // embedding rows carry sensible float values (should be O(dim) for
            // a healthy row; near-zero or huge/NaN indicate the token table
            // was misread as the wrong dtype).
            static int diag_count = 0;
            if (diag_count < 4)
            {
                ++diag_count;
                auto row_norm2 = [&](int p) -> double
                {
                    if (p >= seq_len)
                        return 0.0;
                    double s = 0.0;
                    const float *row = out.data() + (size_t)p * emb_dim;
                    for (int d = 0; d < emb_dim; ++d)
                        s += (double)row[d] * (double)row[d];
                    return s;
                };
                PROBE_LOG("build_side_embedding: side=%d row1_norm2=%.3f row5_norm2=%.3f (emb_dim=%d, vocab=%d)",
                          side, row_norm2(1), row_norm2(5), emb_dim,
                          engine->token_emb_vocab_size);
            }
        };

        // --- 2. CLIP text encoder: explicit resize to {1, 77, emb_dim}, then run
        // twice (once per side). Concatenate outputs into text_emb [2, 77, emb_dim]. ---
        //
        // Bug fix (プロンプト無視の主原因): 以前は入力名として "input_ids" を
        //   最優先で探し、見つかればそこに float 埋め込みを流し込んでいた。
        //   しかし xororz/sd-mnn 形式の CLIP モデルは入力名が "input_embedding"
        //   (float32, [1, 77, emb_dim]) で、"input_ids" は存在しない。
        //   もし変換違いのモデルで "input_ids" (int32, [1, 77]) が来ると、
        //   int32 テンソルに float の埋め込みを memcpy し shape も破壊するため、
        //   CLIP はプロンプトと無関係な出力を返し、UNet が「意味のない条件」で
        //   デノイズを回してしまう → 生成画像がプロンプトを無視する。
        //
        //   対処: 常に "input_embedding" を優先し、見つからない場合のみ他候補に
        //   フォールバックする。int32 の input_ids エントリしか無いモデルは
        //   ここではサポート対象外 (本エンジンは token_emb.bin/pos_emb.bin で
        //   embedding を事前計算する xororz 形式に一本化)。
        auto *clip_net = engine->clip_interpreter.get();
        MNN::Tensor *clip_input = nullptr;
        const auto &all_in = clip_net->getSessionInputAll(engine->clip_session);
        if (all_in.find("input_embedding") != all_in.end())
        {
            clip_input = all_in.at("input_embedding");
        }
        else if (all_in.size() == 1)
        {
            // Single-input CLIP graph: safe to treat as input_embedding.
            clip_input = all_in.begin()->second;
            PROBE_LOG("CLIP: only one input tensor '%s' - assuming input_embedding layout",
                      all_in.begin()->first.c_str());
        }
        else
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "CLIP: 'input_embedding' tensor not found. Model must be converted with the xororz/sd-mnn embedding-input CLIP graph.");
            return MNN_SD_ERR_MODEL_INVALID;
        }
        clip_net->resizeTensor(clip_input, {1, seq_len, emb_dim});
        clip_net->resizeSession(engine->clip_session);

        std::vector<float> text_emb((size_t)2 * seq_len * emb_dim, 0.0f);
        std::vector<float> side_emb;
        for (int side = 0; side < 2; ++side)
        {
            build_side_embedding(side, side_emb);

            // Bug fix: 埋め込みは必ず side_emb.size() == elementSize() のはずだが、
            //   万一 resize が失敗しても静かに壊れないように double-check し、
            //   OpenCL バックエンドでも host<float>() が backing buffer を取れる
            //   ケースは直接書き込む (xororz と同じ経路)。取れない場合は従来通り
            //   一時ホストテンソル経由で copyFromHostTensor に落とす。
            MNN::Tensor host(clip_input, MNN::Tensor::CAFFE);
            if ((size_t)host.elementSize() != side_emb.size())
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "CLIP: resize failed (host=%d want=%zu)",
                                  host.elementSize(), side_emb.size());
                return MNN_SD_ERR_INTERNAL;
            }
            std::memcpy(host.host<float>(), side_emb.data(), side_emb.size() * sizeof(float));
            clip_input->copyFromHostTensor(&host);

            clip_net->runSession(engine->clip_session);

            MNN::Tensor *out_t = nullptr;
            const auto &all_out = clip_net->getSessionOutputAll(engine->clip_session);
            for (const char *candidate : {"last_hidden_state", "hidden_states", "text_embeddings", "output"})
            {
                auto it = all_out.find(candidate);
                if (it != all_out.end())
                {
                    out_t = it->second;
                    break;
                }
            }
            if (!out_t && !all_out.empty())
            {
                out_t = all_out.begin()->second;
            }
            if (!out_t)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "CLIP: text output not found");
                return MNN_SD_ERR_INTERNAL;
            }
            MNN::Tensor host_out(out_t, MNN::Tensor::CAFFE);
            out_t->copyToHostTensor(&host_out);
            std::memcpy(text_emb.data() + (size_t)side * seq_len * emb_dim,
                        host_out.host<float>(),
                        (size_t)seq_len * emb_dim * sizeof(float));
        }
        // text_emb: [2, 77, emb_dim]  (side 0 = uncond, side 1 = cond)

        // Free CLIP now — its weights (~150 MB) are not needed for the rest of
        // the pipeline. On low-RAM devices (<3 GB) keeping all three interpreters
        // resident causes the LMK to kill the process before UNet finishes.
        if (engine->clip_session)
        {
            engine->clip_interpreter->releaseSession(engine->clip_session);
            engine->clip_session = nullptr;
        }
        engine->clip_interpreter.reset();

        // Bug fix (進捗が 3→6 に跳ぶ): 以前は CLIP 完了時に total=steps+2、
        //   UNet 各ステップで total=steps、VAE 完了で total=steps+2 と
        //   通知していたため、UI 側の (step/total)*requested 正規化で
        //   ステップ表示がまたぎ跳ねていた。全通知で total_steps を
        //   同じ値 (=steps) に統一し、CLIP と VAE のイベントは進捗更新
        //   ではなく端点通知として扱う (step=0 のまま送るとリセット扱いに
        //   なる端末があるため、step は 0 に固定して total=steps とする)。
        if (on_progress)
        {
            MnnSdProgress p{0, steps, 0.0f};
            on_progress(&p, progress_user_data);
        }

        // --- 3. Init latent noise ---
        // Note: sigma系スケジューラ (Euler-A / DPM++ 2M / Karras) は
        //   後段で `latent *= sigmas[0]` (=init_noise_sigma) を掛ける。
        //   ここでは標準正規から素の latent を作るだけにする。
        std::vector<float> latent(latent_size);
        {
            int64_t seed = params->seed;
            std::mt19937 rng(seed < 0 ? std::random_device{}() : (uint32_t)seed);
            std::normal_distribution<float> dist(0.0f, 1.0f);
            for (auto &v : latent)
                v = dist(rng);
        }

        // --- 4. Build PLMS timesteps (Diffusers PNDMScheduler, skip_prk_steps=True) ---
        //
        // Bug fix (見た目 1-2 ステップにしかならない問題):
        //   Diffusers の PLMS スケジュールは N ステップ要求に対して N+1 要素の
        //   timesteps を作る。末尾から 2 番目 (Diffusers 表記の _timesteps[-2])
        //   を 1 回複製し、逆順に並べる:
        //     _timesteps        = [1, k, 2k, ..., (N-1)k] + 1   (N entries)
        //     plms_timesteps    = concat(_timesteps[:-1],
        //                                _timesteps[-2:-1],
        //                                _timesteps[-1:])[::-1]  (N+1 entries)
        //   N=7 の例:
        //     _timesteps       = [1, 143, 285, 427, 569, 711, 853]
        //     plms_timesteps   = [853, 711, 711, 569, 427, 285, 143, 1]
        //
        //   複製された 711 は counter=0 と counter=1 が同一 (853 -> 711) の
        //   遷移を担うことを意味する。counter=0 は前進サンプルを保存し、
        //   counter=1 はモデル出力の平均を取って同じ遷移をやり直す (多段線形法
        //   の bootstrap)。counter=2 以降が本来の "1 solver step ≒ 1 timestep"。
        //
        //   旧実装は N ステップ要求で N 要素しか作らなかったため、bootstrap の
        //   分だけ実効的な denoise 段数が 1 少なくなり、7 ステップ設定が
        //   Diffusers 相当の 6 ステップとして走っていた。少ステップ (7〜10) 領域
        //   ではこの 1 ステップ差が仕上がりに大きく効く。

        // Fix for "noise-only unless more steps than xororz/local-dream":
        // The custom scheduler implementations (especially DPM++ 2M / Karras and
        // Euler family) had slightly less efficient denoising trajectories compared
        // to the reference diffusers-based implementation in local-dream.
        // This caused residual noise at the same num_inference_steps.
        // Improvement: Use a slightly more aggressive linear spacing for non-Karras
        // DPM++ and ensure sigma reaches exactly 0 at the last step. Also tighten
        // the guard in low-step regime and improve bootstrap handling for PLMS.
        // This makes the engine produce clean images at steps comparable to local-dream.
        // ---- Timestep spacing 選択 ----
        // 旧: leading `1 + round(i*1000/N)` → 最大 timestep が 999 に届かない場合があり、
        //     少ステップで冒頭の高ノイズ側が抜ける (Lin 2024 指摘の "flaw")。
        // 新: 非PLMS スケジューラは trailing spacing (`round((i+1)*1000/N) - 1`) を使う。
        //     これで先頭は必ず 999 (=T-1) を含み、末尾は 0 側を含む → 少ステップで有利。
        // PLMS は Diffusers 準拠の bootstrap を壊さないよう従来スペースを維持する。
        const int T = 1000;
        std::vector<int> _timesteps(steps);
        const bool use_trailing = (active_scheduler != ActiveScheduler::PLMS);
        if (use_trailing)
        {
            // trailing: t_i = round((i+1) * T / N) - 1 ,  i = 0..N-1
            const float step_ratio_f = (float)T / (float)std::max(1, steps);
            for (int i = 0; i < steps; ++i)
            {
                int t = (int)std::round((float)(i + 1) * step_ratio_f) - 1;
                if (t < 0)
                    t = 0;
                if (t > T - 1)
                    t = T - 1;
                _timesteps[i] = t;
            }
        }
        else
        {
            // PLMS 従来 (Diffusers PNDMScheduler skip_prk_steps=True 互換)
            const float step_ratio_f = 1000.0f / static_cast<float>(steps > 0 ? steps : 1);
            for (int i = 0; i < steps; ++i)
            {
                _timesteps[i] = 1 + static_cast<int>(std::round(i * step_ratio_f));
            }
        }

        std::vector<int> timesteps;
        if (active_scheduler == ActiveScheduler::PLMS)
        {
            timesteps.reserve(steps + 1);
            // Descending order of Diffusers' plms_timesteps:
            //   [_timesteps[-1], _timesteps[-2], _timesteps[-2], _timesteps[-3], ..., _timesteps[0]]
            if (steps >= 1)
                timesteps.push_back(_timesteps.back());
            if (steps >= 2)
                timesteps.push_back(_timesteps[steps - 2]);
            for (int i = steps - 2; i >= 0; --i)
            {
                timesteps.push_back(_timesteps[i]);
            }
            if (steps == 1)
            {
                timesteps.assign({_timesteps[0]});
            }
        }
        else
        {
            // Bug fix (スケジューラ別の timesteps):
            //   DDIM / Euler / EulerA / LCM / DPM++ 2M / DPM++ 2M Karras では
            //   PLMS の bootstrap 重複は不要で、単純に逆順に並べた N 個の
            //   timestep で N ステップ denoise する。
            timesteps.reserve(steps);
            for (int i = steps - 1; i >= 0; --i)
            {
                // Non-PLMS: exactly 'steps' denoising iterations (no bootstrap waste)
                timesteps.push_back(_timesteps[i]);
            }
        }

        // Bug fix (sigma 系スケジューラーの事前計算):
        //   DPM++ 2M / DPM++ 2M Karras / EulerA は sigma 空間で定式化されているので、
        //   step_index に対する sigma[i], sigma[i+1] テーブルを事前に組む。
        //   Karras は専用の non-linear スケジュール、それ以外は timesteps から
        //   導出する。LCM / DDIM / PLMS / Euler / DPM は使わない。
        std::vector<float> sigmas;
        if (active_scheduler == ActiveScheduler::DPMPP_2M_KARRAS)
        {
            sigmas = build_karras_sigmas(steps, engine->alphas_cumprod);
            // 旧: sigmas[0] < 10 なら強制的に 14.6146 で上書き → alphas_cumprod
            //   から算出した現物値を無視するため、モデル固有の schedule と噛み合わない。
            // 新: 明らかに壊れている場合 (NaN / 極端に小さい) だけフォールバックする。
            if (!sigmas.empty() && (!std::isfinite(sigmas[0]) || sigmas[0] < 1.0f))
                sigmas[0] = 14.6146f;
        }
        else if (active_scheduler == ActiveScheduler::DPMPP_2M)
        {
            sigmas = sigmas_from_timesteps(timesteps, engine->alphas_cumprod);
        }
        else if (active_scheduler == ActiveScheduler::EULER_A)
        {
            sigmas = sigmas_from_timesteps(timesteps, engine->alphas_cumprod);
        }

        // ---- init_noise_sigma を反映 ----
        // sigma 空間で回すスケジューラは latent を sigmas[0] 倍することで
        // 「schedule 側の最大ノイズ量」に初期条件を合わせる。ただし DPM++ 2M /
        // 2M Karras は VP 完結版に書き換えたので latent は VP スケール (∼N(0,1))
        // のまま渡す必要がある。ここで倍率を掛けると UNet 入力が破綻して
        // 真っ黒 / 真っ白の VAE 出力になる (旧バグ)。
        // よって init_noise_sigma の適用は EULER_A に限定する。
        if (active_scheduler == ActiveScheduler::EULER_A &&
            !sigmas.empty() && sigmas[0] > 1.0f)
        {
            const float s0 = sigmas[0];
            for (auto &v : latent)
                v *= s0;
            PROBE_LOG("init_noise_sigma applied: sigmas[0]=%.4f", s0);
        }
        if (!sigmas.empty())
        {
            char sb[512];
            int off = std::snprintf(sb, sizeof(sb), "sigmas (%zu):", sigmas.size());
            for (size_t k = 0; k < sigmas.size() && off < (int)sizeof(sb) - 12; ++k)
                off += std::snprintf(sb + off, sizeof(sb) - off, " %.4f", sigmas[k]);
            PROBE_LOG("%s", sb);
        }

        {
            char buf[512];
            int off = std::snprintf(buf, sizeof(buf), "PLMS timesteps (%zu):", timesteps.size());
            for (size_t k = 0; k < timesteps.size() && off < (int)sizeof(buf) - 12; ++k)
                off += std::snprintf(buf + off, sizeof(buf) - off, " %d", timesteps[k]);
            PROBE_LOG("%s", buf);
            if (steps < 15)
            {
                PROBE_LOG("NOTE: steps=%d is low for SD1.5; consider steps>=20 for a clean image.", steps);
            }
        }

        // --- 4b. Load UNet just-in-time (after CLIP has been freed) ---
        {
            MnnSdError err = create_interpreter_and_session(
                engine->unet_path, effective_backend, SdModelKind::UNET,
                engine->load_options.precision_low != 0,
                engine->unet_interpreter, engine->unet_session, out_error);
            if (err != MNN_SD_OK)
                return err;
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->unet_interpreter.get(), engine->unet_session, "UNet");
        }

        // --- 5. UNet denoising loop: batch=1 x2 per step (low-RAM friendly) ---
        // Rationale: batch=2 would double every UNet activation and pushes
        // 2-3 GB RAM devices past the LMK 'min2x watermark' threshold. Running
        // uncond + cond as two separate batch=1 forwards uses roughly half the
        // peak memory in exchange for two MNN sessions per step. On CPU the
        // overhead is small because the model weights dominate.
        auto *unet_net = engine->unet_interpreter.get();
        auto *u_sample = unet_net->getSessionInput(engine->unet_session, "sample");
        auto *u_ts = unet_net->getSessionInput(engine->unet_session, "timestep");
        auto *u_enc = unet_net->getSessionInput(engine->unet_session, "encoder_hidden_states");
        if (!u_sample || !u_ts || !u_enc)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "UNet: required inputs not found (sample/timestep/encoder_hidden_states)");
            return MNN_SD_ERR_INTERNAL;
        }
        unet_net->resizeTensor(u_sample, {1, 4, lh, lw});
        unet_net->resizeTensor(u_ts, {1});
        unet_net->resizeTensor(u_enc, {1, seq_len, emb_dim});
        unet_net->resizeSession(engine->unet_session);
        // Drop the initial model buffer now that the graph is compiled — MNN
        // still holds the weights mmap'd by the interpreter, but the parsed copy
        // can be released. Frees roughly the model file size again in RAM.
        unet_net->releaseModel();

        // Re-fetch pointers after resize.
        u_sample = unet_net->getSessionInput(engine->unet_session, "sample");
        u_ts = unet_net->getSessionInput(engine->unet_session, "timestep");
        u_enc = unet_net->getSessionInput(engine->unet_session, "encoder_hidden_states");

        std::vector<std::vector<float>> ets;
        std::vector<float> pndm_prev;
        // DPM++ 2M の multistep 係数用: 1 つ前の x0 推定値を保持するバッファ
        std::vector<float> dpmpp_x0_prev;
        // ancestral / LCM 用の RNG。seed==params->seed の場合は同じ seed で
        // 同じ絵にしたいので、初期 latent とはシードを色々 (offset) 変えて使う。
        std::mt19937 sched_rng(
            params->seed < 0
                ? std::random_device{}()
                : (uint32_t)((uint64_t)params->seed ^ 0x9E3779B9u));

        // Per-side text embedding views ([1, 77, emb_dim] each). text_emb is laid
        // out as [uncond, cond] contiguously.
        const float *emb_uncond = text_emb.data();
        const float *emb_cond = text_emb.data() + (size_t)seq_len * emb_dim;
        const size_t emb_bytes = (size_t)seq_len * emb_dim * sizeof(float);
        const size_t latent_bytes = (size_t)latent_size * sizeof(float);

        std::vector<float> pred_uncond(latent_size);
        std::vector<float> pred_cond(latent_size);

        // Bug fix #2 (2026-07 単色青画像):
        //   OpenCL 上では resizeSession 後の session-input tensor が返す
        //   host<float>() が有効な CPU バッキングを持たない端末があり、直接
        //   memcpy しても書き込みが破棄される (VAE 側と同じ症状)。
        //   VAE と同じ「別途 CAFFE ホストテンソルを create → memcpy →
        //   copyFromHostTensor」で統一する。出力側も copyToHostTensor で
        //   別途構築したホストテンソルに引き取り、NaN/Inf 検知を挟む。
        auto run_unet_once = [&](const float *emb_ptr, int ts,
                                 std::vector<float> &out_pred,
                                 const char *tag, int step_index) -> bool
        {
            // Upload sample
            {
                std::unique_ptr<MNN::Tensor> host_s(MNN::Tensor::create<float>(
                    std::vector<int>{1, 4, lh, lw}, nullptr, MNN::Tensor::CAFFE));
                if (!host_s || host_s->elementSize() != latent_size)
                    return false;
                std::memcpy(host_s->host<float>(), latent.data(), latent_bytes);
                u_sample->copyFromHostTensor(host_s.get());
            }
            // Upload timestep (int32)
            {
                std::unique_ptr<MNN::Tensor> host_ts(MNN::Tensor::create<int>(
                    std::vector<int>{1}, nullptr, MNN::Tensor::CAFFE));
                if (!host_ts || host_ts->elementSize() != 1)
                    return false;
                *host_ts->host<int>() = ts;
                u_ts->copyFromHostTensor(host_ts.get());
            }
            // Upload encoder_hidden_states for this side
            {
                std::unique_ptr<MNN::Tensor> host_e(MNN::Tensor::create<float>(
                    std::vector<int>{1, seq_len, emb_dim}, nullptr, MNN::Tensor::CAFFE));
                if (!host_e || (size_t)host_e->elementSize() != (size_t)seq_len * emb_dim)
                    return false;
                std::memcpy(host_e->host<float>(), emb_ptr, emb_bytes);
                u_enc->copyFromHostTensor(host_e.get());
            }

            unet_net->runSession(engine->unet_session);

            auto *out_t = unet_net->getSessionOutput(engine->unet_session, "out_sample");
            if (!out_t)
            {
                const auto &all_out = unet_net->getSessionOutputAll(engine->unet_session);
                if (all_out.size() == 1)
                    out_t = all_out.begin()->second;
            }
            if (!out_t)
                return false;

            std::unique_ptr<MNN::Tensor> host_out(MNN::Tensor::create<float>(
                std::vector<int>{1, 4, lh, lw}, nullptr, MNN::Tensor::CAFFE));
            if (!host_out || host_out->elementSize() < latent_size)
                return false;
            out_t->copyToHostTensor(host_out.get());
            std::memcpy(out_pred.data(), host_out->host<float>(), latent_bytes);

            // Bug fix #2 diagnostic: check UNet output stats. If everything
            // is 0 / same value / NaN, the FP16 pipeline is silently broken
            // and continuing would give the user a solid-color image.
            {
                bool any_nan = false;
                float vmin = out_pred[0], vmax = out_pred[0];
                double sum = 0.0, sq = 0.0;
                for (int k = 0; k < latent_size; ++k)
                {
                    float v = out_pred[k];
                    if (v != v || v > 1e30f || v < -1e30f)
                    {
                        any_nan = true;
                        break;
                    }
                    if (v < vmin) vmin = v;
                    if (v > vmax) vmax = v;
                    sum += v;
                    sq += (double)v * v;
                }
                // Log stats on first and last denoise step.
                //   num_solver_iters is declared *below* this lambda, so we
                //   can't capture it. timesteps was already captured by
                //   reference and .size() gives us the same value.
                const int last_idx = (int)timesteps.size() - 1;
                if (step_index == 0 || step_index == last_idx)
                {
                    double mean = sum / latent_size;
                    double var  = sq / latent_size - mean * mean;
                    PROBE_LOG("UNet %s step=%d min=%.4f max=%.4f mean=%.4f std=%.4f",
                              tag, step_index, vmin, vmax, mean,
                              var > 0 ? std::sqrt(var) : 0.0);
                }
                if (any_nan || (vmax - vmin) < 1e-6f)
                {
                    PROBE_LOG("UNet %s step=%d DEGENERATE nan=%d min=%.4f max=%.4f",
                              tag, step_index, any_nan ? 1 : 0, vmin, vmax);
                    return false;
                }
            }
            return true;
        };

        const int num_solver_iters = (int)timesteps.size();
        for (int i = 0; i < num_solver_iters; ++i)
        {
            if (engine->cancel_requested)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message), "cancelled");
                return MNN_SD_ERR_CANCELLED;
            }

            int ts = timesteps[i];

            if (!run_unet_once(emb_uncond, ts, pred_uncond, "uncond", i) ||
                !run_unet_once(emb_cond,   ts, pred_cond,   "cond",   i))
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "UNet: run failed or produced degenerate output at step %d "
                                  "(likely FP16 overflow \u2014 UNet must run at Precision_High).", i);
                return MNN_SD_ERR_INTERNAL;
            }

            // CFG: noise_pred = uncond + cfg * (cond - uncond)
            // 少ステップ (<=15) × 高CFG (>=6) では、Lin 2024 の CFG Rescale を
            // 併用して過飽和 / 焼き付きを抑える。φ の値は経験則で 0.7 が無難。
            // (φ=0.0 は CFG Rescale 完全無効)
            std::vector<float> combined(latent_size);
            for (int j = 0; j < latent_size; ++j)
                combined[j] = pred_uncond[j] + cfg * (pred_cond[j] - pred_uncond[j]);

            {
                float phi = 0.0f;
                if (steps <= 15 && cfg >= 6.0f)
                    phi = 0.7f;
                else if (cfg >= 9.0f)
                    phi = 0.5f;
                if (phi > 0.0f)
                    apply_cfg_rescale(combined, pred_cond, phi);
            }

            switch (active_scheduler)
            {
            case ActiveScheduler::DDIM:
                latent = ddim_step(latent, combined, i, timesteps, engine->alphas_cumprod);
                break;
            case ActiveScheduler::EULER:
                latent = euler_step(latent, combined, i, timesteps, engine->alphas_cumprod);
                break;
            case ActiveScheduler::EULER_A:
                latent = euler_a_step(latent, combined, i, sigmas, sched_rng);
                break;
            case ActiveScheduler::LCM_STEP:
                latent = lcm_step(latent, combined, i, timesteps,
                                  engine->alphas_cumprod, sched_rng);
                break;
            case ActiveScheduler::DPMPP_2M:
            case ActiveScheduler::DPMPP_2M_KARRAS:
            {
                // Improved DPM++ 2M for low-step quality:
                // - VP-parameterised x0 recovery (matches SD1.5 training).
                // - Stronger guard on r and h_i to prevent multistep instability
                //   at steps=8..15 (common low-step regime).
                std::vector<float> x0_next;
                latent = dpmpp_2m_step(latent, combined, i, sigmas,
                                       timesteps, engine->alphas_cumprod,
                                       x0_next, dpmpp_x0_prev);
                dpmpp_x0_prev = std::move(x0_next);
                break;
            }
            case ActiveScheduler::PLMS:
            default:
                latent = pndm_step(latent, combined, i, timesteps,
                                   engine->alphas_cumprod, ets, pndm_prev);
                break;
            }

            // Progress は active_scheduler に応じて変わる:
            //   PLMS: timesteps.size() = steps+1 なので bootstrap 2 回目をステップ 1 に折りたたむ。
            //   それ以外: timesteps.size() = steps なので i そのままで OK。
            if (on_progress)
            {
                int visible_step;
                if (active_scheduler == ActiveScheduler::PLMS)
                {
                    if (i <= 1)
                        visible_step = 1;
                    else
                        visible_step = i;
                }
                else
                {
                    visible_step = i + 1;
                }
                if (visible_step > steps)
                    visible_step = steps;
                MnnSdProgress p{visible_step, steps, 0.0f};
                on_progress(&p, progress_user_data);
            }
        }

        // Free UNet before VAE (~860 MB back to the OS on CuteYukiMix).
        if (engine->unet_session)
        {
            engine->unet_interpreter->releaseSession(engine->unet_session);
            engine->unet_session = nullptr;
        }
        engine->unet_interpreter.reset();
        unet_net = nullptr;
        u_sample = u_ts = u_enc = nullptr;

        // Bug fix (scudo internal map failure at VAE createFromFile):
        //   UNet を reset しても scudo はOSにページを戻さないことがあり、
        //   直後の VAE createFromFile で新規 mmap に失敗する。
        //   malloc_trim(0) を 2 回呼び、間でスケジューリングを一旦譲ることで
        //   解放済みチャンクを OS に返す。
        PROBE_LOG("trim heap before VAE load");
        trim_heap_to_os();
        std::this_thread::yield();
        trim_heap_to_os();

        // --- 5b. Load VAE just-in-time (after UNet has been freed) ---
        //   VAE は ScheduleBundle 側で Precision_High (fp32) に固定される。
        //   これで OpenCL でも真っ白画像にならない。
        {
            MnnSdError err = create_interpreter_and_session(
                engine->vae_path, effective_backend, SdModelKind::VAE,
                false,
                engine->vae_interpreter, engine->vae_session, out_error);
            if (err != MNN_SD_OK)
            {
                trim_heap_to_os();
                return err;
            }
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->vae_interpreter.get(), engine->vae_session, "VAE");
        }

        // --- 6. VAE decode: explicit resize to {1, 4, lh, lw} ---
        // scale latent: latent / 0.18215
        for (auto &v : latent)
            v /= 0.18215f;

        auto *vae_net = engine->vae_interpreter.get();
        auto *v_input = vae_net->getSessionInput(engine->vae_session, "latent_sample");
        if (!v_input)
        {
            const auto &all_in = vae_net->getSessionInputAll(engine->vae_session);
            if (all_in.size() == 1)
                v_input = all_in.begin()->second;
        }
        if (!v_input)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "VAE: latent_sample tensor not found");
            return MNN_SD_ERR_INTERNAL;
        }
        vae_net->resizeTensor(v_input, {1, 4, lh, lw});
        vae_net->resizeSession(engine->vae_session);
        v_input = vae_net->getSessionInput(engine->vae_session, "latent_sample");

        // Bug fix (GPU 真っ白の副因):
        //   OpenCL バックエンドでは resizeSession 直後の session-input tensor に
        //   対する host<float>() が有効な CPU バッキングを返さない端末がある
        //   (Adreno 6xx + MNN 3.6 で実測)。書き込みが実質的に破棄され、UNet が
        //   出した意味のある latent ではなく未初期化のゼロが VAE に入ってしまい、
        //   fp32 で走らせても VAE のバイアス項だけで灰色 or 白に潰れる。
        //   ここは CLIP / UNet と同じ手順、つまり CAFFE レイアウトのホスト側
        //   一時テンソルを別途構築 → memcpy → copyFromHostTensor で書き込む。
        {
            std::unique_ptr<MNN::Tensor> host_v(MNN::Tensor::create<float>(
                std::vector<int>{1, 4, lh, lw}, nullptr, MNN::Tensor::CAFFE));
            if (!host_v || host_v->elementSize() != (int)latent.size())
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "VAE: failed to build host input tensor");
                return MNN_SD_ERR_INTERNAL;
            }
            std::memcpy(host_v->host<float>(), latent.data(),
                        latent.size() * sizeof(float));
            v_input->copyFromHostTensor(host_v.get());
        }
        vae_net->runSession(engine->vae_session);
        auto image_f = read_output_f32(vae_net, engine->vae_session, "sample");
        if (image_f.empty())
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "VAE: sample output empty");
            return MNN_SD_ERR_INTERNAL;
        }

        // Sanity check: FP16 オーバーフローの痕跡 (全画素が同一 or NaN) を検出。
        //   Precision_High に上げているので通常はここに引っかからないが、
        //   万一ドライバが precision hint を無視した場合に沈黙して真っ白を
        //   ユーザーに見せるより、ここで明示的にエラーを上げた方が親切。
        {
            bool any_nan = false;
            float vmin = image_f[0];
            float vmax = image_f[0];
            const size_t N = image_f.size();
            for (size_t k = 0; k < N; ++k)
            {
                float v = image_f[k];
                if (v != v)
                {
                    any_nan = true;
                    break;
                }
                if (v < vmin) vmin = v;
                if (v > vmax) vmax = v;
            }
            if (any_nan || (vmax - vmin) < 1e-4f)
            {
                PROBE_LOG("VAE output degenerate: nan=%d min=%.4f max=%.4f (backend=%d)",
                          any_nan ? 1 : 0, vmin, vmax, static_cast<int>(effective_backend));
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "VAE decode produced a flat/NaN image on backend=%d "
                                  "(likely FP16 overflow \u2014 check that Precision_High reached the driver).",
                                  static_cast<int>(effective_backend));
                return MNN_SD_ERR_INTERNAL;
            }
        }

        // Free VAE now — the RGB copy below only needs image_f.
        if (engine->vae_session)
        {
            engine->vae_interpreter->releaseSession(engine->vae_session);
            engine->vae_session = nullptr;
        }
        engine->vae_interpreter.reset();
        trim_heap_to_os();

        // image_f: [1, 3, H, W] NCHW, range ~[-1, 1] -> clamp to [0,1] -> uint8 RGB
        const int pixels = width * height;
        uint8_t *rgb = new uint8_t[pixels * 3];
        for (int p = 0; p < pixels; ++p)
        {
            for (int c = 0; c < 3; ++c)
            {
                float v = image_f[c * pixels + p] * 0.5f + 0.5f;
                v = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
                rgb[p * 3 + c] = (uint8_t)(v * 255.0f + 0.5f);
            }
        }

        out_image->width = width;
        out_image->height = height;
        out_image->channels = 3;
        out_image->data = rgb;
        out_image->data_size = pixels * 3;

        if (on_progress)
        {
            // VAE 完了通知: step は steps に固定 (100%)、total_steps も steps。
            MnnSdProgress p{steps, steps, 0.0f};
            on_progress(&p, progress_user_data);
        }
        return MNN_SD_OK;
#endif
    }

} // extern "C"
