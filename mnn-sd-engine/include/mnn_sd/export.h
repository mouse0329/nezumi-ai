#pragma once

#if defined(_WIN32)
#  if defined(MNN_SD_BUILD_SHARED)
#    if defined(MNN_SD_EXPORTS)
#      define MNN_SD_API __declspec(dllexport)
#    else
#      define MNN_SD_API __declspec(dllimport)
#    endif
#  else
#    define MNN_SD_API
#  endif
#else
#  if defined(MNN_SD_BUILD_SHARED) && defined(MNN_SD_EXPORTS)
#    define MNN_SD_API __attribute__((visibility("default")))
#  else
#    define MNN_SD_API
#  endif
#endif
