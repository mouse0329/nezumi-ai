#include "engine_detail/sd_tensor_io.h"

#if defined(MNN_SD_HAS_MNN)
#include <MNN/Interpreter.hpp>
#endif

#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

#if defined(MNN_SD_HAS_MNN)

namespace mnn_sd_detail
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

    bool fill_input_f32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const float *data, size_t count)
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
                shape.push_back((int)count);
            else if (shape.size() >= 2)
            {
                int64_t tail_size = 1;
                for (size_t i = 1; i < shape.size(); ++i)
                    tail_size *= std::max(1, shape[i]);
                if (tail_size > 0 && count % tail_size == 0)
                    shape[0] = (int)(count / tail_size);
                else
                    shape[0] = std::max(1, (int)count);
            }
            else
                shape[0] = (int)count;
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
                shape.push_back((int)count);
            else if (shape.size() >= 2)
            {
                int64_t tail_size = 1;
                for (size_t i = 1; i < shape.size(); ++i)
                    tail_size *= std::max(1, shape[i]);
                if (tail_size > 0 && count % tail_size == 0)
                    shape[0] = (int)(count / tail_size);
                else
                    shape[0] = std::max(1, (int)count);
            }
            else
                shape[0] = (int)count;
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

    std::vector<float> read_output_f32(MNN::Interpreter *net, MNN::Session *session, const char *name)
    {
        auto *t = get_session_output_tensor(net, session, name);
        if (!t)
            return {};
        std::unique_ptr<MNN::Tensor> host(new MNN::Tensor(t, MNN::Tensor::CAFFE));
        if (!t->copyToHostTensor(host.get()))
            return {};
        const float *ptr = host->host<float>();
        if (!ptr)
            return {};
        return std::vector<float>(ptr, ptr + host->elementSize());
    }

} // namespace mnn_sd_detail

#endif // MNN_SD_HAS_MNN
