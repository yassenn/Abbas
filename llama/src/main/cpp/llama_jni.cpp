#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "sampling.h"

static void aichat_android_log_callback(ggml_log_level level, const char *text, void *) {
    int android_log_level;
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: android_log_level = ANDROID_LOG_DEBUG; break;
        case GGML_LOG_LEVEL_INFO:  android_log_level = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_WARN:  android_log_level = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_ERROR: android_log_level = ANDROID_LOG_ERROR; break;
        default:                   android_log_level = ANDROID_LOG_VERBOSE;
    }
    __android_log_print(android_log_level, "llama", "%s", text);
}

// ── Global state ────────────────────────────────────────────────────────

static constexpr int DEFAULT_CONTEXT_SIZE = 4096;
static constexpr int OVERFLOW_HEADROOM   = 4;
static constexpr int BATCH_SIZE          = 512;

static llama_model                * g_model    = nullptr;
static llama_context              * g_context  = nullptr;
static llama_batch                  g_batch;
static common_chat_templates_ptr    g_chat_templates;
static common_sampler             * g_sampler  = nullptr;

// Chat history: stores all messages for multi-turn conversation
static std::vector<common_chat_msg> g_chat_msgs;

// Generation state
static volatile bool g_stop_generation = false;
static bool           g_generating      = false;
static llama_pos      g_current_pos     = 0;
static llama_pos      g_stop_pos        = 0;

// Reasoning delimiters declared by the loaded model's chat template, populated in
// createContext(). Thinking models open the trace inside the generation prompt and
// close it with a marker token; surfacing these lets the app route the trace to its
// "Thinking" block instead of leaking it into the final answer.
static bool                     g_supports_thinking = false;
static std::string              g_think_start;
static std::vector<std::string> g_think_ends;
// Whether the next generation may emit a reasoning trace. When false the template's
// opened reasoning block is closed immediately so the model answers directly.
static bool                     g_enable_thinking = true;

// ── Helpers ──────────────────────────────────────────────────────────────

static std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '"' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out;
}

static void reset_state() {
    g_chat_msgs.clear();
    g_stop_generation = false;
    g_generating      = false;
    g_current_pos     = 0;
    g_stop_pos        = 0;
}

static int decode_tokens_batch(llama_context *ctx, llama_batch &batch,
                                const llama_tokens &tokens, llama_pos start_pos,
                                bool compute_last_logit = false) {
    for (int i = 0; i < (int)tokens.size(); i += BATCH_SIZE) {
        int cur_size = std::min((int)tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        for (int j = 0; j < cur_size; j++) {
            llama_token id = tokens[i + j];
            llama_pos pos = start_pos + i + j;
            bool want_logit = compute_last_logit && (i + j == (int)tokens.size() - 1);
            common_batch_add(batch, id, pos, {0}, want_logit);
        }
        if (llama_decode(ctx, batch) != 0) {
            LOGe("llama_decode failed at batch offset %d", i);
            return 1;
        }
    }
    return 0;
}

// Format `content` for `role` and append it to the running chat history.
// `add_ass` (the assistant generation prompt) must be true ONLY for the final
// user turn — the turn we generate from. Passing it for every user turn makes
// each mid-history user message open a spurious assistant turn, so the model
// sees a malformed conversation and answers poorly.
static std::string chat_format_and_add(const std::string &role, const std::string &content, bool add_ass) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    std::string formatted = common_chat_format_single(
        g_chat_templates.get(), g_chat_msgs, msg, add_ass, false);
    // Thinking disabled: the template already opened a reasoning block in the
    // generation prompt, so close it straight away (empty trace) and the model
    // answers directly — the "search engine" behaviour.
    if (add_ass && !g_enable_thinking && g_supports_thinking && !g_think_ends.empty()) {
        formatted += g_think_ends.front();
    }
    g_chat_msgs.push_back(msg);
    return formatted;
}

// ── JNI exports ──────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_init(JNIEnv *env, jobject, jstring jNativeLibDir) {
    llama_log_set(aichat_android_log_callback, nullptr);

    const char *path = env->GetStringUTFChars(jNativeLibDir, nullptr);
    LOGi("Loading CPU backends from %s", path);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(jNativeLibDir, path);

    llama_backend_init();
    LOGi("Backends initialized");
}

