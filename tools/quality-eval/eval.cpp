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

// "current" must match QualityPlan.prefix in the app exactly.
static std::string prefix(const std::string& variant, const Case& c) {
    std::string from = c.from, to = c.to;
    if (variant == "mexican") { if (to == "es") to = "es-MX"; if (from == "es") from = "es-MX"; }
    std::string s = language(from), t = language(to);
    std::string head = "<start_of_turn>user\nYou are a professional " + s + " (" + from + ") to " + t + " (" + to + ") translator. Your goal is to accurately convey the meaning and nuances of the original " + s + " text while adhering to " + t + " grammar, vocabulary, and cultural sensitivities.\n";
    std::string extra;
    if (variant != "current") extra = "The text is a casual text message between friends or family. Keep its tone, slang and emoji, and write it the way a native speaker would text it.\n";
    if (variant == "context" && !c.previous.empty()) extra += "For context only (do not translate it), the message before it in the conversation was: \"" + c.previous + "\"\n";
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
    model.translate(prefix("current", cases[0]), cases[0].text, SUFFIX, never);
    out << "{\"kind\":\"load\",\"ms\":" << std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - loadStart).count() << "}\n";
    // Speed: the same messages starting fresh each time (as before), then keeping the instruction (as now).
    for (const char* mode : {"fresh", "kept"}) {
        for (size_t i = 0; i < 10 && i < cases.size(); i++) {
            const Case& c = cases[i < 5 ? i : cases.size() - 1 - i];
            if (std::string(mode) == "fresh") model.forget();
            auto t0 = std::chrono::steady_clock::now();
            std::string said = model.translate(prefix("current", c), c.text, SUFFIX, never);
            long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            out << "{\"kind\":\"speed\",\"mode\":\"" << mode << "\",\"id\":" << json(c.id) << ",\"ms\":" << ms << ",\"reused\":" << model.reused() << ",\"processed\":" << model.processed() << ",\"output\":" << json(said) << "}\n";
            out.flush();
        }
    }
    // Naturalness: every message under each prompt variant (context only where there's a previous message).
    for (const char* variant : {"current", "casual", "mexican", "context"}) {
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
