#include "engine_detail/sd_schedulers.h"
#include "engine_detail/sd_log.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>
#include <vector>

#if defined(MNN_SD_HAS_MNN)

namespace mnn_sd_detail
{
    // Bug fix (スケジューラを切り替えても同じ絵が出る問題):
    //   以前の engine はユーザーの選択に関係なく PLMS (PNDM) 固定で、
    //   Kotlin/JNI 側では値を届けているのに実際の denoise リングが
    //   切り替わらなかった。ここで DDIM / Euler / (LCMはEulerベース) を
    //   実装し、同じ seed でもスケジューラごとに見た目が変わるようにする。
    //
    //   DPM-Solver-2 / DPM++ 2M / UniPC-bh2 まで VP 定式で実装する。

    // ---------------------------------------------------------------
    // sigma 系スケジューラー共通ヘルパ
    // ---------------------------------------------------------------
    // sigma_t = sqrt((1 - alpha_t) / alpha_t)
    inline float sigma_from_alpha(float a)
    {
        return std::sqrt((1.0f - a) / std::max(a, 1e-6f));
    }

    // sigma = sqrt((1-a)/a)  ⇒  a = 1/(1+sigma^2)
    // UNet の timestep 条件付けを、solver が使っている sigma に揃える。
    int timestep_from_sigma(float sigma, const std::vector<float> &alphas_cumprod)
    {
        if (alphas_cumprod.empty())
            return 0;
        const float s = std::max(sigma, 0.0f);
        const float target_a = 1.0f / (1.0f + s * s);
        int best = 0;
        float best_d = std::fabs(alphas_cumprod[0] - target_a);
        for (int t = 1; t < (int)alphas_cumprod.size(); ++t)
        {
            const float d = std::fabs(alphas_cumprod[t] - target_a);
            if (d < best_d)
            {
                best_d = d;
                best = t;
            }
        }
        return best;
    }

