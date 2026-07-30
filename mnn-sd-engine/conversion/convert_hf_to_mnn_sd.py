#!/usr/bin/env python3
"""
SD1.5 (HuggingFace) → nezumi-ai / xororz-sd-mnn 互換 MNN 変換スクリプト（修正版）

現行版で "動かなかった" 主な原因は以下の 5 点。全部この版で潰してある:
  1. UNet の timestep は **int32** で渡す必要がある (ランタイム側 host<int>() で書き込み)
  2. token_emb.bin は **FP16 (uint16)** で保存する (ランタイム標準・vocab 自動判定)
  3. CLIP は **clip_skip=2 相当** (penultimate 層) を焼き込む
  4. CLIP は **batch=1, [1,77,768]** でエクスポート (ランタイムは 2 回逐次実行)
  5. 出力ファイル名を model.json 既定 (clip_v2 / unet_asym_block32 / vae_decoder_fp16) に合わせる

さらにモデルサイズを削るための追加スイッチも用意した:
  --unet-bits 4|8         UNet の重み量子化 bit 幅 (既定 8)
  --unet-block 32|64|128  block-wise 量子化 (既定 32、精度が最も安定)
  --vae-fp16              VAE decoder を fp16 で保存 (既定 True)
  --token-emb-fp16        token_emb.bin を fp16 で保存 (既定 True, サイズ半減)

使い方例:
  python convert_sd15_to_mnn.py \
      --model runwayml/stable-diffusion-v1-5 \
      --out ./out/CuteYukiMix \
      --size 512 \
      --unet-bits 4 --unet-block 32
"""
import argparse, json, os, shutil, subprocess, sys, gc
from pathlib import Path
from typing import Optional
import numpy as np
import torch
import torch.nn as nn


def log(msg): print(f"[convert] {msg}", flush=True)
def run(cmd): log("$ " + " ".join(cmd)); subprocess.check_call(cmd)


# --------------------------------------------------------------------------- #
# ONNX -> MNN 変換ヘルパ
# --------------------------------------------------------------------------- #
def which_mnnconvert():
    return [sys.executable, "-m", "MNN.tools.mnnconvert"]


def onnx_to_mnn(onnx_path, mnn_path, *, fp16=False, quant_bits=0,
                quant_block=0, asymmetric=True):
    """
    quant_bits:  0=無効, 4 or 8
    quant_block: 0=無効, 32/64/128  (block-wise の粒度)
    """
    conv = which_mnnconvert()
    args = [*conv, "-f", "ONNX",
            "--modelFile", str(onnx_path),
            "--MNNModel", str(mnn_path),
            "--bizCode", "mnn-sd15-v1",
            "--optimizeLevel", "1"]
    if fp16:
        args.append("--fp16")
    if quant_bits > 0:
        args += ["--weightQuantBits", str(quant_bits)]
        if asymmetric:
            args.append("--weightQuantAsymmetric")
        if quant_block > 0:
            args += ["--weightQuantBlock", str(quant_block)]
    args.append("--saveExternalData")
    run(args)


# --------------------------------------------------------------------------- #
# torch SDPA を ONNX 化可能な素朴実装に差し替え
# (torch 2.x の scaled_dot_product_attention は opset 14 では出せないため)
# --------------------------------------------------------------------------- #
def scaled_dot_product_attention_patch(query, key, value, attn_mask=None,
                                       dropout_p=0.0, is_causal=False, scale=None):
    """
    素朴な matmul+softmax+matmul 実装 (チャンク分割はしない)。
    ★ 重要: チャンク分割版は Python の for ループ回数が
      torch.onnx.export トレース時のシーケンス長 L で固定され、
      dynamic_axes を付けても解像度ごとに変化しない。結果、トレース時と
      異なる解像度で実行すると shape エラーは出ないが、チャンク境界が
      ズレて出力が壊れる (実機で "cat" が意味不明な画像になった原因)。
      メモリ削減効果も実測でほぼ無かったため、チャンク分割は行わない。
    """
    L, S = query.size(-2), key.size(-2)
    scale_factor = 1 / (query.size(-1) ** 0.5) if scale is None else scale
    attn_weight = query @ key.transpose(-2, -1) * scale_factor
    if is_causal:
        mask = torch.ones(L, S, dtype=torch.bool, device=query.device).tril(diagonal=0)
        attn_weight.masked_fill_(mask.logical_not(), float("-inf"))
    if attn_mask is not None:
        if attn_mask.dtype == torch.bool:
            attn_weight.masked_fill_(attn_mask.logical_not(), float("-inf"))
        else:
            attn_weight += attn_mask
    return torch.softmax(attn_weight, dim=-1) @ value



torch.nn.functional.scaled_dot_product_attention = scaled_dot_product_attention_patch

