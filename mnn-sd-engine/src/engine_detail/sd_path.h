#pragma once

// File-path helpers shared across the engine parts.
//
// 旧 mnn_session.cpp に同居していた build_model_path / file_exists を
// ここへ移動。他パーツ (session 構築、パイプライン) からも使う。

#include <cstdio>
#include <string>

namespace mnn_sd_detail
{

    inline std::string build_model_path(const char *model_dir, const char *filename)
    {
        std::string path(model_dir);
        if (!path.empty() && path.back() != '/' && path.back() != '\\')
        {
            path += '/';
        }
        path += filename;
        return path;
    }

    inline bool file_exists(const std::string &path)
    {
        FILE *f = std::fopen(path.c_str(), "rb");
        if (!f)
            return false;
        std::fclose(f);
        return true;
    }

} // namespace mnn_sd_detail