extern "C"
JNIEXPORT jint JNICALL
Java_ai_abbas_llama_LlamaJni_loadModel(JNIEnv *env, jobject, jstring jModelPath) {
    reset_state();

    // Free previous model if any
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_chat_templates.reset();
    g_supports_thinking = false;
    g_think_start.clear();
    g_think_ends.clear();
    g_enable_thinking = true;
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGi("Loading model from: %s", model_path);

    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!g_model) {
        LOGe("Failed to load model");
        return 1;
    }
    LOGi("Model loaded successfully");
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_ai_abbas_llama_LlamaJni_createContext(
    JNIEnv *, jobject, jint jNCtx, jint jNThreads, jint jNBatch) {

    if (!g_model) { LOGe("No model loaded"); return 1; }

    int n_ctx = jNCtx > 0 ? jNCtx : DEFAULT_CONTEXT_SIZE;
    int n_threads = jNThreads > 0 ? jNThreads : 4;
    int n_batch = jNBatch > 0 ? jNBatch : BATCH_SIZE;

    int trained_ctx = llama_model_n_ctx_train(g_model);
    if (n_ctx > trained_ctx) {
        LOGw("Requested context %d > trained %d; enforcing trained limit", n_ctx, trained_ctx);
        n_ctx = trained_ctx;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_batch;
    cparams.n_ubatch = n_batch;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    g_context = llama_init_from_model(g_model, cparams);
    if (!g_context) {
        LOGe("Failed to create context");
        return 2;
    }

    g_batch = llama_batch_init(n_batch, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");

    // Ask the model's own chat template how it frames reasoning, so the app knows
    // the exact opening/closing markers instead of guessing a tag family.
    {
        common_chat_templates_inputs tinputs;
        common_chat_msg probe;
        probe.role = "user";
        probe.content = "hi";
        tinputs.messages.push_back(probe);
        tinputs.add_generation_prompt = true;
        common_chat_params tp = common_chat_templates_apply(g_chat_templates.get(), tinputs);

        g_supports_thinking = tp.supports_thinking;
        g_think_start       = tp.thinking_start_tag;
        g_think_ends        = tp.thinking_end_tags;

        LOGi("Chat template reasoning: supports_thinking=%d start='%s' ends=%zu",
             (int) g_supports_thinking, g_think_start.c_str(), g_think_ends.size());
    }

    // Default sampler
    common_params_sampling sparams;
    sparams.temp = 0.7f;
    sparams.top_p = 0.9f;
    sparams.penalty_last_n = 64;
    sparams.penalty_repeat = 1.1f;
    g_sampler = common_sampler_init(g_model, sparams);

    LOGi("Context created: n_ctx=%d, threads=%d, batch=%d", n_ctx, n_threads, n_batch);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_setSamplerParams(
    JNIEnv *, jobject, jfloat jTemp, jfloat jTopP, jint jSeed) {

    if (!g_sampler || !g_model) return;

    common_sampler_free(g_sampler);
    common_params_sampling sparams;
    sparams.temp = (float)jTemp;
    sparams.top_p = (float)jTopP;
    // Without a repetition penalty small models degenerate into loops, especially
    // after a duplicated prompt turn.
    sparams.penalty_last_n = 64;
    sparams.penalty_repeat = 1.1f;
    if (jSeed >= 0) {
        sparams.seed = (uint32_t)jSeed;
    }
    g_sampler = common_sampler_init(g_model, sparams);
    LOGi("Sampler params: temp=%.2f top_p=%.2f seed=%d", (float)jTemp, (float)jTopP, jSeed);
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_resetChat(JNIEnv *, jobject) {
    reset_state();
    if (g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
    LOGi("Chat history reset");
}

extern "C"
JNIEXPORT jint JNICALL
Java_ai_abbas_llama_LlamaJni_submitMessages(
    JNIEnv *env, jobject,
    jstring jMessagesJson,
    jint jMaxTokens,
    jboolean jEnableThinking) {

    if (!g_context) { LOGe("No context"); return 1; }

    g_enable_thinking = (jEnableThinking == JNI_TRUE);

    // Parse messages JSON: [{"role":"user","content":"hello"}, ...]
    const char *json_str = env->GetStringUTFChars(jMessagesJson, nullptr);
    std::string json(json_str);
    env->ReleaseStringUTFChars(jMessagesJson, json_str);

    int max_tokens = jMaxTokens > 0 ? jMaxTokens : 512;

    // Clear KV cache and chat history for fresh submission
    reset_state();
    llama_memory_clear(llama_get_memory(g_context), false);
    g_current_pos = 0;

    // Parse with the vendored JSON parser. The previous hand-rolled scanner
    // located each object/array by searching for the first '}' / ']' character,
    // so any message containing a brace or bracket (markdown links, citations,
    // code) truncated the conversation and fed the model a corrupted prompt.
    // Expected: {"messages":[{"role":..,"content":..},...]} OR a bare array.
    std::vector<std::pair<std::string, std::string>> msgs;
    try {
        common_json root = common_json::parse(json);
        const common_json * arr = nullptr;
        if (root.is_object() && root.contains("messages")) {
            arr = &root.at("messages");
        } else if (root.is_array()) {
            arr = &root;
        }
        if (arr == nullptr || !arr->is_array()) {
            LOGe("No messages array found");
            return 2;
        }
        for (size_t i = 0; i < arr->size(); i++) {
            const common_json & m = arr->at(i);
            std::string role    = m.value("role", "");
            std::string content = m.value("content", "");
            if (!role.empty() && !content.empty()) {
                msgs.push_back({role, content});
            }
        }
    } catch (const std::exception & e) {
        LOGe("Failed to parse messages JSON: %s", e.what());
        return 2;
    }

    LOGi("Parsed %zu messages from JSON", msgs.size());

    // Process all messages to build chat history + KV cache
    const int n_msgs = (int)msgs.size();
    for (int mi = 0; mi < n_msgs; mi++) {
        const std::string & role    = msgs[mi].first;
        const std::string & content = msgs[mi].second; // already unescaped by the JSON parser

        const bool is_last = (mi == n_msgs - 1);
        // Only the final user turn opens an assistant turn to generate from.
        const bool generate_from_here = is_last && role == "user";

        std::string formatted = chat_format_and_add(role, content, generate_from_here);

        // parse_special MUST be true: the rendered prompt contains the model's own
        // control tokens (e.g. <|im_start|>/<|im_end|>). With parse_special=false
        // they were tokenized as literal text, so the model received a malformed,
        // plain-text conversation and produced poor output.
        auto tokens = common_tokenize(g_context, formatted,
            g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get()),
            /*parse_special=*/true);

        if (g_current_pos + (int)tokens.size() >= llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {
            LOGw("Context overflow, stopping at %d messages", mi);
            g_chat_msgs.pop_back();
            break;
        }

        // Decode this turn. The final (user) turn requests the last logit so the
        // first nextToken() call samples from a populated output buffer.
        decode_tokens_batch(g_context, g_batch, tokens, g_current_pos, generate_from_here);
        g_current_pos += (int)tokens.size();
    }

    g_stop_pos = g_current_pos + max_tokens;
    g_stop_generation = false;
    g_generating = true;
    LOGi("Messages submitted. pos=%d, stop_pos=%d", g_current_pos, g_stop_pos);
    return 0;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_ai_abbas_llama_LlamaJni_nextToken(JNIEnv *env, jobject) {
    if (!g_context || !g_generating) return nullptr;
    if (g_stop_generation || g_current_pos >= g_stop_pos) {
        g_generating = false;
        LOGi("Generation complete (stop=%d pos=%d stop_pos=%d)",
             g_stop_generation, g_current_pos, g_stop_pos);
        return nullptr;
    }

    // Sample next token
    llama_token id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, id, true);

    // Check for EOS
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) {
        g_generating = false;
        LOGi("EOS token generated");
        return nullptr;
    }

    // special=false keeps ordinary template/control tokens out of the visible
    // answer. Reasoning delimiters (e.g. </think>) are special tokens that render
    // to nothing this way, so re-render them — the app needs to see the marker to
    // split the reasoning trace from the final answer. Normal text is identical
    // under either flag, so only special tokens are affected.
    std::string piece = common_token_to_piece(g_context, id, /*special=*/false);
    if (piece.empty()) {
        piece = common_token_to_piece(g_context, id, /*special=*/true);
    }

    // Decode the token
    common_batch_clear(g_batch);
    common_batch_add(g_batch, id, g_current_pos, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("llama_decode failed during generation");
        g_generating = false;
        return nullptr;
    }
    g_current_pos++;

    // Return the raw UTF-8 bytes. A token piece can end mid-character (the
    // tokenizer splits multi-byte characters across tokens), so decoding each
    // piece on the Kotlin side produced mojibake. Kotlin reassembles the byte
    // stream and emits only complete characters. An empty (zero-length) array
    // means "token produced no output"; nullptr means generation is finished.
    jbyteArray out = env->NewByteArray((jsize) piece.size());
    if (out != nullptr && !piece.empty()) {
        env->SetByteArrayRegion(out, 0, (jsize) piece.size(),
                                reinterpret_cast<const jbyte *>(piece.data()));
    }
    return out;
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_stopGeneration(JNIEnv *, jobject) {
    g_stop_generation = true;
    g_generating = false;
    LOGi("Generation stop requested");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_ai_abbas_llama_LlamaJni_getThinkingTags(JNIEnv *env, jobject) {
    // {"thinking":bool,"start":"...","ends":["..."]} — used by Kotlin to route the
    // reasoning trace to the thinking block.
    std::string json = "{\"thinking\":";
    json += g_supports_thinking ? "true" : "false";
    json += ",\"start\":\"" + json_escape(g_think_start) + "\",\"ends\":[";
    for (size_t i = 0; i < g_think_ends.size(); i++) {
        if (i > 0) json += ",";
        json += "\"" + json_escape(g_think_ends[i]) + "\"";
    }
    json += "]}";
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_unload(JNIEnv *, jobject) {
    reset_state();
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_chat_templates.reset();
    g_supports_thinking = false;
    g_think_start.clear();
    g_think_ends.clear();
    g_enable_thinking = true;
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    LOGi("Model unloaded");
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_shutdown(JNIEnv *, jobject) {
    reset_state();
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_chat_templates.reset();
    g_supports_thinking = false;
    g_think_start.clear();
    g_think_ends.clear();
    g_enable_thinking = true;
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGi("Shutdown complete");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_ai_abbas_llama_LlamaJni_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}