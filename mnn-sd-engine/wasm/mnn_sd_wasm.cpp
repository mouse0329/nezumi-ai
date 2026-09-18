#include "mnn_sd/engine.h"
#include <emscripten/bind.h>
#include <emscripten/val.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>

namespace {
MnnSdEngine* g_engine = nullptr;
std::string g_last_error;

void set_error(const MnnSdErrorInfo& info) {
  g_last_error = std::string(mnn_sd_error_string(info.code)) + ": " + info.message;
  if (info.cause[0]) g_last_error += " (" + std::string(info.cause) + ")";
}

void progress_cb(const MnnSdProgress* p, void*) {
  // The synchronous C API is invoked from a Web Worker. The JS side can poll
  // progress via the returned Promise in the worker protocol.
  (void)p;
}
}

void wasm_create() {
  if (!g_engine) g_engine = mnn_sd_create();
}

void wasm_destroy() {
  if (g_engine) mnn_sd_destroy(g_engine);
  g_engine = nullptr;
}

bool wasm_load(const std::string& model_dir, int backend) {
  wasm_create();
  MnnSdLoadOptions options{};
  options.backend = backend == 1 ? MNN_SD_BACKEND_OPENCL : MNN_SD_BACKEND_CPU;
  options.opencl_safe_max_side = 0;
  // CuteYukiMix-int8-block32 requires MNN's low-memory weight path for the
  // UNet. CLIP/VAE remain on the normal portable CPU schedule.
  options.precision_low = 1;
  MnnSdErrorInfo info{};
  const auto rc = mnn_sd_load(g_engine, model_dir.c_str(), &options, &info);
  if (rc != MNN_SD_OK) { set_error(info); return false; }
  g_last_error.clear();
  return true;
}

emscripten::val wasm_capabilities() {
  MnnSdCapabilities caps{};
  MnnSdErrorInfo info{};
  if (!g_engine || mnn_sd_get_capabilities(g_engine, &caps, &info) != MNN_SD_OK) {
    set_error(info);
    return emscripten::val::null();
  }
  emscripten::val result = emscripten::val::object();
  result.set("supportsOpencl", caps.supports_opencl);
  result.set("maxSidePx", caps.max_side_px);
  result.set("defaultSidePx", caps.default_side_px);
  result.set("textEmbeddingSize", caps.text_embedding_size);
  result.set("supportsImg2Img", caps.supports_img2img);
  result.set("formatVersion", std::string(caps.format_version));
  return result;
}

emscripten::val wasm_generate(const std::string& prompt, const std::string& negative_prompt,
                              int width, int height, int steps, float cfg, int64_t seed,
                              int scheduler) {
  if (!g_engine) return emscripten::val::null();
  MnnSdGenerateParams params{};
  params.prompt = prompt.c_str();
  params.negative_prompt = negative_prompt.c_str();
  params.width = width;
  params.height = height;
  params.steps = steps;
  params.cfg_scale = cfg;
  params.seed = seed;
  params.scheduler = static_cast<MnnSdScheduler>(scheduler);
  params.use_opencl = 0;
  MnnSdImage image{};
  MnnSdErrorInfo info{};
  const auto rc = mnn_sd_generate(g_engine, &params, progress_cb, nullptr, &image, &info);
  if (rc != MNN_SD_OK) { set_error(info); return emscripten::val::null(); }
  emscripten::val bytes = emscripten::val::global("Uint8Array").new_(image.data_size);
  emscripten::val memory = emscripten::val::module_property("HEAPU8");
  bytes.call<void>("set", memory.call<emscripten::val>("subarray", reinterpret_cast<uintptr_t>(image.data), reinterpret_cast<uintptr_t>(image.data) + image.data_size));
  emscripten::val result = emscripten::val::object();
  result.set("width", image.width);
  result.set("height", image.height);
  result.set("channels", image.channels);
  result.set("data", bytes);
  mnn_sd_free_image(&image);
  return result;
}

std::string wasm_last_error() { return g_last_error; }

EMSCRIPTEN_BINDINGS(mnn_sd_wasm) {
  emscripten::function("create", &wasm_create);
  emscripten::function("destroy", &wasm_destroy);
  emscripten::function("load", &wasm_load);
  emscripten::function("capabilities", &wasm_capabilities);
  emscripten::function("generate", &wasm_generate);
  emscripten::function("lastError", &wasm_last_error);
}
