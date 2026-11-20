#pragma once
#include <string>
#include <functional>
#include <memory>
#include <vector>
struct llama_model;
struct llama_context;
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
    int reused_=0,processed_=0;
    void forgetCache();
public:
    explicit QualityModel(const std::string& path);
    ~QualityModel();
    QualityModel(const QualityModel&)=delete;
    QualityModel& operator=(const QualityModel&)=delete;
    std::string translate(const std::string& prefix,const std::string& text,const std::string& suffix,const std::function<bool()>& cancelled);
    /** Drops what the context holds (the next message processes everything): for measuring, and after a failure. */
    void forget(){forgetCache();}
    /** For the last message: tokens already in the context, and tokens processed for it. */
    int reused()const{return reused_;}
    int processed()const{return processed_;}
};