    // k-diffusion 空間の sample を VP 訓練済み UNet 入力へ戻す。
    // x_vp = x_k / sqrt(sigma^2 + 1)
    void scale_k_to_vp(const std::vector<float> &src, float sigma, std::vector<float> &dst)
    {
        const float inv = 1.0f / std::sqrt(sigma * sigma + 1.0f);
        dst.resize(src.size());
        for (size_t i = 0; i < src.size(); ++i)
            dst[i] = src[i] * inv;
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
        //
        // Bug fix (2026-08 スケジューラ深堀り調査 / S-4):
        //   最終手前ステップ (step_index == num_steps - 2) は次段の
        //   sigma_next が急激に 0 へ落ちるため h_i = λ_next - λ_cur が非常に
        //   大きくなり、r = h_prev / h_i がすぐ下限 (5e-2) に飽和する。
        //   飽和すると coef_prev = -0.5/r ≈ -10 まで振れて旧 x0 推定に強い
        //   負の寄与が入り、少ステップ Karras (steps=8..12) で「末尾付近だけ
        //   色が飛ぶ」現象を再現させていた。DPM-Solver++ の原論文 (Lu et al.
        //   2022) 実装でも最終区間は 1 次に落とすのが推奨なので、
        //   sigma_next が既に極小な最終手前区間は multistep を切る。
        //   なお sigma_next <= 1e-8f の完全最終段は上で早期 return 済み。
        std::vector<float> denoised(N);
        const bool is_penultimate = (step_index >= num_steps - 2);
        const bool has_prev = !x0_prev_in.empty() && x0_prev_in.size() == N &&
                              step_index >= 2 && !is_penultimate;
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

    inline void ksigma_to_vp(float ksig, float &alpha, float &sigma_vp)
    {
        ksig = std::max(ksig, 0.0f);
        alpha = 1.0f / std::sqrt(ksig * ksig + 1.0f);
        sigma_vp = ksig * alpha;
        if (sigma_vp < 1e-8f)
            sigma_vp = 1e-8f;
    }

    inline float vp_lambda(float alpha, float sigma_vp)
    {
        return std::log(std::max(alpha, 1e-8f)) - std::log(std::max(sigma_vp, 1e-8f));
    }

    /**
     * DPM-Solver-2 多ステップ (Lu et al. 2022, algorithm_type=dpmsolver, midpoint).
     * noise-prediction。1 ステップ 1 UNet。初回と最終手前は 1 次 (DDIM 相当)。
     */
    std::vector<float> dpm_solver_2_step(
        const std::vector<float> &sample,
        const std::vector<float> &eps,
        int step_index,
        const std::vector<float> &sigmas,
        std::vector<float> &eps_prev_out,
        const std::vector<float> &eps_prev_in)
    {
        const size_t N = sample.size();
        const int nsig = (int)sigmas.size();
        if (nsig < 2 || step_index + 1 >= nsig)
        {
            eps_prev_out = eps;
            return sample;
        }

        float alpha_s, sigma_s, alpha_t, sigma_t;
        ksigma_to_vp(sigmas[step_index], alpha_s, sigma_s);
        ksigma_to_vp(sigmas[step_index + 1], alpha_t, sigma_t);
        const float lambda_s = vp_lambda(alpha_s, sigma_s);
        const float lambda_t = vp_lambda(alpha_t, sigma_t);
        const float h = std::max(lambda_t - lambda_s, 1e-5f);
        const float expm1_h = std::expm1(h);

        const bool last = (sigmas[step_index + 1] <= 1e-8f) || (step_index + 2 >= nsig);
        if (last)
        {
            std::vector<float> x0(N);
            for (size_t i = 0; i < N; ++i)
                x0[i] = (sample[i] - sigma_s * eps[i]) / alpha_s;
            eps_prev_out = eps;
            return x0;
        }

        const bool has_prev = !eps_prev_in.empty() && eps_prev_in.size() == N &&
                              step_index >= 1 && step_index + 2 < nsig;
        std::vector<float> next(N);
        if (!has_prev)
        {
            const float coef = sigma_t * expm1_h;
            const float scale = alpha_t / alpha_s;
            for (size_t i = 0; i < N; ++i)
            {
                float v = scale * sample[i] - coef * eps[i];
                if (!std::isfinite(v))
                    v = 0.0f;
                next[i] = v;
            }
        }
        else
        {
            float alpha_s1, sigma_s1;
            ksigma_to_vp(sigmas[step_index - 1], alpha_s1, sigma_s1);
            const float lambda_s1 = vp_lambda(alpha_s1, sigma_s1);
            const float h0 = std::max(lambda_s - lambda_s1, 1e-5f);
            const float r0 = std::min(std::max(h0 / h, 5e-2f), 5.0f);
            const float coef = sigma_t * expm1_h;
            const float scale = alpha_t / alpha_s;
            for (size_t i = 0; i < N; ++i)
            {
                const float D0 = eps[i];
                const float D1 = (D0 - eps_prev_in[i]) / r0;
                float v = scale * sample[i] - coef * D0 - 0.5f * coef * D1;
                if (!std::isfinite(v))
                    v = 0.0f;
                next[i] = v;
            }
        }
        eps_prev_out = eps;
        return next;
    }

    /**
     * UniPC-bh2, solver_order=2, predict_x0=True (Zhao et al. 2023 / diffusers).
     * 1 ステップ 1 UNet。corrector は前回予測を現在の x0 で補正し、その後 predictor。
     */
    std::vector<float> unipc_step(
        std::vector<float> sample,
        const std::vector<float> &eps,
        int step_index,
        const std::vector<float> &sigmas,
        UniPCState &st)
    {
        const size_t N = sample.size();
        const int nsig = (int)sigmas.size();
        const int solver_order = 2;
        if (nsig < 2 || step_index + 1 >= nsig)
            return sample;

        float alpha_s, sigma_s;
        ksigma_to_vp(sigmas[step_index], alpha_s, sigma_s);

        std::vector<float> x0_cur(N);
        for (size_t i = 0; i < N; ++i)
            x0_cur[i] = (sample[i] - sigma_s * eps[i]) / alpha_s;

        const bool use_corrector = (step_index > 2 && !st.last_sample.empty() &&
                                    st.last_sample.size() == N && !st.x0s.empty());
        if (use_corrector)
        {
            const int order = std::max(1, st.this_order);
            float alpha_t, sigma_t, alpha_s0, sigma_s0;
            ksigma_to_vp(sigmas[step_index], alpha_t, sigma_t);
            ksigma_to_vp(sigmas[step_index - 1], alpha_s0, sigma_s0);
            const float lambda_t = vp_lambda(alpha_t, sigma_t);
            const float lambda_s0 = vp_lambda(alpha_s0, sigma_s0);
            const float h = std::max(lambda_t - lambda_s0, 1e-5f);
            const float hh = -h;
            const float h_phi_1 = std::expm1(hh);
            const float B_h = std::expm1(hh);
            const std::vector<float> &m0 = st.x0s.back();
            const std::vector<float> &x = st.last_sample;

            float rho_last = 0.5f;
            float rho0 = 0.0f;
            bool has_d1 = false;
            float rk = 1.0f;
            if (order >= 2 && st.x0s.size() >= 2 && step_index >= 2)
            {
                float alpha_si, sigma_si;
                ksigma_to_vp(sigmas[step_index - 2], alpha_si, sigma_si);
                const float lambda_si = vp_lambda(alpha_si, sigma_si);
                rk = (lambda_si - lambda_s0) / h;
                if (std::fabs(rk) > 1e-4f)
                {
                    has_d1 = true;
                    float h_phi_k = h_phi_1 / hh - 1.0f;
                    const float b0 = h_phi_k * 1.0f / B_h;
                    h_phi_k = h_phi_k / hh - 0.5f;
                    const float b1 = h_phi_k * 2.0f / B_h;
                    const float det = 1.0f - rk;
                    if (std::fabs(det) > 1e-6f)
                    {
                        rho0 = (b0 - b1) / det;
                        rho_last = b0 - rho0;
                    }
                }
            }

            for (size_t i = 0; i < N; ++i)
            {
                float xt = (sigma_t / sigma_s0) * x[i] - alpha_t * h_phi_1 * m0[i];
                float corr = 0.0f;
                if (has_d1)
                {
                    const float D1 = (st.x0s[st.x0s.size() - 2][i] - m0[i]) / rk;
                    corr += rho0 * D1;
                }
                const float D1t = x0_cur[i] - m0[i];
                xt -= alpha_t * B_h * (corr + rho_last * D1t);
                if (!std::isfinite(xt))
                    xt = sample[i];
                sample[i] = xt;
            }
        }

        if ((int)st.x0s.size() >= solver_order)
        {
            st.x0s.erase(st.x0s.begin());
            st.step_ids.erase(st.step_ids.begin());
        }
        st.x0s.push_back(x0_cur);
        st.step_ids.push_back(step_index);

        int remain = (int)sigmas.size() - 1 - step_index;
        int this_order = std::min(solver_order, std::max(1, remain));
        this_order = std::min(this_order, st.lower_order_nums + 1);
        st.this_order = this_order;
        st.last_sample = sample;

        if (sigmas[step_index + 1] <= 1e-8f)
        {
            if (st.lower_order_nums < solver_order)
                st.lower_order_nums++;
            return x0_cur;
        }

        float alpha_t, sigma_t, alpha_s0, sigma_s0;
        ksigma_to_vp(sigmas[step_index + 1], alpha_t, sigma_t);
        ksigma_to_vp(sigmas[step_index], alpha_s0, sigma_s0);
        const float lambda_t = vp_lambda(alpha_t, sigma_t);
        const float lambda_s0 = vp_lambda(alpha_s0, sigma_s0);
        const float h = std::max(lambda_t - lambda_s0, 1e-5f);
        const float hh = -h;
        const float h_phi_1 = std::expm1(hh);
        const float B_h = std::expm1(hh);
        const std::vector<float> &m0 = st.x0s.back();

        std::vector<float> next(N);
        const bool use_d1 = (this_order >= 2 && st.x0s.size() >= 2 && step_index >= 1);
        float rk = 1.0f;
        if (use_d1)
        {
            float alpha_si, sigma_si;
            ksigma_to_vp(sigmas[step_index - 1], alpha_si, sigma_si);
            const float lambda_si = vp_lambda(alpha_si, sigma_si);
            rk = (lambda_si - lambda_s0) / h;
            if (std::fabs(rk) <= 1e-4f)
            {
                // fall back to order 1
            }
        }
        const bool d1_ok = use_d1 && std::fabs(rk) > 1e-4f;
        for (size_t i = 0; i < N; ++i)
        {
            float xt = (sigma_t / sigma_s0) * sample[i] - alpha_t * h_phi_1 * m0[i];
            if (d1_ok)
            {
                const float D1 = (st.x0s[st.x0s.size() - 2][i] - m0[i]) / rk;
                xt -= alpha_t * B_h * (0.5f * D1);
            }
            if (!std::isfinite(xt))
                xt = m0[i];
            next[i] = xt;
        }

        if (st.lower_order_nums < solver_order)
            st.lower_order_nums++;
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
     *
     * 補足 (2026-08 スケジューラ深堀り調査 / S-8):
     *   diffusers の LCMScheduler は本来 c_skip * x + c_out * x0 + sigma * n の
     *   consistency-model 式で更新する。SD1.5 の LCM 蒸留 checkpoint は
     *   c_skip = 0, c_out = 1 に相当するため、上の VP posterior 版と数値的に
     *   一致する ("naive LCM"; xororz/local-dream もこの形)。LCM-LoRA + 高
     *   CFG で本家と微妙にずれる可能性はあるが、少ステップ挙動 (4..8) は
     *   実測で本家 diffusers と同等の絵になることを確認している。
     *   完全な LCMScheduler (c_skip/c_out, timestep_scaling=10) を使う。
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
        const float sqrt_a_t = std::sqrt(std::max(a_t, 1e-8f));
        const float sqrt_a_prev = std::sqrt(std::max(a_prev, 0.0f));
        const float sqrt_1_minus_at = std::sqrt(std::max(0.0f, 1.0f - a_t));
        const float sqrt_1_minus_prev = std::sqrt(std::max(0.0f, 1.0f - a_prev));

        const float scaled_t = (float)t * 10.0f;
        const float sigma_data = 0.5f;
        const float sd2 = sigma_data * sigma_data;
        const float st2 = scaled_t * scaled_t;
        const float c_skip = sd2 / (st2 + sd2);
        const float c_out = scaled_t / std::sqrt(st2 + sd2);

        const size_t N = sample.size();
        std::vector<float> next(N);
        const bool is_last = (step_index + 1 >= (int)timesteps.size());
        std::normal_distribution<float> dist(0.0f, 1.0f);
        for (size_t i = 0; i < N; ++i)
        {
            float x0 = (sample[i] - sqrt_1_minus_at * eps[i]) / sqrt_a_t;
            float denoised = c_out * x0 + c_skip * sample[i];
            if (is_last)
                next[i] = denoised;
            else
                next[i] = sqrt_a_prev * denoised + sqrt_1_minus_prev * dist(rng);
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
    //
    // Bug fix (2026-08 スケジューラ深堀り調査 / S-2 + S-14 + S-11):
    //   旧実装は step_index==1 (bootstrap 2 回目) で ets を push しない設計だった
    //   ため、次の step_index==2 に入った時点で ets_sz==1 のままとなり、
    //   `else if (ets_sz == 2)` / `else if (ets_sz == 3)` のいずれにも該当せず、
    //   4 項 Adams-Bashforth の else ブランチに落ちて `ets[ets_sz - 4]` =
    //   `ets[-3]` の out-of-bounds アクセスを引き起こす経路があった。少ステップ
    //   PLMS (steps<=4) や、bootstrap 直後にキャンセル/再開が挟まる経路で SEGV
    //   に化ける可能性がある。加えて img2img (denoise_start >= 1) では
    //   step_index==0 の分岐が呼ばれないまま step_index==1 に到達し、
    //   `pndm_prev_sample` が空のまま `src = pndm_prev_sample` に参照されて
    //   空 vector アクセス (SEGV) になる別経路もあった。
    //   対処:
    //     (1) step_index==1 でも ets に push する (bootstrap 2 回目でも近似は
    //         必要で、push しないメリットがない)。
    //     (2) img2img で step_index==0 をスキップして start した場合の保険として
    //         pndm_prev_sample が空なら sample で初期化する (Diffusers 相当)。
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

        // Bug fix (S-2 / S-14): step_index==1 でも ets を積む。旧実装の
        //   "push しない" 分岐は次段の ets_sz カウントを狂わせ、最終的に
        //   ets[-3] への OOB アクセスに繋がっていた。
        if (ets.size() >= 4)
            ets.erase(ets.begin());
        ets.push_back(mo);
        if (step_index == 1)
        {
            // bootstrap 2 回目: Diffusers の counter==1 と同じく、prk 直前の
            //   (timesteps[0], timesteps[1]) の遷移を担当する。timestep 選択のみ
            //   置き換え、ets ring は保持する。
            timestep = timesteps[0];
            prev_timestep = (timesteps.size() >= 2) ? timesteps[1] : 0;
        }

        int ets_sz = (int)ets.size();
        size_t N = sample.size();
        std::vector<float> blended(N);

        // Bug fix (S-11): img2img で denoise_start>=1 のとき step_index==0 の
        //   pndm_prev_sample 初期化を通っていない経路がある。空だったら sample
        //   で埋めておく (Diffusers の PNDMScheduler もこの前提)。
        if (pndm_prev_sample.empty())
            pndm_prev_sample = sample;

        if (step_index == 0)
        {
            pndm_prev_sample = sample;
            blended = mo;
        }
        else if (step_index == 1)
        {
            // ets には step0 のぶんと今回 push した step1 のぶんの計 2 本以上
            //   入っている前提。旧コードと同じ 2 点平均で bootstrap する。
            const int last = ets_sz - 1;
            const int prev = (ets_sz >= 2) ? (ets_sz - 2) : last;
            for (size_t i = 0; i < N; ++i)
                blended[i] = (ets[last][i] + ets[prev][i]) * 0.5f;
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
} // namespace mnn_sd_detail

#endif // MNN_SD_HAS_MNN
