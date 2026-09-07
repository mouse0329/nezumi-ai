#include "mnn_sd/engine.h"
#include "engine_internal.h"

// mnn_session.cpp is now a thin translation-unit shim.
//
// The former single-file implementation (~4500 lines) was split into
// per-responsibility units under src/engine_detail/ for maintainability:
//   - sd_log.h          : PROBE_LOG / set_error / append_log / trim_heap_to_os
//   - sd_path.h         : build_model_path / file_exists
//   - sd_model_kind.h   : SdModelKind
//   - sd_session.{h,cpp}: ScheduleBundle / cache_file_for / create_interpreter_and_session
//   - sd_tensor_io.{h,cpp}: get_session_input/output_tensor / fill_input_* / read_output_f32
//   - sd_tokenizer.{h,cpp}: JSON scanners + ClipTokenizer BPE implementation
//   - sd_schedulers.{h,cpp}: ActiveScheduler + DDIM/Euler/EulerA/LCM/DPM++/DPM2/UniPC/PNDM
//   - sd_lifecycle.{h,cpp}: mnn_sd_initialize_sessions / mnn_sd_release_sessions / mnn_sd_probe_model
//   - sd_pipeline.cpp   : mnn_sd_run_pipeline (CLIP -> UNet denoise -> VAE decode)
//
// This file intentionally contains no code; CMake builds the parts above.