# --------------------------------------------------------------------------- #
# torch GroupNorm を ONNX 化可能な素朴実装に差し替え
# (PyTorch 標準の GroupNorm -> ONNX 変換は Reshape の shape に "0" (=入力次元をそのまま
#  コピーする ONNX の特殊値) を焼き込むが、MNN のコンバータがこれを正しく解釈できず
#  "Reshape error: 0 -> N" として壊れる。ここでは全て具体的なリテラル整数だけを使う
#  reshape に置き換えることで、"0" プレースホルダーが一切生成されないようにする)
# --------------------------------------------------------------------------- #
def group_norm_patch(input, num_groups, weight=None, bias=None, eps=1e-5):
    """
    解像度非依存版。バッチ*空間次元は -1 で動的に任せ (ONNX Reshape の
    "0=入力からコピー" プレースホルダーではなく、shape[0] を明示的に
    織り込んだ動的な -1 として扱われることを期待)、チャンネル数だけ
    リテラル整数で扱う。これにより 128/192/256/320/384/448/512 など
    複数解像度で同一の UNet/VAE .mnn を使い回せることを狙う。
    """
    C = int(input.shape[1])
    G = num_groups
    N = input.shape[0]
    # バッチ次元だけ明示、空間次元はまとめて -1 に押し込む
    # (チャンネルはグループ数で割り切れる前提)
    x = input.reshape(N, G, C // G, -1)
    dims = tuple(range(2, x.dim()))
    mean = x.mean(dim=dims, keepdim=True)
    var = x.var(dim=dims, unbiased=False, keepdim=True)
    x = (x - mean) / torch.sqrt(var + eps)
    x = x.reshape_as(input)
    if weight is not None:
        x = x * weight.view(1, C, *([1] * (input.dim() - 2)))
    if bias is not None:
        x = x + bias.view(1, C, *([1] * (input.dim() - 2)))
    return x


torch.nn.functional.group_norm = group_norm_patch


def _patch_vae_attn_processor_dynamic():
    """
    VAE mid_block の Attention を dynamic shape 対応にする。
    height/width を Python int に落とさず、reshape を -1 で扱う。
    """
    import torch
    import torch.nn.functional as F
    from diffusers.models.attention_processor import AttnProcessor2_0, AttnProcessor

    def _dynamic_call(self, attn, hidden_states, encoder_hidden_states=None,
                      attention_mask=None, temb=None, *args, **kwargs):
        residual = hidden_states

        if attn.spatial_norm is not None:
            hidden_states = attn.spatial_norm(hidden_states, temb)

        input_ndim = hidden_states.ndim
        if input_ndim == 4:
            b = hidden_states.shape[0]
            c = hidden_states.shape[1]
            hidden_states = hidden_states.reshape(b, c, -1).transpose(1, 2)

        batch_size, sequence_length, _ = (
            hidden_states.shape if encoder_hidden_states is None
            else encoder_hidden_states.shape
        )

        if attention_mask is not None:
            attention_mask = attn.prepare_attention_mask(attention_mask, sequence_length, batch_size)
            attention_mask = attention_mask.view(batch_size, attn.heads, -1, attention_mask.shape[-1])

        if attn.group_norm is not None:
            hidden_states = attn.group_norm(hidden_states.transpose(1, 2)).transpose(1, 2)

        query = attn.to_q(hidden_states)
        if encoder_hidden_states is None:
            encoder_hidden_states = hidden_states
        elif attn.norm_cross:
            encoder_hidden_states = attn.norm_encoder_hidden_states(encoder_hidden_states)

        key = attn.to_k(encoder_hidden_states)
        value = attn.to_v(encoder_hidden_states)

        inner_dim = key.shape[-1]
        head_dim = inner_dim // attn.heads

        query = query.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)
        key = key.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)
        value = value.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)

        hidden_states = F.scaled_dot_product_attention(
            query, key, value, attn_mask=attention_mask, dropout_p=0.0, is_causal=False
        )

        hidden_states = hidden_states.transpose(1, 2).reshape(batch_size, -1, attn.heads * head_dim)
        hidden_states = hidden_states.to(query.dtype)

        hidden_states = attn.to_out[0](hidden_states)
        hidden_states = attn.to_out[1](hidden_states)

        if input_ndim == 4:
            h = residual.shape[2]
            w = residual.shape[3]
            hidden_states = hidden_states.transpose(-1, -2).reshape(
                hidden_states.shape[0], residual.shape[1], h, w
            )

        if attn.residual_connection:
            hidden_states = hidden_states + residual

        hidden_states = hidden_states / attn.rescale_output_factor
        return hidden_states

    AttnProcessor2_0.__call__ = _dynamic_call
    AttnProcessor.__call__ = _dynamic_call


