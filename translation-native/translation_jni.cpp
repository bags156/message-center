#include <jni.h>
#include <string>
#include <vector>
#include "translate_kit/translate_kit.h"

// Java serializes ALL calls on Translation.worker. Loaded models are kept between requests (below).
// Pass UTF-8 byte arrays rather than JNI modified UTF-8: emoji and supplementary scripts
// must survive round trips exactly. No message text is logged or passed to a network API.
static tk_context* context = nullptr;
static std::string bytes(JNIEnv* e, jbyteArray a) {
    if (!a) return {};
    auto n = e->GetArrayLength(a); std::string s(n, '\0');
    e->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(s.data())); return s;
}
static jbyteArray result(JNIEnv* e, const std::string& s) {
    auto a = e->NewByteArray(s.size());
    if (a) e->SetByteArrayRegion(a, 0, s.size(), reinterpret_cast<const jbyte*>(s.data())); return a;
}
static bool init() { return context || tk_init(&context) == TK_OK; }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_bags156_messagecenter_TranslationNative_detect(JNIEnv* e, jclass, jbyteArray input) {
    if (!init()) return nullptr;
    auto text = bytes(e, input); tk_language_result r{};
    if (tk_detect_language(context, text.c_str(), &r) != TK_OK || r.confidence < 0.6f) return nullptr;
    return result(e, r.language);
}
// Loaded models are kept (at most two: a route through English uses two) so the next message
// doesn't reload them; Java unloads them when translation has been idle a while.
struct Loaded { std::string key; tk_model* model; };
static std::vector<Loaded> loaded;
static tk_model* model_for(const tk_model_spec& spec, const std::string& key) {
    for (size_t i = 0; i < loaded.size(); i++) if (loaded[i].key == key) {
        Loaded hit = loaded[i]; loaded.erase(loaded.begin() + i); loaded.push_back(hit); return hit.model;
    }
    tk_model* fresh = nullptr;
    if (tk_load_model(context, &spec, &fresh) != TK_OK || !fresh) return nullptr;
    loaded.push_back({key, fresh});
    while (loaded.size() > 2) { tk_model_close(loaded.front().model); loaded.erase(loaded.begin()); }
    return fresh;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_bags156_messagecenter_TranslationNative_translate(JNIEnv* e, jclass, jbyteArray input,
        jbyteArray source, jbyteArray target, jbyteArray modelPath, jbyteArray vocabPath, jbyteArray shortlistPath) {
    if (!init()) return nullptr;
    auto text=bytes(e,input), from=bytes(e,source), to=bytes(e,target), model=bytes(e,modelPath),
         vocab=bytes(e,vocabPath), shortlist=bytes(e,shortlistPath);
    // Marian chooses its binary / SentencePiece readers by filename extension.
    // Reject a bad path before entering those readers, which can abort on a wrong format.
    auto ends=[](const std::string& s,const std::string& suffix){return s.size()>=suffix.size() && s.compare(s.size()-suffix.size(),suffix.size(),suffix)==0;};
    if (!ends(model,".bin") || !ends(vocab,".spm") || !ends(shortlist,".bin")) return nullptr;
    const char* vocabs[] = {vocab.c_str()};
    tk_model_spec spec{from.c_str(),to.c_str(),model.c_str(),vocabs,1,shortlist.c_str(),nullptr,1};
    tk_model* ready=model_for(spec, from + "\n" + to + "\n" + model + "\n" + vocab + "\n" + shortlist);
    if (!ready) return nullptr;
    tk_translation_result translated{};
    auto status=tk_translate(ready,text.c_str(),0,&translated);
    jbyteArray output=status==TK_OK && translated.text ? result(e,translated.text) : nullptr;
    tk_translation_result_free(&translated);return output;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_bags156_messagecenter_TranslationNative_unload(JNIEnv*, jclass) {
    for (auto& l : loaded) tk_model_close(l.model);
    loaded.clear();
}
