#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>

#include "logging.h"
#include "chat.h"
#include "common.h"
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

// ── Helpers ──────────────────────────────────────────────────────────────

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

static std::string chat_format_and_add(const std::string &role, const std::string &content) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    std::string formatted = common_chat_format_single(
        g_chat_templates.get(), g_chat_msgs, msg, role == "user", false);
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

    // Default sampler
    common_params_sampling sparams;
    sparams.temp = 0.7f;
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
    jint jMaxTokens) {

    if (!g_context) { LOGe("No context"); return 1; }

    // Parse messages JSON: [{"role":"user","content":"hello"}, ...]
    const char *json_str = env->GetStringUTFChars(jMessagesJson, nullptr);
    LOGd("submitMessages: %s", json_str);

    int max_tokens = jMaxTokens > 0 ? jMaxTokens : 512;

    // Simple JSON parser for our message format
    // Expected: {"messages":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]}
    // OR: [{"role":"user","content":"..."},...]
    std::string json(json_str);
    env->ReleaseStringUTFChars(jMessagesJson, json_str);

    // Clear KV cache and chat history for fresh submission
    reset_state();
    llama_memory_clear(llama_get_memory(g_context), false);
    g_current_pos = 0;

    // Parse manually (simple approach - extract role/content pairs)
    std::vector<std::pair<std::string, std::string>> msgs;
    size_t pos = 0;

    // Find "messages" array or bare array
    size_t arr_start = json.find("\"messages\"");
    if (arr_start != std::string::npos) {
        arr_start = json.find('[', arr_start);
    } else {
        arr_start = json.find('[');
    }
    if (arr_start == std::string::npos) { LOGe("No messages array found"); return 2; }

    size_t arr_end = json.find(']', arr_start);
    if (arr_end == std::string::npos) arr_end = json.length();

    std::string arr = json.substr(arr_start + 1, arr_end - arr_start - 1);

    // Parse each object: {"role":"system","content":"..."}
    size_t obj_start = 0;
    while ((obj_start = arr.find('{', obj_start)) != std::string::npos) {
        size_t obj_end = arr.find('}', obj_start);
        if (obj_end == std::string::npos) break;
        std::string obj = arr.substr(obj_start, obj_end - obj_start + 1);
        obj_start = obj_end + 1;

        // Extract role
        std::string role, content;
        size_t rp = obj.find("\"role\"");
        if (rp != std::string::npos) {
            rp = obj.find('"', rp + 7);
            if (rp != std::string::npos) {
                size_t re = obj.find('"', rp + 1);
                if (re != std::string::npos) role = obj.substr(rp + 1, re - rp - 1);
            }
        }
        // Extract content
        size_t cp = obj.find("\"content\"");
        if (cp != std::string::npos) {
            cp = obj.find('"', cp + 10);
            if (cp != std::string::npos) {
                size_t ce = obj.find('"', cp + 1);
                // Handle escaped quotes
                while (ce != std::string::npos && obj[ce - 1] == '\\') {
                    ce = obj.find('"', ce + 1);
                }
                if (ce != std::string::npos) content = obj.substr(cp + 1, ce - cp - 1);
            }
        }

        if (!role.empty() && !content.empty()) {
            msgs.push_back({role, content});
        }
    }

    LOGi("Parsed %zu messages from JSON", msgs.size());

    // Process all messages to build chat history + KV cache
    for (auto &[role, content] : msgs) {
        // Unescape basic JSON escapes in content
        for (size_t i = 0; i + 1 < content.size(); i++) {
            if (content[i] == '\\') {
                char next = content[i + 1];
                if (next == 'n') { content.replace(i, 2, "\n"); }
                else if (next == 't') { content.replace(i, 2, "\t"); }
                else if (next == '"') { content.replace(i, 2, "\""); }
                else if (next == '\\') { content.replace(i, 2, "\\"); }
            }
        }

        std::string formatted = chat_format_and_add(role, content);

        bool add_assistant_prefix = (role == "assistant");
        auto tokens = common_tokenize(g_context, formatted,
            g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get()),
            add_assistant_prefix);

        if (g_current_pos + (int)tokens.size() >= llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {
            LOGw("Context overflow, stopping at %zu messages", g_chat_msgs.size());
            g_chat_msgs.pop_back();
            break;
        }

        // Decode tokens (except last assistant message - that's what we generate from)
        bool is_last = (&role == &msgs.back().first);
        bool compute_logit = is_last && role == "user";

        decode_tokens_batch(g_context, g_batch, tokens, g_current_pos, compute_logit);
        g_current_pos += (int)tokens.size();

        // If last message is user, we want to start generating from here
        if (compute_logit) {
            // Add assistant prefix token if template needs it
            common_chat_msg asst_msg;
            asst_msg.role = "assistant";
            asst_msg.content = "";
            std::string asst_prefix = common_chat_format_single(
                g_chat_templates.get(), g_chat_msgs, asst_msg, false, false);
            g_chat_msgs.push_back(asst_msg);

            if (!asst_prefix.empty()) {
                auto prefix_tokens = common_tokenize(g_context, asst_prefix, false, false);
                decode_tokens_batch(g_context, g_batch, prefix_tokens, g_current_pos, false);
                g_current_pos += (int)prefix_tokens.size();
            }
        }
    }

    g_stop_pos = g_current_pos + max_tokens;
    g_stop_generation = false;
    g_generating = true;
    LOGi("Messages submitted. pos=%d, stop_pos=%d", g_current_pos, g_stop_pos);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
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

    // Decode token
    std::string piece = common_token_to_piece(g_context, id);

    // Decode the token
    common_batch_clear(g_batch);
    common_batch_add(g_batch, id, g_current_pos, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("llama_decode failed during generation");
        g_generating = false;
        return nullptr;
    }
    g_current_pos++;

    return env->NewStringUTF(piece.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_stopGeneration(JNIEnv *, jobject) {
    g_stop_generation = true;
    g_generating = false;
    LOGi("Generation stop requested");
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_abbas_llama_LlamaJni_unload(JNIEnv *, jobject) {
    reset_state();
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_chat_templates.reset();
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