def make_distribution_zip(out_dir: Path, zip_name: Optional[str]) -> Path:
    """
    out_dir 直下の配布対象ファイル (model.json, *.mnn, *.mnn.weight, *.bin,
    tokenizer.json など) だけを、out_dir と同じ階層に zip としてまとめる。
    _onnx/ や _diffusers_from_single_file/ のような中間生成ディレクトリは
    含めない (out_dir 直下のファイルのみを対象にしているため自動的に除外される)。

    これまで手動で行っていた以下のような操作を自動化したもの:
        cd out && zip -r ../ModelName-mnn-int8-block32.zip .
    """
    import zipfile

    name = zip_name or out_dir.name
    if not name.lower().endswith(".zip"):
        name += ".zip"
    zip_path = out_dir.parent / name

    # out_dir 直下に前回実行の .zip が残っている場合、今回の zip の対象から
    # 除外する (二重梱包・サイズ集計の狂いを防ぐ)。
    files = sorted(
        p for p in out_dir.iterdir()
        if p.is_file() and p.suffix.lower() != ".zip"
    )
    if not files:
        log(f"WARNING: no files found directly under {out_dir}; zip will be empty")

    log(f"Creating distribution zip: {zip_path}")
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in files:
            zf.write(p, arcname=p.name)

    sz = zip_path.stat().st_size
    log(f"  {zip_path.name}  {sz/1024/1024:8.2f} MB  ({len(files)} files)")
    return zip_path


# --------------------------------------------------------------------------- #
# single-file (.safetensors / .ckpt) 自動変換
# --------------------------------------------------------------------------- #
SINGLE_FILE_EXTS = (".safetensors", ".ckpt", ".pt")


def is_single_file_checkpoint(model_arg: str) -> bool:
    """
    --model に渡された文字列が「単一 checkpoint ファイル」を指しているかを判定する。
    以下のいずれの形でも True になる (diffusers.from_single_file がそのまま
    受け付けられる形式に合わせてある):

      1. ローカルファイルパス            ./rev_1.2.2-fp16.safetensors
      2. 直接 URL                        https://huggingface.co/.../foo.safetensors
      3. HF repo_id + ファイル名         s6yx/ReV_Animated/rev_1.2.2/rev_1.2.2-fp16.safetensors
                                          cyberdelia/CyberRealistic/CyberRealistic_FINAL_FP16.safetensors

    HF repo ID 単体 (例: 'runwayml/stable-diffusion-v1-5', 'emilianJR/majicMIX_realistic_v6')
    や diffusers 形式のローカルディレクトリはここでは False になる。
    repo ID 単体との区別は「拡張子で終わっているか」で行う: diffusers 形式の
    repo は 'org/name' の形でファイル拡張子を持たないため。
    """
    return model_arg.lower().endswith(SINGLE_FILE_EXTS)


def _looks_like_url(s: str) -> bool:
    return s.startswith("http://") or s.startswith("https://")


def _fix_hf_url_for_single_file(url: str) -> str:
    """
    ユーザーが 'resolve/main/' 形式の URL を直接 --model に貼り付けた場合に備えた
    保険。diffusers.from_single_file は 'blob/main/' を前提に内部で 'resolve/main/'
    へ変換するため、'resolve/main/' のまま渡すと二重パスになり 404 になる
    (normalize_single_file_ref のコメント参照)。'resolve/main/' を見つけたら
    'blob/main/' に置き換えてから渡す。
    """
    if "huggingface.co/" in url and "/resolve/main/" in url:
        fixed = url.replace("/resolve/main/", "/blob/main/")
        log(f"Rewriting resolve/main URL -> blob/main to avoid double-path 404: "
            f"'{url}' -> '{fixed}'")
        return fixed
    return url


def _looks_like_local_path(s: str) -> bool:
    return s.startswith(".") or s.startswith("/") or s.startswith("~") or (
        len(s) > 1 and s[1] == ":"  # Windows ドライブレター (C:\...)
    ) or os.path.exists(s)


def normalize_single_file_ref(model_arg: str) -> str:
    """
    --model が 'repo_id/subpath/file.safetensors' の形 (HF repo 内のファイルを
    指す省略形) で渡された場合、diffusers.from_single_file が公式にサポートする
    'https://huggingface.co/<repo_id>/blob/main/<subpath>/file.safetensors'
    形式の URL に展開する。ローカルパスや URL、あるいは既に repo 直下のファイルを
    指す 2 階層形式は diffusers 側がそのまま解釈できるため変更しない。

    ★ 重要: ここは必ず 'blob/main/' を使うこと。'resolve/main/' を使うと、
      diffusers 内部が (blob -> resolve への変換を前提に) 再度
      'resolve/main/' を付与してしまい、
      '.../resolve/main/resolve/main/...' という二重パスになって 404 になる
      不具合を実機で確認済み。'blob/main/' であれば diffusers 側が正しく
      1 回だけ 'resolve/main/' に変換してくれる。
    """
    if _looks_like_url(model_arg) or _looks_like_local_path(model_arg):
        return model_arg

    parts = model_arg.split("/")
    # 'org/repo/....../file.ext' の 3 階層以上だけを URL に展開する。
    # 'org/repo' や 'org/repo/file.ext' (2 階層) は diffusers が
    # 直接 repo_id として解釈できるため、そのまま渡す。
    if len(parts) >= 3 and is_single_file_checkpoint(model_arg):
        repo_id = "/".join(parts[:2])
        subpath = "/".join(parts[2:])
        url = f"https://huggingface.co/{repo_id}/blob/main/{subpath}"
        log(f"Expanding HF shorthand '{model_arg}' -> '{url}'")
        return url
    return model_arg


