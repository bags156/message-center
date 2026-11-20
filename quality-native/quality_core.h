#pragma once
#include <string>
#include <functional>
#include <memory>
#include <vector>
struct llama_model;
struct llama_context;
struct llama_batch_ext;
class QualityModel {
    llama_model* model_=nullptr;
    // One context kept between messages. It holds two conversations' worth (two sequences), one for
    // each direction you translate in, with the tokens each holds: the instruction (the same for every
    // message in a language pair) is processed once, and each message after only adds its own. Two,
    // because a chat goes both ways: reading their Spanish, then sending your English.
    llama_context* ctx_=nullptr;
    struct Slot { std::vector<int> tokens; unsigned long used=0; };
    static constexpr int SLOTS=2;
    Slot slots_[SLOTS];
    unsigned long clock_=0;
    int reused_=0,processed_=0,written_=0;long promptMs_=0,writeMs_=0;
    bool repack_;int threads_;
    void forgetCache();
    void ensureContext();
    int place(const std::vector<int>& input,size_t& keep,bool scores);
    void read(int seq,const int* ids,int n,int start,struct llama_batch_ext* batch,const std::function<bool()>& cancelled);
public:
    /**
     * repack: weights rearranged at load for this CPU's matrix instructions (faster, but held in
     * memory rather than read from the file as needed). threads: 0 picks from the phone's fast cores.
     */
    explicit QualityModel(const std::string& path,bool repack=false,int threads=0);
    ~QualityModel();
    QualityModel(const QualityModel&)=delete;
    QualityModel& operator=(const QualityModel&)=delete;
    /** Reads an instruction in ahead of time, so the next message with it only reads itself (and the model's already in memory). */
    void prime(const std::string& prefix,const std::function<bool()>& cancelled);
    std::string translate(const std::string& prefix,const std::string& text,const std::string& suffix,const std::function<bool()>& cancelled);
    /** Drops what the context holds (the next message processes everything): for measuring, and after a failure. */
    void forget(){forgetCache();}
    /** For the last message: tokens already in the context, and tokens processed for it. */
    int reused()const{return reused_;}
    int processed()const{return processed_;}
    /** For the last message: tokens written, and time spent reading the prompt and writing the answer. */
    int written()const{return written_;}
    long promptMs()const{return promptMs_;}
    long writeMs()const{return writeMs_;}
    /** Threads it uses to write an answer. */
    int threads()const;
};
