from huggingface_hub import hf_hub_download
import os

repo_id = 'xororz/CuteYukiMix'
files = ['clip_v2.mnn', 'unet_asym_block32.mnn', 'vae_decoder_fp16.mnn', 'tokenizer.json']
out_dir = os.path.join(os.getcwd(), 'models', 'CuteYukiMix')
os.makedirs(out_dir, exist_ok=True)
for name in files:
    path = hf_hub_download(repo_id=repo_id, filename=name, local_dir=out_dir, local_dir_use_symlinks=False)
    print(path)