def resolve_model_dir(args, out_dir: Path, work_dir: Path) -> str:
    """
    --model が単一 checkpoint ファイル (ローカルパス / URL / HF repo 内の
    ファイルへのショートハンド) の場合、diffusers 形式に自動変換してそのディレクトリ
    パスを返す。diffusers 形式 (HF repo ID / ローカルディレクトリ) の場合は
    そのまま args.model を返す。

    これまで手動で行っていた以下の手順を自動化したもの:
        huggingface-cli download <repo> <file>
        pipe = StableDiffusionPipeline.from_single_file(path, ...)
        pipe.save_pretrained(diffusers_dir)
    """
    model_ref = normalize_single_file_ref(args.model)

    if not is_single_file_checkpoint(model_ref) and not _looks_like_url(model_ref):
        return args.model

    if _looks_like_url(model_ref):
        model_ref = _fix_hf_url_for_single_file(model_ref)

    from diffusers import StableDiffusionPipeline

    diffusers_dir = out_dir / "_diffusers_from_single_file"
    if diffusers_dir.exists() and (diffusers_dir / "model_index.json").exists():
        log(f"Single-file checkpoint already converted, reusing: {diffusers_dir}")
        return str(diffusers_dir)

    log(f"--model looks like a single-file checkpoint ({model_ref}); "
        f"auto-converting to diffusers format first...")
    log("  (this downloads the file directly via diffusers.from_single_file; "
        "no separate huggingface-cli download step is needed)")

    kwargs = dict(torch_dtype=torch.float32, safety_checker=None)
    if args.original_config_file:
        kwargs["original_config_file"] = args.original_config_file

    try:
        pipe = StableDiffusionPipeline.from_single_file(model_ref, **kwargs)
    except Exception as e:
        log(f"ERROR: from_single_file() failed: {e}")
        log("If this is an architecture-detection error, try passing "
            "--original-config-file with the matching v1-inference.yaml (or similar).")
        log("If this is a 404 / repo-not-found error, double check the exact filename "
            "on the model's 'Files and versions' tab on Hugging Face.")
        raise

    diffusers_dir.mkdir(parents=True, exist_ok=True)
    pipe.save_pretrained(str(diffusers_dir))
    log(f"  Saved intermediate diffusers checkpoint to: {diffusers_dir}")

    del pipe
    gc.collect()

    return str(diffusers_dir)


# --------------------------------------------------------------------------- #
# ラッパーモデル群
# --------------------------------------------------------------------------- #
class CLIPTextEncoderNoEmbedSkip(nn.Module):
    """
    xororz / nezumi-ai 互換 CLIP:
      入力: input_embedding [1, 77, 768]  (float32, token_emb + pos_emb を CPU で加算済み)
      出力: last_hidden_state [1, 77, 768] (float32)

    ★ clip_skip=2 対応 ★
      SD1.5 系の多くの二次配布モデル (CuteYukiMix 系含む) は penultimate hidden
      state に final_layer_norm を掛けたテンソルを条件として使う。
      transformer 全 12 層のうち最後の 1 層をスキップして 11 層目の出力を final_layer_norm
      に流し込む形で焼き込む。
    """
    def __init__(self, hf_text_encoder, clip_skip: int = 2):
        super().__init__()
        # encoder 内の layers を必要な深さだけコピー
        full_encoder = hf_text_encoder.text_model.encoder
        keep_layers = len(full_encoder.layers) - (clip_skip - 1)
        # clip_skip=1 で全層 / =2 で最後の 1 層を落とす / =3 で 2 層落とす
        self.layers = nn.ModuleList(list(full_encoder.layers)[:keep_layers])
        self.final_layer_norm = hf_text_encoder.text_model.final_layer_norm

        mask = torch.full((77, 77), float("-inf"))
        self.register_buffer(
            "causal_mask",
            torch.triu(mask, diagonal=1)[None, None, :, :],
            persistent=False,
        )

    def forward(self, input_embedding):
        # input_embedding: [B, 77, D]
        hidden = input_embedding
        causal = self.causal_mask.expand(input_embedding.shape[0], 1, 77, 77)
        for layer in self.layers:
            # CLIPEncoderLayer.forward(hidden_states, attention_mask, causal_attention_mask, ...)
            out = layer(hidden, None, causal, False)
            hidden = out[0]
        return self.final_layer_norm(hidden)


class UNetWrapper(nn.Module):
    """
    ランタイムは timestep を int32 で書き込むため、
    ★ ここは必ず torch.int64/int32 で受ける ★
    (diffusers UNet2DConditionModel.forward の timestep は int でも long でもOK)
    """
    def __init__(self, unet):
        super().__init__()
        self.unet = unet

    def forward(self, sample, timestep, encoder_hidden_states):
        # timestep: [1] int32/int64
        t = timestep.reshape(-1).to(torch.long).expand(sample.shape[0])
        return self.unet(sample=sample, timestep=t,
                         encoder_hidden_states=encoder_hidden_states,
                         return_dict=False)[0]


class VAEDecoderWrapper(nn.Module):
    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, latent_sample):
        return self.vae.decode(latent_sample, return_dict=False)[0]


