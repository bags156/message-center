#include "quality_core.h"
#include "llama.h"
#include <algorithm>
#include <thread>
#include <stdexcept>
#include <vector>
#include <chrono>
namespace {
void quiet(ggml_log_level,const char*,void*) {}
void check(const std::function<bool()>& cancelled){if(cancelled())throw std::runtime_error("Translation cancelled");}
std::vector<llama_token> tokens(const llama_vocab* vocab,const std::string& words,bool bos,bool special){
    int n=-llama_tokenize(vocab,words.data(),words.size(),nullptr,0,bos,special);
    if(n<0)throw std::runtime_error("Tokenization failed");std::vector<llama_token> out(n);
    int used=llama_tokenize(vocab,words.data(),words.size(),out.data(),out.size(),bos,special);
    if(used<0)throw std::runtime_error("Tokenization failed");out.resize(used);return out;
}
}
QualityModel::QualityModel(const std::string& path){
    static bool initialized=[](){llama_log_set(quiet,nullptr);llama_backend_init();return true;}();(void)initialized;
    auto p=llama_model_default_params();p.n_gpu_layers=0;p.load_mode=LLAMA_LOAD_MODE_MMAP;p.use_extra_bufts=false;
    model_=llama_model_load_from_file(path.c_str(),p);if(!model_)throw std::runtime_error("Translation model could not load");
}
QualityModel::~QualityModel(){if(model_)llama_model_free(model_);}
std::string QualityModel::translate(const std::string& prefix,const std::string& text,const std::string& suffix,const std::function<bool()>& cancelled){
    check(cancelled);const auto* vocab=llama_model_get_vocab(model_);auto input=tokens(vocab,prefix,true,true);
    auto body=tokens(vocab,text,false,false),end=tokens(vocab,suffix,false,true);
    input.insert(input.end(),body.begin(),body.end());input.insert(input.end(),end.begin(),end.end());
    if(input.empty()||input.size()>1400)throw std::runtime_error("Message fragment is too long for the translation model");
    auto p=llama_context_default_params();p.n_ctx=2048;p.n_batch=256;p.n_ubatch=128;
    p.n_threads=std::max(1u,std::min(4u,std::thread::hardware_concurrency()));p.n_threads_batch=p.n_threads;p.no_perf=true;
    std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model_,p),llama_free);
    if(!ctx)throw std::runtime_error("Not enough memory for the translation model");
    std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()),llama_batch_ext_free);
    if(!batch)throw std::runtime_error("Translation batch could not initialize");
    auto eval=[&](const llama_token* ids,int n,int start){check(cancelled);llama_batch_ext_clear(batch.get());
        for(int i=0;i<n;i++){int idx=llama_batch_ext_add_token(batch.get(),0,ids[i]);llama_pos pos=start+i;llama_batch_ext_set_pos(batch.get(),idx,&pos);}
        llama_batch_ext_set_output_logits(batch.get(),n-1,true);
        if(llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get()))throw std::runtime_error("Translation inference failed");};
    for(size_t i=0;i<input.size();i+=256)eval(input.data()+i,std::min<size_t>(256,input.size()-i),i);
    std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
    llama_sampler_chain_add(sampler.get(),llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),64,1.0f,0,0));llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
    auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(90);
    std::string output;int max=std::min<int>(1024,2048-input.size()-1);
    for(int i=0;i<max;i++){check(cancelled);if(std::chrono::steady_clock::now()>deadline)throw std::runtime_error("Translation took too long; the original is unchanged");llama_token id=llama_sampler_sample(sampler.get(),ctx.get(),-1);
        if(llama_vocab_is_eog(vocab,id)){if(output.empty())throw std::runtime_error("Translation was empty");return output;}
        char buffer[256];int n=llama_token_to_piece(vocab,id,buffer,sizeof(buffer),0,false);
        if(n<0){std::vector<char> more(-n);n=llama_token_to_piece(vocab,id,more.data(),more.size(),0,false);if(n<0)throw std::runtime_error("Translation token failed");output.append(more.data(),n);}else output.append(buffer,n);
        if(output.size()>text.size()*6+1024)throw std::runtime_error("Translation produced too much text; the original is unchanged");
        if(output.size()>384){std::string tail=output.substr(output.size()-128);auto at=output.find(tail);if(at<output.size()-128&&text.find(tail)==std::string::npos)throw std::runtime_error("Translation started repeating; the original is unchanged");}
        eval(&id,1,input.size()+i);
    }
    throw std::runtime_error("Translation did not finish; the original message is unchanged");
}
