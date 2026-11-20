// Runs the app's stronger-translation core (quality-native/quality_core.cpp) over a corpus of casual
// messages, under prompt variants, recording every output and how long it took; and times messages
// that start fresh (as before) against ones that keep the instruction processed (as now).
// A developer tool: synthetic messages only; nothing here runs in the app.
#include "quality_core.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

struct Case { std::string id, from, to, previous, text; };

static std::string language(const std::string& code) {
    if (code == "en") return "English";
    if (code == "es") return "Spanish";
    if (code == "es-MX") return "Mexican Spanish";
    return code;
}

// Must match QualityPlan.prefix(from, to, casual) in the app exactly: "app" is the casual prompt the
// app uses, "plain" its literal fallback (TranslateGemma's own prompt). The app also tidies Spanish
// output afterwards (QualityPlan.tidy: one gender form, closed ¡ ¿), which this harness doesn't.
static std::string prefix(const std::string& variant, const Case& c) {
    const std::string& from = c.from; const std::string& to = c.to;
    std::string s = language(from), t = language(to);
    std::string head = "<start_of_turn>user\nYou are a professional " + s + " (" + from + ") to " + t + " (" + to + ") translator. Your goal is to accurately convey the meaning and nuances of the original " + s + " text while adhering to " + t + " grammar, vocabulary, and cultural sensitivities.\n";
    std::string extra;
    if (variant == "app") {
        extra = "This is a casual text message between friends or family. Translate it the way a native speaker would text it, keeping its tone, and translate slang and idioms by what they mean.";
        if (from == "es") extra += " It may use Mexican or other Latin American slang.";
        if (to == "es") extra += " Use Latin American Spanish, with tú, and ustedes for more than one person. When it isn't clear whether someone is a man or a woman, use the masculine form.";
        extra += " Keep every emoji exactly as it is.\n";
    }
    return head + extra + "Produce only the " + t + " translation, without any additional explanations or commentary. Please translate the following " + s + " text into " + t + ":\n\n\n";
}

static const std::string SUFFIX = "<end_of_turn>\n<start_of_turn>model\n";

static std::string json(const std::string& s) {
    std::string out = "\"";
    for (unsigned char ch : s) {
        if (ch == '"') out += "\\\""; else if (ch == '\\') out += "\\\\"; else if (ch == '\n') out += "\\n"; else if (ch == '\t') out += "\\t";
        else if (ch < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", ch); out += b; } else out += (char)ch;
    }
    return out + "\"";
}

int main(int argc, char** argv) {
    if (argc < 4) { std::cerr << "usage: quality_eval model.gguf cases.tsv out.jsonl\n"; return 2; }
    std::vector<Case> cases;
    std::ifstream in(argv[2]); std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::vector<std::string> f; std::stringstream ss(line); std::string part;
        while (std::getline(ss, part, '\t')) f.push_back(part);
        while (f.size() < 5) f.push_back("");
        cases.push_back({f[0], f[1], f[2], f[3], f[4]});
    }
    std::ofstream out(argv[3]);
    auto never = [] { return false; };
    auto loadStart = std::chrono::steady_clock::now();
    QualityModel model(argv[1]);
    // Warm up, so the timings below don't include reading the model in.
    model.translate(prefix("app", cases[0]), cases[0].text, SUFFIX, never);
    out << "{\"kind\":\"load\",\"ms\":" << std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - loadStart).count() << "}\n";
    // Speed: the same messages starting fresh each time (as before), then keeping the instruction (as
    // now), alternating directions the way a conversation does (their Spanish in, your English out).
    std::vector<const Case*> toSpanish, toEnglish, alternating;
    for (const Case& c : cases) (c.to == "es" ? toSpanish : toEnglish).push_back(&c);
    for (size_t i = 0; i < 5 && i < toSpanish.size() && i < toEnglish.size(); i++) { alternating.push_back(toEnglish[i]); alternating.push_back(toSpanish[i]); }
    for (const char* mode : {"fresh", "kept"}) {
        for (size_t i = 0; i < alternating.size(); i++) {
            const Case& c = *alternating[i];
            if (std::string(mode) == "fresh") model.forget();
            auto t0 = std::chrono::steady_clock::now();
            std::string said = model.translate(prefix("app", c), c.text, SUFFIX, never);
            long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            out << "{\"kind\":\"speed\",\"mode\":\"" << mode << "\",\"id\":" << json(c.id) << ",\"ms\":" << ms << ",\"reused\":" << model.reused() << ",\"processed\":" << model.processed() << ",\"output\":" << json(said) << "}\n";
            out.flush();
        }
    }
    // Naturalness: every message under each prompt variant (context only where there's a previous message).
    for (const char* variant : {"plain", "app"}) {
        for (const Case& c : cases) {
            if (std::string(variant) == "context" && c.previous.empty()) continue;
            std::string said, error;
            auto t0 = std::chrono::steady_clock::now();
            try { said = model.translate(prefix(variant, c), c.text, SUFFIX, never); } catch (const std::exception& e) { error = e.what(); }
            long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            out << "{\"kind\":\"translation\",\"variant\":\"" << variant << "\",\"id\":" << json(c.id) << ",\"from\":" << json(c.from) << ",\"to\":" << json(c.to)
                << ",\"previous\":" << json(c.previous) << ",\"text\":" << json(c.text) << ",\"output\":" << json(said) << ",\"error\":" << json(error) << ",\"ms\":" << ms << "}\n";
            out.flush();
            std::cerr << variant << " " << c.id << " " << ms << " ms" << (error.empty() ? "" : " ERROR " + error) << "\n";
        }
    }
    return 0;
}
