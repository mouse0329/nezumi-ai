#include "mnn_sd/engine.h"
#include "engine_internal.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <random>
#include <set>
#include <sstream>
#include <string>
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

    struct ScheduleBundle
    {
        MNN::BackendConfig backend_config{};
        MNN::ScheduleConfig schedule{};

        explicit ScheduleBundle(MnnSdBackend backend)
        {
            backend_config.precision = MNN::BackendConfig::Precision_Low;
            if (backend == MNN_SD_BACKEND_OPENCL)
            {
                backend_config.power = MNN::BackendConfig::Power_High;
            }
            schedule.type = (backend == MNN_SD_BACKEND_OPENCL) ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
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

        ScheduleBundle bundle(backend);
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

        // Load xororz embedding tables (token_emb.bin, pos_emb.bin)
        {
            auto load_bin = [](const std::string &path, std::vector<float> &out) -> bool {
                FILE *f = std::fopen(path.c_str(), "rb");
                if (!f) return false;
                std::fseek(f, 0, SEEK_END);
                long sz = std::ftell(f);
                std::fseek(f, 0, SEEK_SET);
                out.resize(sz / sizeof(float));
                std::fread(out.data(), sizeof(float), out.size(), f);
                std::fclose(f);
                return !out.empty();
            };

            const std::string token_emb_path = build_model_path(engine->model_dir.c_str(), "token_emb.bin");
            const std::string pos_emb_path   = build_model_path(engine->model_dir.c_str(), "pos_emb.bin");

            if (file_exists(token_emb_path) && file_exists(pos_emb_path))
            {
                load_bin(token_emb_path, engine->token_emb);
                load_bin(pos_emb_path,   engine->pos_emb);
                engine->token_emb_vocab_size = (int)(engine->token_emb.size() / 768);
            }
        }

        MnnSdError err = create_interpreter_and_session(
            unet_path, engine->load_options.backend, engine->unet_interpreter, engine->unet_session, out_error);
        if (err != MNN_SD_OK)
            return err;

        err = create_interpreter_and_session(
            clip_path, engine->load_options.backend, engine->clip_interpreter, engine->clip_session, out_error);
        if (err != MNN_SD_OK)
        {
            mnn_sd_release_sessions(engine);
            return err;
        }

        err = create_interpreter_and_session(
            vae_path, engine->load_options.backend, engine->vae_interpreter, engine->vae_session, out_error);
        if (err != MNN_SD_OK)
        {
            mnn_sd_release_sessions(engine);
            return err;
        }

        // Precompute PNDM alphas_cumprod (scaled_linear schedule, beta_start=0.00085, beta_end=0.012, T=1000)
        {
            const int T = 1000;
            const float beta_start = 0.00085f;
            const float beta_end   = 0.012f;
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

        ScheduleBundle bundle(backend);
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
    // Minimal JSON string extraction (no third-party dep)
    // Finds the value of "key": { ... } or "key": "..."
    // Returns empty string on failure
    std::string json_find_object(const std::string &json, const std::string &key)
    {
        std::string needle = "\"" + key + "\"";
        auto pos = json.find(needle);
        if (pos == std::string::npos) return {};
        pos = json.find('{', pos + needle.size());
        if (pos == std::string::npos) return {};
        int depth = 0;
        size_t start = pos;
        for (size_t i = pos; i < json.size(); ++i)
        {
            if (json[i] == '{') ++depth;
            else if (json[i] == '}') { --depth; if (depth == 0) return json.substr(start, i - start + 1); }
        }
        return {};
    }

    // Parse "token": id pairs from a JSON object string
    void parse_vocab(const std::string &obj, std::unordered_map<std::string, int> &vocab)
    {
        size_t i = 0;
        while (i < obj.size())
        {
            // find next "
            auto q1 = obj.find('"', i);
            if (q1 == std::string::npos) break;
            auto q2 = std::string::npos;
            // find closing " (handle \" escapes)
            size_t j = q1 + 1;
            while (j < obj.size())
            {
                if (obj[j] == '\\') { j += 2; continue; }
                if (obj[j] == '"') { q2 = j; break; }
                ++j;
            }
            if (q2 == std::string::npos) break;
            std::string token = obj.substr(q1 + 1, q2 - q1 - 1);
            // unescape \\\\ -> \\ and \\" -> "
            std::string unescaped;
            for (size_t k = 0; k < token.size(); ++k)
            {
                if (token[k] == '\\' && k + 1 < token.size())
                {
                    ++k;
                    if (token[k] == '"') unescaped += '"';
                    else if (token[k] == '\\') unescaped += '\\';
                    else if (token[k] == 'n') unescaped += '\n';
                    else { unescaped += '\\'; unescaped += token[k]; }
                }
                else unescaped += token[k];
            }
            // find colon then integer
            auto colon = obj.find(':', q2 + 1);
            if (colon == std::string::npos) break;
            size_t num_start = colon + 1;
            while (num_start < obj.size() && (obj[num_start] == ' ' || obj[num_start] == '\t')) ++num_start;
            if (num_start >= obj.size() || !std::isdigit((unsigned char)obj[num_start])) { i = q2 + 1; continue; }
            int id = std::stoi(obj.substr(num_start));
            vocab[unescaped] = id;
            i = num_start;
        }
    }

    // Parse merges array: each element is "a b" (two tokens separated by space)
    void parse_merges(const std::string &json, std::vector<std::pair<std::string, std::string>> &merges)
    {
        // find "merges": [
        auto pos = json.find("\"merges\"");
        if (pos == std::string::npos) return;
        auto bracket = json.find('[', pos);
        if (bracket == std::string::npos) return;
        size_t i = bracket + 1;
        while (i < json.size())
        {
            auto q1 = json.find('"', i);
            if (q1 == std::string::npos) break;
            auto q2 = json.find('"', q1 + 1);
            if (q2 == std::string::npos) break;
            std::string entry = json.substr(q1 + 1, q2 - q1 - 1);
            auto sp = entry.find(' ');
            if (sp != std::string::npos)
                merges.emplace_back(entry.substr(0, sp), entry.substr(sp + 1));
            i = q2 + 1;
            // stop at ]
            auto next_q = json.find('"', i);
            auto next_bracket = json.find(']', i);
            if (next_bracket != std::string::npos && (next_q == std::string::npos || next_bracket < next_q)) break;
        }
    }
} // namespace

bool ClipTokenizer::load(const std::string &path)
{
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::string json(sz, '\0');
    std::fread(&json[0], 1, sz, f);
    std::fclose(f);

    std::string model_obj = json_find_object(json, "model");
    if (model_obj.empty()) return false;
    std::string vocab_obj = json_find_object(model_obj, "vocab");
    if (vocab_obj.empty()) return false;
    parse_vocab(vocab_obj, vocab);
    parse_merges(model_obj, merges);
    return !vocab.empty();
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
        256,257,258,259,260,261,262,263,264,265,266,267,268,269,270,271,
        272,273,274,275,276,277,278,279,280,281,282,283,284,285,286,287,
        288, // 32 entries for 0-32
        289, // 127
        290,291,292,293,294,295,296,297,298,299,300,301,302,303,304,305,
        306,307,308,309,310,311,312,313,314,315,316,317,318,319,320,321,
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
            if (!printable) byte_to_cp[b] = 256 + n++;
            else byte_to_cp[b] = b;
        }
    }
    int cp = byte_to_cp[c];
    // Encode codepoint as UTF-8
    std::string out;
    if (cp < 0x80) { out += (char)cp; }
    else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
    else { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    return out;
}

std::string ClipTokenizer::bpe(const std::string &token) const
{
    if (token.empty()) return token;
    // Split token into UTF-8 characters, append </w> to last
    std::vector<std::string> chars;
    size_t i = 0;
    while (i < token.size())
    {
        unsigned char c = (unsigned char)token[i];
        int len = 1;
        if (c >= 0xF0) len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        chars.push_back(token.substr(i, len));
        i += len;
    }
    if (!chars.empty()) chars.back() += "</w>";

    // BPE merge loop
    while (chars.size() > 1)
    {
        // Find the highest-priority merge pair
        int best_rank = -1;
        size_t best_pos = 0;
        for (size_t k = 0; k + 1 < chars.size(); ++k)
        {
            std::string pair_a = chars[k];
            std::string pair_b = chars[k + 1];
            for (int r = 0; r < (int)merges.size(); ++r)
            {
                if (merges[r].first == pair_a && merges[r].second == pair_b)
                {
                    if (best_rank < 0 || r < best_rank)
                    {
                        best_rank = r;
                        best_pos = k;
                    }
                    break;
                }
            }
        }
        if (best_rank < 0) break;
        // Merge
        chars[best_pos] += chars[best_pos + 1];
        chars.erase(chars.begin() + best_pos + 1);
    }

    std::string result;
    for (size_t k = 0; k < chars.size(); ++k)
    {
        if (k > 0) result += ' ';
        result += chars[k];
    }
    return result;
}

std::vector<int> ClipTokenizer::encode_single(const std::string &text) const
{
    std::vector<int> ids;
    ids.push_back(BOS_ID);

    // Lowercase + simple whitespace split into words
    std::string lower;
    for (unsigned char c : text)
        lower += (char)std::tolower(c);

    std::istringstream ss(lower);
    std::string word;
    while (ss >> word)
    {
        // Byte-level encode each character
        std::string byte_word;
        for (unsigned char c : word)
            byte_word += bytes_to_unicode(c);

        // BPE
        std::string bpe_result = bpe(byte_word);
        std::istringstream bpe_ss(bpe_result);
        std::string sub;
        while (bpe_ss >> sub)
        {
            auto it = vocab.find(sub);
            if (it != vocab.end())
                ids.push_back(it->second);
        }
    }

    ids.push_back(EOS_ID);
    // Pad or truncate to MAX_LEN
    ids.resize(MAX_LEN, 0);
    return ids;
}

std::vector<int> ClipTokenizer::encode_pair(const std::string &prompt) const
{
    auto uncond = encode_single("");
    auto cond   = encode_single(prompt);
    std::vector<int> out;
    out.insert(out.end(), uncond.begin(), uncond.end());
    out.insert(out.end(), cond.begin(),   cond.end());
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
        if (!net || !session || !name) return nullptr;
        auto *t = net->getSessionInput(session, name);
        if (t) return t;

        const auto &all_inputs = net->getSessionInputAll(session);
        auto it = all_inputs.find(name);
        if (it != all_inputs.end()) return it->second;
        if (all_inputs.size() == 1) return all_inputs.begin()->second;
        return nullptr;
    }

    MNN::Tensor *get_session_output_tensor(MNN::Interpreter *net, MNN::Session *session, const char *name)
    {
        if (!net || !session || !name) return nullptr;
        auto *t = net->getSessionOutput(session, name);
        if (t) return t;

        const auto &all_outputs = net->getSessionOutputAll(session);
        auto it = all_outputs.find(name);
        if (it != all_outputs.end()) return it->second;
        if (all_outputs.size() == 1) return all_outputs.begin()->second;
        return nullptr;
    }

    // Copy float data into a named MNN session input tensor
    bool fill_input_f32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const float *data, size_t count)
    {
        auto *t = get_session_input_tensor(net, session, name);
        if (!t || !data) return false;
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        // Resize if needed
        if ((size_t)host.elementSize() != count)
        {
            std::vector<int> shape;
            for (int i = 0; i < t->dimensions(); ++i) shape.push_back(t->length(i));
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
            if (!t) return false;
            MNN::Tensor host2(t, MNN::Tensor::CAFFE);
            if ((size_t)host2.elementSize() != count) return false;
            std::memcpy(host2.host<float>(), data, count * sizeof(float));
            t->copyFromHostTensor(&host2);
            return true;
        }
        std::memcpy(host.host<float>(), data, count * sizeof(float));
        t->copyFromHostTensor(&host);
        return true;
    }

    bool fill_input_i32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const int *data, size_t count)
    {
        auto *t = get_session_input_tensor(net, session, name);
        if (!t || !data) return false;
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        if ((size_t)host.elementSize() != count)
        {
            std::vector<int> shape;
            for (int i = 0; i < t->dimensions(); ++i) shape.push_back(t->length(i));
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
            if (!t) return false;
            MNN::Tensor host2(t, MNN::Tensor::CAFFE);
            if ((size_t)host2.elementSize() != count) return false;
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
        if (!t) return {};
        MNN::Tensor host(t, MNN::Tensor::CAFFE);
        t->copyToHostTensor(&host);
        const float *ptr = host.host<float>();
        return std::vector<float>(ptr, ptr + host.elementSize());
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
            if (ets.size() >= 4) ets.erase(ets.begin());
            ets.push_back(mo);
        }
        else
        {
            timestep      = timesteps[0];
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
                blended[i] = (3.0f * ets[ets_sz-1][i] - ets[ets_sz-2][i]) * 0.5f;
        }
        else if (ets_sz == 3)
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (23.0f*ets[ets_sz-1][i] - 16.0f*ets[ets_sz-2][i] + 5.0f*ets[ets_sz-3][i]) / 12.0f;
        }
        else
        {
            for (size_t i = 0; i < N; ++i)
                blended[i] = (55.0f*ets[ets_sz-1][i] - 59.0f*ets[ets_sz-2][i]
                             + 37.0f*ets[ets_sz-3][i] -  9.0f*ets[ets_sz-4][i]) / 24.0f;
        }

        float alpha_t      = alphas_cumprod[timestep];
        float alpha_t_prev = alphas_cumprod[prev_timestep];
        float beta_t       = 1.0f - alpha_t;
        float beta_t_prev  = 1.0f - alpha_t_prev;
        float coeff_sample = std::sqrt(alpha_t_prev / alpha_t);
        float denom        = alpha_t * std::sqrt(beta_t_prev) + std::sqrt(alpha_t * beta_t * alpha_t_prev);
        float coeff_mo     = (alpha_t_prev - alpha_t) / denom;

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
    (void)engine; (void)params; (void)on_progress; (void)progress_user_data;
    (void)out_image;
    if (out_error) { out_error->code = MNN_SD_ERR_BACKEND_INIT_FAILED;
        std::snprintf(out_error->message, sizeof(out_error->message), "MNN not linked"); }
    return MNN_SD_ERR_BACKEND_INIT_FAILED;