class VAEEncoderWrapper(nn.Module):
    """
    img2img 用 VAE encoder ラッパー。

    ランタイム側の実装に合わせ、DiagonalGaussianDistribution の
    サンプリング (mean + std * eps) はここでは行わず、mean と logvar から
    導いた std を出力し、ノイズのサンプリングと scaling_factor の乗算は
    ランタイム側 (または呼び出し側) に委ねる設計にしてある。
    これは、乱数источник をどちら側で持つかを固定しないための判断。

    出力:
      latent_mean [1, 4, H/8, W/8]  - 潜在分布の平均
      latent_std  [1, 4, H/8, W/8]  - 潜在分布の標準偏差 (exp(0.5*logvar))

    img2img 呼び出し側は以下を行う想定:
      latent = (latent_mean + latent_std * randn_like(latent_mean)) * scaling_factor
      その後、通常の txt2img と同じ流れで denoise_strength に応じた
      partial diffusion (add_noise から steps 分だけ denoise) を行う。
    """
    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, image):
        # image: [1, 3, H, W]  float32, range [-1, 1] (呼び出し側で正規化済み)
        posterior = self.vae.encode(image, return_dict=False)[0]
        mean = posterior.mean
        std = torch.exp(0.5 * posterior.logvar)
        return mean, std


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True,
                    help="HF model ID or local path (e.g. runwayml/stable-diffusion-v1-5)")
    ap.add_argument("--out", required=True, help="出力ディレクトリ")
    ap.add_argument("--size", type=int, default=512, help="latent 解像度の元になる画像サイズ")
    ap.add_argument("--clip-skip", type=int, default=2,
                    help="CLIP の layers スキップ数 (SD1.5 の多くは 2)")
    ap.add_argument("--unet-bits", type=int, default=8, choices=[0, 4, 8],
                    help="UNet の重み量子化 bit 幅。0=無効, 4=小さい/速い, 8=標準")
    ap.add_argument("--unet-block", type=int, default=0, choices=[0, 32, 64, 128],
                    help="UNet の block-wise 量子化サイズ。0=無効（OpenCL GPU 互換）")
    ap.add_argument("--unet-asymmetric", action="store_true",
                    help="UNet の量子化を非対称 (asymmetric) にする。既定は対称 (symmetric)。"
                         "非対称の方がファイルは小さくなるが、一部のモバイル OpenCL ドライバで"
                         "ノイズ画像になることが実測で確認されているため、既定では無効。"
                         "CPU バックエンドのみで使う場合や、GPU 側で検証済みの場合に有効化する。")
    ap.add_argument("--clip-bits", type=int, default=8, choices=[0, 4, 8],
                    help="CLIP の重み量子化 bit 幅")
    ap.add_argument("--vae-bits", type=int, default=8, choices=[0, 4, 8],
                    help="VAE の重み量子化 bit 幅 (VAE は 4bit で崩れやすいので 8 推奨)")
    ap.add_argument("--no-vae-fp16", action="store_true",
                    help="指定すると VAE を fp16 化せず fp32 のまま (サイズ 2 倍)")
    ap.add_argument("--img2img", action="store_true",
                    help="VAE encoder も書き出し、img2img 対応の model.json を生成する。"
                         "vae_encoder.mnn (mean, std を出力) が追加される。"
                         "★ ランタイム (nezumi-ai-sd-cli / app) 側の img2img 実装が"
                         "揃うまでは、このフラグで出力したファイルは txt2img 専用ランタイムでは"
                         "無視されるだけで害はないが、model.json の 'vae_encoder' キーの有無を"
                         "見て機能を切り替えるランタイム実装が前提。")
    ap.add_argument("--no-token-emb-fp16", action="store_true",
                    help="指定すると token_emb.bin を fp32 で保存する (サイズ 2 倍)")
    ap.add_argument("--filenames", choices=["default", "nezumi"], default="nezumi",
                    help="出力ファイル名。nezumi=CuteYukiMix の model.json と同じ名前")
    ap.add_argument("--original-config-file", default=None,
                    help="single-file (.safetensors/.ckpt) 変換時に、アーキテクチャ自動推定が"
                         "失敗する場合に明示的に渡す original config yaml のパス/URL")
    ap.add_argument("--keep-single-file-diffusers", action="store_true",
                    help="single-file から変換した中間 diffusers ディレクトリを削除せず残す"
                         "(--out/_diffusers_from_single_file に保存される)")
    ap.add_argument("--zip", action="store_true",
                    help="変換完了後、出力ディレクトリを自動で zip 圧縮する。"
                         "zip は --out の親ディレクトリに作成され、中身は "
                         "--out ディレクトリの直下のファイル群 (model.json, *.mnn, "
                         "*.mnn.weight, *.bin, tokenizer.json) のみを含む "
                         "(中間生成物の _onnx/, _diffusers_from_single_file/ は含めない)。")
    ap.add_argument("--zip-name", default=None,
                    help="--zip 使用時の zip ファイル名 (拡張子 .zip は自動付与)。"
                         "省略時は --out の最終フォルダ名がそのまま使われる。"
                         "例: --out ./out/CyberRealistic-mnn-int8-block32 --zip "
                         "-> CyberRealistic-mnn-int8-block32.zip "
                         "(命名規則に沿ったファイルが欲しい場合は --out 自体を "
                         "'{モデル名}-mnn-int{bits}-block{block}' の形にしておくとよい)")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    work_dir = out_dir / "_onnx"
    work_dir.mkdir(parents=True, exist_ok=True)

    from diffusers import UNet2DConditionModel, AutoencoderKL
    from transformers import CLIPTextModel, CLIPTokenizer

    # --model が単一 .safetensors/.ckpt の場合、ここで diffusers 形式に自動変換し、
    # 以降の処理はすべて変換後のディレクトリを参照するようにする。
    args.model = resolve_model_dir(args, out_dir, work_dir)

    _patch_vae_attn_processor_dynamic()

    # ------------------------------------------------------------------ #
    # ★ ONNX エクスポートは基本 CPU / fp32 で行う ★
    #   GPU 経由の fp16 エクスポートは onnx opset 14 の Op でシンボリック関数が
    #   欠けて "TracerWarning: torch.tensor..." が出たり、MNN 側で解釈違いに
    #   なることがある。エクスポートは fp32、MNN 側で --fp16 する方が安全。
    # ------------------------------------------------------------------ #
    device = "cpu"
    dtype = torch.float32
    log(f"Using device: {device.upper()} ({dtype})")

    # ファイル名の対応
    if args.filenames == "nezumi":
        f_clip = "clip_v2.mnn"
        # The established sd-mnn GPU models use a conventional external-weight
        # UNet.  The previous asymmetric block-32 variant produces numerically
        # valid but unusable noisy images on some mobile OpenCL drivers.
        f_unet = "unet.mnn"
        f_vae = "vae_decoder_fp16.mnn" if not args.no_vae_fp16 else "vae_decoder.mnn"
        f_vae_enc = "vae_encoder_fp16.mnn" if not args.no_vae_fp16 else "vae_encoder.mnn"
    else:
        f_clip = "clip.mnn"
        f_unet = "unet.mnn"
        f_vae = "vae_decoder.mnn"
        f_vae_enc = "vae_encoder.mnn"

    # =================================================================== #
    # 1. CLIP TEXT ENCODER
    # =================================================================== #
    if not (out_dir / f_clip).exists():
        log("Exporting CLIP text encoder...")
        text_encoder = CLIPTextModel.from_pretrained(
            args.model, subfolder="text_encoder", low_cpu_mem_usage=True
        ).eval()
        hidden = text_encoder.config.hidden_size
        embeddings = text_encoder.text_model.embeddings

        # ------------------------------------------------------------- #
        # ★ token_emb.bin を書き出す (ランタイムは vocab を自動検出) ★
        #   FP16 版が現行主流。sd1.5 の CLIP は vocab=49408, dim=768
        #   -> FP32=~151MB / FP16=~75MB
        # ------------------------------------------------------------- #
        tok_w = embeddings.token_embedding.weight.detach().cpu().numpy()
        pos_w = embeddings.position_embedding.weight.detach().cpu().numpy()[:77]
        if args.no_token_emb_fp16:
            (out_dir / "token_emb.bin").write_bytes(tok_w.astype(np.float32).tobytes())
            log(f"  token_emb.bin (fp32) vocab={tok_w.shape[0]} dim={tok_w.shape[1]} "
                f"-> {tok_w.nbytes/1024/1024:.1f} MB")
        else:
            (out_dir / "token_emb.bin").write_bytes(tok_w.astype(np.float16).tobytes())
            log(f"  token_emb.bin (fp16) vocab={tok_w.shape[0]} dim={tok_w.shape[1]} "
                f"-> {tok_w.astype(np.float16).nbytes/1024/1024:.1f} MB")
        # pos_emb.bin は歴代 fp32 (77 * 768 = 231KB なので削る意味なし)
        (out_dir / "pos_emb.bin").write_bytes(pos_w.astype(np.float32).tobytes())

        # ------------------------------------------------------------- #
        # ★ ONNX 化: batch=1 固定・clip_skip 焼き込み ★
        # ------------------------------------------------------------- #
        onnx_r = work_dir / "clip.raw.onnx"
        model_to_run = CLIPTextEncoderNoEmbedSkip(text_encoder, clip_skip=args.clip_skip).eval()
        dummy = torch.randn(1, 77, hidden)  # batch=1 固定！
        torch.onnx.export(
            model_to_run, (dummy,), onnx_r.as_posix(),
            input_names=["input_embedding"],
            output_names=["last_hidden_state"],
            opset_version=14,
        )
        # CLIP は fp16 + int8 で通常 150MB -> ~40MB
        onnx_to_mnn(onnx_r, out_dir / f_clip,
                    fp16=True,
                    quant_bits=args.clip_bits, quant_block=0, asymmetric=True)
        del text_encoder, model_to_run
        gc.collect()

    # =================================================================== #
    # 2. UNet
    # =================================================================== #
    if not (out_dir / f_unet).exists():
        log("Exporting UNet (this can take a lot of RAM)...")
        unet = UNet2DConditionModel.from_pretrained(
            args.model, subfolder="unet", low_cpu_mem_usage=True, torch_dtype=torch.float32
        ).eval()
        hidden = unet.config.cross_attention_dim
        onnx_r = work_dir / "unet.raw.onnx"

        model_to_run = UNetWrapper(unet).eval()
        # ★ ランタイムのアップロード形状に完全に合わせる ★
        #   sample: [1, 4, H/8, W/8]   (batch=1, ランタイムが 2 回逐次実行)
        #   timestep: [1] int32
        #   encoder_hidden_states: [1, 77, 768]
        dummy_sample = torch.randn(1, 4, args.size // 8, args.size // 8)
        dummy_timestep = torch.tensor([999], dtype=torch.int32)   # ← ★ int32 ★
        dummy_ehs = torch.randn(1, 77, hidden)

        # ★ dynamic_axes で sample の H/W (latent の高さ・幅) をシンボリックに
        #   保つ。これを指定しないと GroupNorm/Transformer2DModel 内部の
        #   reshape が全部トレース時の解像度でリテラル固定されてしまい、
        #   変換時に指定した --size 以外の解像度で実行時に
        #   "Reshape error" が発生する (実測済み)。
        torch.onnx.export(
            model_to_run,
            (dummy_sample, dummy_timestep, dummy_ehs),
            onnx_r.as_posix(),
            input_names=["sample", "timestep", "encoder_hidden_states"],
            output_names=["out_sample"],
            opset_version=14,
            dynamic_axes={
                "sample": {0: "batch", 2: "height", 3: "width"},
                "out_sample": {0: "batch", 2: "height", 3: "width"},
            },
        )

        # GPU compatibility first: use conventional symmetric INT8 weights.
        # Asymmetric block quantization is smaller, but has proven unstable on
        # the target mobile OpenCL stack (CPU output is correct, GPU is noise).
        #  ★ 重要: --fp16 と --weightQuantBits を併用すると、UNet 規模の
        #    大きいグラフでは mnnconvert (このバージョン) が量子化を無視し、
        #    fp16 のみが適用されたファイルが出力される不具合を実測で確認済み
        #    (CLIP/VAE のような小さいグラフでは問題なく併用できる)。
        #    UNet だけは fp16=False にして --weightQuantBits 単独で量子化する
        #    ことで、期待通り fp32 の 1/4 サイズ (int8) まで縮む。
        #  - weightQuantBits=4 にすればさらに縮む
        #  - block=32 は品質面で有利 (本家 xororz/sd-mnn と同等の構成)
        #  - asymmetric はデフォルト False (対称・GPU 安全側)。
        #    --unet-asymmetric を指定した場合のみ非対称にする。
        if args.unet_asymmetric:
            log("  WARNING: --unet-asymmetric specified. Known to produce noisy output on "
                "some mobile OpenCL (GPU) backends. Verify on-device before shipping a "
                "GPU-backed build; CPU backend is generally unaffected.")
        onnx_to_mnn(onnx_r, out_dir / f_unet,
                    fp16=False,
                    quant_bits=args.unet_bits,
                    quant_block=args.unet_block,
                    asymmetric=args.unet_asymmetric)
        del unet, model_to_run
        gc.collect()

    # =================================================================== #
    # 3. VAE Decoder / Encoder
    # =================================================================== #
    need_vae_decoder = not (out_dir / f_vae).exists()
    need_vae_encoder = args.img2img and not (out_dir / f_vae_enc).exists()

    if need_vae_decoder or need_vae_encoder:
        vae = AutoencoderKL.from_pretrained(
            args.model, subfolder="vae", low_cpu_mem_usage=True, torch_dtype=torch.float32
        ).eval()

        # --- 強制的に動的パッチ版プロセッサを再適用 ---
        from diffusers.models.attention_processor import AttnProcessor
        for name, module in vae.named_modules():
            if module.__class__.__name__ == "Attention":
                module.set_processor(AttnProcessor())
        # --------------------------------------------

        if need_vae_decoder:
            log("Exporting VAE decoder...")
            onnx_r = work_dir / "vae.raw.onnx"

            model_to_run = VAEDecoderWrapper(vae).eval()
            # ランタイムは latent_sample [1, 4, H/8, W/8]
            dummy_latent = torch.randn(1, 4, args.size // 8, args.size // 8)
            # ★ UNet と同じ理由で VAE decoder も latent の H/W を動的にする
            torch.onnx.export(
                model_to_run, (dummy_latent,), onnx_r.as_posix(),
                input_names=["latent_sample"],
                output_names=["sample"],
                opset_version=14,
                dynamic_axes={
                    "latent_sample": {0: "batch", 2: "height", 3: "width"},
                    "sample": {0: "batch", 2: "height", 3: "width"},
                },
            )
            # VAE は int4 だと簡単に色が抜ける。int8 のままで fp16 の効果だけ乗せる
            onnx_to_mnn(onnx_r, out_dir / f_vae,
                        fp16=not args.no_vae_fp16,
                        quant_bits=args.vae_bits, quant_block=0, asymmetric=True)
            del model_to_run
            gc.collect()
        else:
            log(f"VAE decoder already exists ({f_vae}), skipping re-export.")

        # --------------------------------------------------------------- #
        # img2img: VAE encoder も書き出す (--img2img 指定時のみ)
        # 既にロード済みの vae インスタンスを再利用し、二重ロードを避ける。
        # デコーダが既に存在していて再変換をスキップする場合でも、
        # ここは独立して実行される (再変換時に --img2img だけ追加したい
        # ケースに対応するため)。
        # --------------------------------------------------------------- #
        if need_vae_encoder:
            log("Exporting VAE encoder (--img2img)...")
            onnx_enc = work_dir / "vae_encoder.raw.onnx"
            encoder_to_run = VAEEncoderWrapper(vae).eval()
            # image: [1, 3, H, W]  H/W は元画像サイズ (latent の 8 倍)
            dummy_image = torch.randn(1, 3, args.size, args.size)
            torch.onnx.export(
                encoder_to_run, (dummy_image,), onnx_enc.as_posix(),
                input_names=["image"],
                output_names=["latent_mean", "latent_std"],
                opset_version=14,
                dynamic_axes={
                    "image": {0: "batch", 2: "height", 3: "width"},
                    "latent_mean": {0: "batch", 2: "height", 3: "width"},
                    "latent_std": {0: "batch", 2: "height", 3: "width"},
                },
            )
            # decoder と同じ量子化方針 (int4 は色抜けしやすいため既定は int8)
            onnx_to_mnn(onnx_enc, out_dir / f_vae_enc,
                        fp16=not args.no_vae_fp16,
                        quant_bits=args.vae_bits, quant_block=0, asymmetric=True)
            del encoder_to_run
            gc.collect()

        del vae
        gc.collect()

    # =================================================================== #
    # 4. tokenizer.json + model.json
    # =================================================================== #
    tokenizer = CLIPTokenizer.from_pretrained(args.model, subfolder="tokenizer")
    tokenizer.save_pretrained(work_dir)
    src_json = work_dir / "tokenizer.json"
    if src_json.exists():
        shutil.copyfile(src_json, out_dir / "tokenizer.json")
    else:
        # fast tokenizer が無いと tokenizer.json を吐かないので transformers の
        # PreTrainedTokenizerFast 経由で作る
        try:
            from transformers import CLIPTokenizerFast
            fast = CLIPTokenizerFast.from_pretrained(args.model, subfolder="tokenizer")
            fast.save_pretrained(work_dir)
            src_json = work_dir / "tokenizer.json"
            if src_json.exists():
                shutil.copyfile(src_json, out_dir / "tokenizer.json")
            else:
                log("WARNING: tokenizer.json could not be generated")
        except Exception as e:
            log(f"WARNING: failed to save tokenizer.json: {e}")

    model_json = {
        "format": "mnn-sd15-v1",
        "base": "sd1.5",
        "clip": f_clip,
        "unet": f_unet,
        "vae_decoder": f_vae,
        "tokenizer": "tokenizer.json",
        "token_embedding": "token_emb.bin",
        "position_embedding": "pos_emb.bin",
        "clip_skip": args.clip_skip,
        "text_embedding_size": 768,
        "default_size": args.size,
    }
    if args.img2img and (out_dir / f_vae_enc).exists():
        # ランタイムはこのキーの有無で img2img 対応可否を判定する想定。
        # vae_encoder の出力は (latent_mean, latent_std) の2テンソルであり、
        # サンプリング (mean + std * eps) と scaling_factor の乗算は
        # ランタイム側で行う。VAEEncoderWrapper のクラスコメント参照。
        model_json["vae_encoder"] = f_vae_enc
        model_json["img2img"] = True
    with open(out_dir / "model.json", "w", encoding="utf-8") as f:
        json.dump(model_json, f, ensure_ascii=False, indent=2)

    log("=" * 60)
    log("Done. Files:")
    total = 0
    for p in sorted(out_dir.iterdir()):
        if p.is_file() and p.suffix.lower() != ".zip":
            sz = p.stat().st_size
            total += sz
            log(f"  {p.name:35s}  {sz/1024/1024:8.2f} MB")
        elif p.is_file() and p.suffix.lower() == ".zip":
            log(f"  {p.name:35s}  (existing zip, excluded from TOTAL below)")
    log(f"  {'TOTAL':35s}  {total/1024/1024:8.2f} MB")

    diffusers_dir = out_dir / "_diffusers_from_single_file"
    if diffusers_dir.exists() and not args.keep_single_file_diffusers:
        log(f"Removing intermediate diffusers checkpoint: {diffusers_dir} "
            f"(pass --keep-single-file-diffusers to keep it)")
        shutil.rmtree(diffusers_dir, ignore_errors=True)

    if args.zip:
        make_distribution_zip(out_dir, args.zip_name)


if __name__ == "__main__":
    main()