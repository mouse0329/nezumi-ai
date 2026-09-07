#pragma once

// Diffusion scheduler implementations (PLMS/DDIM/Euler/EulerA/LCM/DPM++/DPM-Solver-2/UniPC)
// and their shared sigma helpers + CFG rescale.
//
// 旧 mnn_session.cpp の "sigma 系スケジューラー共通ヘルパ" から
// 各ステップ関数までを一括してここへ移動した。

#include <random>
#include <vector>

namespace mnn_sd_detail
{

    // Bug fix (スケジューラを切り替えても同じ絵が出る問題):
    //   以前の engine はユーザーの選択に関係なく PLMS (PNDM) 固定で、
    //   Kotlin/JNI 側では値を届けているのに実際の denoise リングが
    //   切り替わらなかった。ここで DDIM / Euler / (LCMはEulerベース) を
    //   実装し、同じ seed でもスケジューラごとに見た目が変わるようにする。
    //
    //   DPM-Solver-2 / DPM++ 2M / UniPC-bh2 まで VP 定式で実装する。
    enum class ActiveScheduler
    {
        PLMS = 0,        // PNDM / PLMS (bootstrap 1 step)
        DDIM,            // deterministic; eta=0
        EULER,           // 単純オイラー法
        EULER_A,         // Euler ancestral (ステップの途中で ancestral ノイズを足す)
        LCM_STEP,        // LCM の 1 ステップ式 (x0 -> x_prev = sqrt(a_prev)*x0 + sqrt(1-a_prev)*noise)
        DPMPP_2M,        // DPM-Solver++ 2M 多ステップ (linear sigma schedule)
        DPMPP_2M_KARRAS, // DPM-Solver++ 2M + Karras ノイズスケジュール
        DPM_SOLVER_2,    // DPM-Solver 2 多ステップ (noise-prediction, Lu et al. 2022)
        UNIPC            // UniPC-bh2 order 2 (Zhao et al. 2023, predict_x0)
    };

    // ---------------------------------------------------------------
    // sigma 系スケジューラー共通ヘルパ
    // ---------------------------------------------------------------
    float sigma_from_alpha(float a);
    int timestep_from_sigma(float sigma, const std::vector<float> &alphas_cumprod);
    void scale_k_to_vp(const std::vector<float> &src, float sigma, std::vector<float> &dst);
    std::vector<float> build_karras_sigmas(int steps, const std::vector<float> &alphas_cumprod);
    std::vector<float> sigmas_from_timesteps(const std::vector<int> &timesteps,
                                             const std::vector<float> &alphas_cumprod);
    void ksigma_to_vp(float ksig, float &alpha, float &sigma_vp);
    float vp_lambda(float alpha, float sigma_vp);

    // CFG Rescale (Lin et al., 2024)
    float vec_std(const float *v, size_t n);
    void apply_cfg_rescale(std::vector<float> &combined,
                           const std::vector<float> &pred_cond,
                           float phi);

    struct UniPCState
    {
        std::vector<std::vector<float>> x0s;
        std::vector<int> step_ids;
        std::vector<float> last_sample;
        int this_order = 1;
        int lower_order_nums = 0;
    };

    std::vector<float> dpmpp_2m_step(const std::vector<float> &sample,
                                     const std::vector<float> &eps,
                                     int step_index,
                                     const std::vector<float> &sigmas,
                                     const std::vector<int> &timesteps,
                                     const std::vector<float> &alphas_cumprod,
                                     std::vector<float> &x0_prev_out,
                                     const std::vector<float> &x0_prev_in);

    std::vector<float> dpm_solver_2_step(const std::vector<float> &sample,
                                         const std::vector<float> &eps,
                                         int step_index,
                                         const std::vector<float> &sigmas,
                                         std::vector<float> &eps_prev_out,
                                         const std::vector<float> &eps_prev_in);

    std::vector<float> unipc_step(std::vector<float> sample,
                                  const std::vector<float> &eps,
                                  int step_index,
                                  const std::vector<float> &sigmas,
                                  UniPCState &st);

    std::vector<float> euler_a_step(const std::vector<float> &sample,
                                    const std::vector<float> &eps,
                                    int step_index,
                                    const std::vector<float> &sigmas,
                                    std::mt19937 &rng);

    std::vector<float> lcm_step(const std::vector<float> &sample,
                                const std::vector<float> &eps,
                                int step_index,
                                const std::vector<int> &timesteps,
                                const std::vector<float> &alphas_cumprod,
                                std::mt19937 &rng);

    std::vector<float> ddim_step(const std::vector<float> &sample,
                                 const std::vector<float> &model_output,
                                 int step_index,
                                 const std::vector<int> &timesteps,
                                 const std::vector<float> &alphas_cumprod);

    std::vector<float> euler_step(const std::vector<float> &sample,
                                  const std::vector<float> &model_output,
                                  int step_index,
                                  const std::vector<int> &timesteps,
                                  const std::vector<float> &alphas_cumprod);

    std::vector<float> pndm_step(const std::vector<float> &sample,
                                 const std::vector<float> &model_output,
                                 int step_index,
                                 const std::vector<int> &timesteps,
                                 const std::vector<float> &alphas_cumprod,
                                 std::vector<std::vector<float>> &ets,
                                 std::vector<float> &pndm_prev_sample);

} // namespace mnn_sd_detail