#else
    const int steps  = params->steps;
    const int width  = params->width;
    const int height = params->height;
    const float cfg  = params->cfg_scale;
    const int lw = width  / 8;
    const int lh = height / 8;
    const int latent_size = 4 * lh * lw;

    // --- 0. Probe CLIP tensor names (logged once for debugging) ---
    {
        const auto &all_inputs = engine->clip_interpreter->getSessionInputAll(engine->clip_session);
        for (const auto &kv : all_inputs)
        {
            const MNN::Tensor *t = kv.second;
            char buf[256];
            std::snprintf(buf, sizeof(buf), "[Probe] CLIP input: %s dims=%d",
                          kv.first.c_str(), t ? t->dimensions() : -1);
            __android_log_print(ANDROID_LOG_INFO, "MnnSdJni", "%s", buf);
        }
        const auto &all_outputs = engine->clip_interpreter->getSessionOutputAll(engine->clip_session);
        for (const auto &kv : all_outputs)
        {
            char buf[256];
            std::snprintf(buf, sizeof(buf), "[Probe] CLIP output: %s", kv.first.c_str());
            __android_log_print(ANDROID_LOG_INFO, "MnnSdJni", "%s", buf);
        }
    }

    // --- 0. Probe CLIP/UNet/VAE tensor names (logged once) ---
    {
        auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label) {
            for (const auto &kv : net->getSessionInputAll(sess))
                PROBE_LOG("%s input: %s", label, kv.first.c_str());
            for (const auto &kv : net->getSessionOutputAll(sess))
                PROBE_LOG("%s output: %s", label, kv.first.c_str());
        };
        probe_session(engine->clip_interpreter.get(), engine->clip_session, "CLIP");
        probe_session(engine->unet_interpreter.get(), engine->unet_session, "UNet");
        probe_session(engine->vae_interpreter.get(),  engine->vae_session,  "VAE");
    }

    // --- 1. Tokenize + build input_embedding ---
    auto token_ids = engine->tokenizer.encode_pair(params->prompt ? params->prompt : "");
    // token_ids: [2 * 77] ints

    // Build input_embedding [2, 77, 768] = token_emb[id] + pos_emb[pos]
    const int seq_len = ClipTokenizer::MAX_LEN;
    const int emb_dim = 768;
    const int batch = 2;
    std::vector<float> input_embedding(batch * seq_len * emb_dim, 0.0f);

    if (!engine->token_emb.empty() && !engine->pos_emb.empty())
    {
        for (int b = 0; b < batch; ++b)
        {
            for (int p = 0; p < seq_len; ++p)
            {
                int tok_id = token_ids[b * seq_len + p];
                tok_id = std::max(0, std::min(tok_id, engine->token_emb_vocab_size - 1));
                const float *te = engine->token_emb.data() + tok_id * emb_dim;
                const float *pe = engine->pos_emb.data()   + p       * emb_dim;
                float *dst = input_embedding.data() + (b * seq_len + p) * emb_dim;
                for (int d = 0; d < emb_dim; ++d)
                    dst[d] = te[d] + pe[d];
            }
        }
    }

    // --- 2. CLIP text encoder ---
    if (!fill_input_f32(engine->clip_interpreter.get(), engine->clip_session,
                        "input_embedding", input_embedding.data(), input_embedding.size()))
    {
        if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                     "CLIP: input_embedding tensor not found");
        return MNN_SD_ERR_INTERNAL;
    }
    engine->clip_interpreter->runSession(engine->clip_session);
    auto text_emb = read_output_f32(engine->clip_interpreter.get(), engine->clip_session, "last_hidden_state");
    if (text_emb.empty())
    {
        if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                     "CLIP: last_hidden_state output empty");
        return MNN_SD_ERR_INTERNAL;
    }
    // text_emb: [2, 77, 768]

    if (on_progress) { MnnSdProgress p{1, steps + 2, 0.0f}; on_progress(&p, progress_user_data); }

    // --- 3. Init latent noise ---
    std::vector<float> latent(latent_size);
    {
        int64_t seed = params->seed;
        std::mt19937 rng(seed < 0 ? std::random_device{}() : (uint32_t)seed);
        std::normal_distribution<float> dist(0.0f, 1.0f);
        for (auto &v : latent) v = dist(rng);
    }

    // --- 4. Build PNDM timesteps ---
    std::vector<int> timesteps(steps);
    int step_size = 1000 / steps;
    for (int i = steps - 1; i >= 0; --i)
        timesteps[i] = 1 + (steps - 1 - i) * step_size;

    // --- 5. UNet denoising loop ---
    std::vector<std::vector<float>> ets;
    std::vector<float> pndm_prev;

    for (int i = 0; i < steps; ++i)
    {
        if (engine->cancel_requested)
        {
            if (out_error) std::snprintf(out_error->message, sizeof(out_error->message), "cancelled");
            return MNN_SD_ERR_CANCELLED;
        }

        // sample = concat(latent, latent) -> [2, 4, lh, lw]
        std::vector<float> sample(latent_size * 2);
        std::memcpy(sample.data(),              latent.data(), latent_size * sizeof(float));
        std::memcpy(sample.data() + latent_size, latent.data(), latent_size * sizeof(float));

        int ts = timesteps[i];

        if (!fill_input_f32(engine->unet_interpreter.get(), engine->unet_session,
                            "sample", sample.data(), sample.size()))
        {
            if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                         "UNet: sample tensor not found");
            return MNN_SD_ERR_INTERNAL;
        }
        {
            float ts_f = (float)ts;
            fill_input_f32(engine->unet_interpreter.get(), engine->unet_session, "timestep", &ts_f, 1);
        }
        if (!fill_input_f32(engine->unet_interpreter.get(), engine->unet_session,
                            "encoder_hidden_states", text_emb.data(), text_emb.size()))
        {
            if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                         "UNet: encoder_hidden_states tensor not found");
            return MNN_SD_ERR_INTERNAL;
        }

        engine->unet_interpreter->runSession(engine->unet_session);
        auto noise_pred = read_output_f32(engine->unet_interpreter.get(), engine->unet_session, "out_sample");
        if (noise_pred.empty())
        {
            if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                         "UNet: out_sample output empty");
            return MNN_SD_ERR_INTERNAL;
        }

        // CFG: noise_pred = uncond + cfg * (cond - uncond)
        for (int j = 0; j < latent_size; ++j)
            noise_pred[j] = noise_pred[j] + cfg * (noise_pred[j + latent_size] - noise_pred[j]);
        noise_pred.resize(latent_size);

        latent = pndm_step(latent, noise_pred, i, timesteps,
                           engine->alphas_cumprod, ets, pndm_prev);

        if (on_progress)
        {
            MnnSdProgress p{i + 1, steps, 0.0f};
            on_progress(&p, progress_user_data);
        }
    }

    // --- 6. VAE decode ---
    // scale latent: latent / 0.18215
    for (auto &v : latent) v /= 0.18215f;

    if (!fill_input_f32(engine->vae_interpreter.get(), engine->vae_session,
                        "latent_sample", latent.data(), latent.size()))
    {
        if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                     "VAE: latent_sample tensor not found");
        return MNN_SD_ERR_INTERNAL;
    }
    engine->vae_interpreter->runSession(engine->vae_session);
    auto image_f = read_output_f32(engine->vae_interpreter.get(), engine->vae_session, "sample");
    if (image_f.empty())
    {
        if (out_error) std::snprintf(out_error->message, sizeof(out_error->message),
                                     "VAE: sample output empty");
        return MNN_SD_ERR_INTERNAL;
    }

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

    out_image->width     = width;
    out_image->height    = height;
    out_image->channels  = 3;
    out_image->data      = rgb;
    out_image->data_size = pixels * 3;

    if (on_progress) { MnnSdProgress p{steps + 2, steps + 2, 0.0f}; on_progress(&p, progress_user_data); }
    return MNN_SD_OK;
#endif
}

} // extern "C"
