#include "glsl_support.h"
#include <cstring>
#include <deque>
#include <jni.h>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
class Reader {
    const std::vector<unsigned char> &bytes;
    size_t at{};
    std::deque<std::string> strings;

  public:
    explicit Reader(const std::vector<unsigned char> &payload) : bytes(payload) {}
    uint32_t number() {
        if (bytes.size() - at < 4)
            throw std::invalid_argument("Truncated native request");
        uint32_t n = 0;
        for (int i = 0; i < 4; i++)
            n = (n << 8) | bytes[at++];
        return n;
    }
    size_t count(size_t minBytes) {
        auto n = number();
        if (n > (bytes.size() - at) / minBytes)
            throw std::invalid_argument("Invalid collection length");
        return n;
    }
    const char *string() {
        size_t size = count(1);
        auto &s = strings.emplace_back(reinterpret_cast<const char *>(bytes.data() + at), size);
        at += size;
        if (s.find('\0') != s.npos)
            throw std::invalid_argument("Unexpected NUL in shader request");
        return s.c_str();
    }
    bool done() const { return at == bytes.size(); }
};
class Writer {
  public:
    std::vector<jbyte> bytes;
    void number(uint32_t v) {
        for (int shift : {24, 16, 8, 0})
            bytes.push_back(static_cast<jbyte>(v >> shift));
    }
    void string(const char *s) {
        size_t n = std::strlen(s);
        number(static_cast<uint32_t>(n));
        bytes.insert(bytes.end(), s, s + n);
    }
};

jbyteArray run(JNIEnv *env, jbyteArray request) {
    if (!request)
        throw std::invalid_argument("Empty native request");
    auto size = env->GetArrayLength(request);
    std::vector<unsigned char> data(size);
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(data.data()));
    if (env->ExceptionCheck())
        return nullptr;
    Reader in(data);
    glsl_request r{};
    r.vulkan = static_cast<int>(in.number());
    r.operation = static_cast<int>(in.number());
    r.unit_count = in.count(24);
    std::vector<glsl_unit> units(r.unit_count);
    std::vector<std::vector<glsl_define>> definitions(r.unit_count);
    for (size_t i = 0; i < units.size(); i++) {
        auto &u = units[i];
        u.source = {in.string(), in.string()};
        u.stage = static_cast<int>(in.number());
        u.version_override = static_cast<int>(in.number());
        u.profile_override = in.string();
        auto &defs = definitions[i];
        defs.resize(in.count(8));
        for (auto &d : defs)
            d = {in.string(), in.string()};
        u.defines = defs.data();
        u.define_count = defs.size();
    }
    r.units = units.data();
    r.header_count = in.count(8);
    std::vector<glsl_source> headers(r.header_count);
    for (auto &h : headers)
        h = {in.string(), in.string()};
    r.headers = headers.data();
    r.root_count = in.count(4);
    std::vector<const char *> roots(r.root_count);
    for (auto &root : roots)
        root = in.string();
    r.include_roots = roots.data();
    if (!in.done())
        throw std::invalid_argument("Trailing data in compiler request");
    std::unique_ptr<glsl_result, decltype(&glsl_free)> result(glsl_compile(&r), glsl_free);
    if (!result)
        throw std::bad_alloc();
    Writer out;
    out.number(glsl_success(result.get()));
    out.string(glsl_log(result.get()));
    out.string(glsl_assembly(result.get()));
    out.string(glsl_layout(result.get()));
    size_t n = 0;
    auto words = glsl_words(result.get(), &n);
    out.number(static_cast<uint32_t>(n));
    for (size_t i = 0; i < n; ++i)
        out.number(words[i]);
    if (r.operation == GLSL_LINK) {
        for (size_t i = 0; i < r.unit_count; ++i)
            out.string(glsl_stage_log(result.get(), i));
        out.string(glsl_link_log(result.get()));
    }
    if (r.operation == GLSL_TRACE) {
        size_t count = glsl_trace_count(result.get());
        out.number(static_cast<uint32_t>(count));
        for (size_t i = 0; i < count; ++i) {
            auto e = glsl_trace_at(result.get(), i);
            out.string(e.file);
            out.number(e.line);
            out.number(e.depth);
            out.string(e.kind);
            out.string(e.name);
            out.string(e.value);
        }
    }
    if (out.bytes.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("Compiler output is too large");
    auto length = static_cast<jsize>(out.bytes.size());
    jbyteArray array = env->NewByteArray(length);
    if (array)
        env->SetByteArrayRegion(array, 0, length, out.bytes.data());
    return array;
}
} // namespace
extern "C" JNIEXPORT jint JNICALL Java_com_glslsupport_bridge_NativeCompiler_abi(JNIEnv *, jclass) {
    return glsl_abi_version();
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_glslsupport_bridge_NativeCompiler_invoke(JNIEnv *env, jclass,
                                                                                          jbyteArray input) {
    try {
        return run(env, input);
    } catch (const std::exception &e) {
        if (!env->ExceptionCheck())
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what());
        return nullptr;
    }
}
