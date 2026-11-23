#include "SPIRV/GlslangToSpv.h"
#include "glsl_support.h"
#include "preprocessor_trace.h"
#include "glslang/MachineIndependent/localintermediate.h"
#include "glslang/Public/ResourceLimits.h"
#include "glslang/Public/ShaderLang.h"
#include <algorithm>
#include <cctype>
#include <filesystem>
#include <memory>
#include <spirv-tools/libspirv.hpp>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

struct TraceEntry {
    std::string file, kind, name, value;
    int line;
    unsigned depth;
};
struct glsl_result : glsl_support_trace::Listener {
    bool success{};
    std::string log, assembly, layout, linkLog;
    std::vector<std::string> stageLogs;
    std::vector<TraceEntry> trace;
    std::vector<uint32_t> words;
    void event(const char *file, int line, unsigned depth, const char *kind,
               const char *name, const char *value) override {
        trace.push_back({file, kind, name, value, line, depth});
    }
};

namespace {
struct TraceScope {
    glsl_support_trace::Listener *previous = glsl_support_trace::listener;
    explicit TraceScope(glsl_support_trace::Listener *current) { glsl_support_trace::listener = current; }
    ~TraceScope() { glsl_support_trace::listener = previous; }
};
struct CompilerProcess {
    CompilerProcess() {
        if (!glslang::InitializeProcess())
            throw std::runtime_error("Could not initialize glslang");
    }
    ~CompilerProcess() { glslang::FinalizeProcess(); }
};

std::string clean(const std::filesystem::path &path) {
    auto text = path.lexically_normal().generic_u8string();
    return {text.begin(), text.end()};
}

class SnapshotIncludes final : public glslang::TShader::Includer {
    std::unordered_map<std::string, const char *> files;
    std::vector<std::filesystem::path> roots;
    IncludeResult *lookup(const std::filesystem::path &name) {
        auto found = files.find(clean(name));
        if (found == files.end())
            return nullptr;
        return new IncludeResult(found->first, found->second, std::char_traits<char>::length(found->second),
                                 nullptr);
    }

  public:
    explicit SnapshotIncludes(const glsl_request &request) {
        for (size_t i = 0; i < request.header_count; ++i) {
            auto &h = request.headers[i];
            if (!h.path || !h.text)
                throw std::invalid_argument("Invalid header snapshot");
            files.emplace(clean(std::filesystem::u8path(h.path)), h.text);
        }
        for (size_t i = 0; i < request.root_count; ++i) {
            if (!request.include_roots[i])
                throw std::invalid_argument("Invalid include root");
            roots.push_back(std::filesystem::u8path(request.include_roots[i]));
        }
    }
    IncludeResult *includeSystem(const char *name, const char *, size_t depth) override {
        if (depth > 64)
            return nullptr;
        for (auto &root : roots)
            if (auto *hit = lookup(root / std::filesystem::u8path(name)))
                return hit;
        return nullptr;
    }
    IncludeResult *includeLocal(const char *name, const char *from, size_t depth) override {
        if (depth > 64)
            return nullptr;
        return lookup(std::filesystem::u8path(from).parent_path() / std::filesystem::u8path(name));
    }
    void releaseInclude(IncludeResult *r) override { delete r; }
};

std::string sourceText(const glsl_unit &unit) {
    std::string text(unit.source.text);
    if (!unit.version_override)
        return text;
    std::string directive = "#version " + std::to_string(unit.version_override);
    std::string profile = unit.profile_override ? unit.profile_override : "";
    if (!profile.empty() && profile != "none")
        directive += " " + profile;
    size_t pos = 0;
    while (pos < text.size()) {
        if (std::isspace(static_cast<unsigned char>(text[pos]))) {
            ++pos;
            continue;
        }
        if (text.compare(pos, 2, "//") == 0) {
            pos = text.find('\n', pos);
            if (pos == text.npos)
                break;
            continue;
        }
        if (text.compare(pos, 2, "/*") == 0) {
            auto end = text.find("*/", pos + 2);
            if (end == text.npos)
                break;
            pos = end + 2;
            continue;
        }
        break;
    }
    if (pos < text.size() && text[pos] == '#') {
        size_t word = text.find_first_not_of(" \t", pos + 1);
        if (word != text.npos && text.compare(word, 7, "version") == 0) {
            size_t end = text.find_first_of("\r\n", word + 7);
            text.replace(pos, end == text.npos ? text.size() - pos : end - pos, directive);
            return text;
        }
    }
    return directive + "\n#line 1\n" + text;
}

int members(std::ostringstream &out, const glslang::TType &type, const std::string &block,
            const std::string &prefix, int base, glslang::TLayoutPacking packing, bool rowMajor) {
    int cursor = 0;
    for (auto &field : *type.getStruct()) {
        auto &t = *field.type;
        const auto &q = t.getQualifier();
        bool row = q.hasMatrix() ? q.layoutMatrix == glslang::ElmRowMajor : rowMajor;
        int size = 0, stride = 0;
        int align = glslang::TIntermediate::getMemberAlignment(t, size, stride, packing, row);
        if (q.hasAlign())
            align = std::max(align, q.layoutAlign);
        int offset = q.hasOffset() ? q.layoutOffset : ((cursor + align - 1) / align) * align;
        std::string path = prefix + t.getFieldName().c_str();
        bool runtime = t.isUnsizedArray();
        int matrixStride = 0;
        if (t.isMatrix()) {
            if (!t.isArray())
                matrixStride = stride;
            else {
                glslang::TType element(t, 0);
                element.clearArraySizes();
                int ignored = 0;
                glslang::TIntermediate::getMemberAlignment(element, ignored, matrixStride, packing, row);
            }
        }
        out << "member\t" << block << '\t' << path << '\t' << base + offset << '\t' << (runtime ? -1 : size)
            << '\t' << align << '\t' << (t.isArray() ? stride : 0) << '\t' << matrixStride << '\t'
            << (t.isMatrix() ? (row ? "row" : "column") : "-") << '\t' << offset - cursor << '\n';
        if (t.isStruct()) {
            if (t.isArray())
                for (int dimension = 0; dimension < t.getArraySizes()->getNumDims(); ++dimension)
                    path += "[0]";
            members(out, t, block, path + ".", base + offset, packing, row);
        }
        cursor = offset + (runtime ? 0 : size);
    }
    return cursor;
}

std::string reflect(const glslang::TIntermediate &ir) {
    std::ostringstream out;
    auto *objects = ir.findLinkerObjects();
    if (!objects)
        return {};
    for (auto *node : objects->getSequence()) {
        auto *symbol = node->getAsSymbolNode();
        if (!symbol)
            continue;
        auto &t = symbol->getType();
        const auto &q = t.getQualifier();
        if (t.getBasicType() != glslang::EbtBlock ||
            (q.storage != glslang::EvqUniform && q.storage != glslang::EvqBuffer))
            continue;
        std::string name = t.getTypeName().c_str();
        auto packing = q.layoutPacking;
        if (packing != glslang::ElpStd140 && packing != glslang::ElpStd430 && packing != glslang::ElpScalar) {
            out << "block\t" << name << "\tdriver-dependent\t-1\t-1\n";
            continue;
        }
        const bool row = q.layoutMatrix == glslang::ElmRowMajor;
        int size = 0, stride = 0;
        glslang::TType blockType;
        blockType.shallowCopy(t);
        blockType.clearArraySizes();
        int align = glslang::TIntermediate::getMemberAlignment(blockType, size, stride, packing, row);
        auto &fields = *t.getStruct();
        bool runtime = !fields.empty() && fields.back().type->isUnsizedArray();
        std::ostringstream memberRows;
        int end = members(memberRows, blockType, name, "", 0, packing, row);
        size = runtime ? end : ((end + align - 1) / align) * align;
        out << "block\t" << name << '\t' << glslang::TQualifier::getLayoutPackingString(packing) << '\t'
            << size << '\t' << align << '\n';
        out << memberRows.str();
    }
    return out.str();
}

void compile(const glsl_request &r, glsl_result &out) {
    if (!r.units || r.unit_count == 0 || r.unit_count > EShLangCount || (r.header_count && !r.headers) ||
        (r.root_count && !r.include_roots) || (r.vulkan && (r.vulkan < 10 || r.vulkan > 14)) ||
        r.operation < GLSL_CHECK || r.operation > GLSL_TRACE ||
        (r.operation != GLSL_CHECK && r.operation != GLSL_LINK && r.unit_count != 1))
        throw std::invalid_argument("Invalid compiler request");
    static CompilerProcess process;
    SnapshotIncludes includes(r);
    std::vector<std::unique_ptr<glslang::TShader>> shaders;
    glslang::TProgram program;
    bool spirv = r.operation == GLSL_SPIRV;
    EShMessages messages = EShMsgDisplayErrorColumn;
    if (r.vulkan)
        messages = EShMessages(messages | EShMsgVulkanRules | EShMsgSpvRules);
    else if (spirv)
        messages = EShMessages(messages | EShMsgSpvRules);
    const glslang::EShTargetLanguageVersion spvVersions[] = {
        glslang::EShTargetSpv_1_0, glslang::EShTargetSpv_1_3, glslang::EShTargetSpv_1_5,
        glslang::EShTargetSpv_1_6, glslang::EShTargetSpv_1_6};
    bool parsed = true;
    for (size_t i = 0; i < r.unit_count; ++i) {
        const glsl_unit &u = r.units[i];
        if (!u.source.path || !u.source.text || u.stage < 0 || u.stage > GLSL_MESH ||
            (u.define_count && !u.defines))
            throw std::invalid_argument("Invalid shader unit");
        auto stage = static_cast<EShLanguage>(u.stage);
        auto &shader = *shaders.emplace_back(std::make_unique<glslang::TShader>(stage));
        std::string source = sourceText(u);
        const char *text = source.c_str();
        const char *name = u.source.path;
        shader.setStringsWithLengthsAndNames(&text, nullptr, &name, 1);
        std::string preamble = "#extension GL_GOOGLE_include_directive : enable\n";
        for (size_t j = 0; j < u.define_count; ++j) {
            if (!u.defines[j].name || !u.defines[j].value)
                throw std::invalid_argument("Invalid macro");
            preamble += "#define " + std::string(u.defines[j].name) + " " + u.defines[j].value + "\n";
        }
        shader.setPreamble(preamble.c_str());
        if (r.vulkan || spirv) {
            auto client = r.vulkan ? glslang::EShClientVulkan : glslang::EShClientOpenGL;
            shader.setEnvInput(glslang::EShSourceGlsl, stage, client, r.vulkan ? 100 : 450);
            shader.setEnvClient(client, r.vulkan ? static_cast<glslang::EShTargetClientVersion>(
                                                       glslang::EShTargetVulkan_1_0 + ((r.vulkan - 10) << 12))
                                                 : glslang::EShTargetOpenGL_450);
            shader.setEnvTarget(glslang::EShTargetSpv,
                                r.vulkan ? spvVersions[r.vulkan - 10] : glslang::EShTargetSpv_1_0);
        }
        shader.setDebugInfo(spirv);
        TraceScope trace(r.operation == GLSL_TRACE ? &out : nullptr);
        bool ok = shader.parse(GetDefaultResources(), 110, false, messages, includes);
        out.log += shader.getInfoLog();
        if (r.operation == GLSL_LINK)
            out.stageLogs.emplace_back(shader.getInfoLog());
        if (ok) {
            program.addShader(&shader);
            if (r.operation == GLSL_LAYOUT)
                out.layout = reflect(*shader.getIntermediate());
        }
        parsed = parsed && ok;
    }
    if (!parsed)
        return;
    if (r.operation == GLSL_LINK || r.unit_count > 1 || spirv) {
        bool ok = program.link(messages);
        out.log += program.getInfoLog();
        if (r.operation == GLSL_LINK)
            out.linkLog = program.getInfoLog();
        if (!ok)
            return;
    }
    if (spirv) {
        glslang::SpvOptions options;
        options.generateDebugInfo = true;
        options.disableOptimizer = true;
        glslang::GlslangToSpv(*program.getIntermediate(static_cast<EShLanguage>(r.units[0].stage)), out.words,
                              &options);
        constexpr spv_target_env environments[] = {SPV_ENV_VULKAN_1_0, SPV_ENV_VULKAN_1_1, SPV_ENV_VULKAN_1_2,
                                                   SPV_ENV_VULKAN_1_3, SPV_ENV_VULKAN_1_4};
        spvtools::SpirvTools tools(r.vulkan ? environments[r.vulkan - 10] : SPV_ENV_OPENGL_4_5);
        tools.SetMessageConsumer([&](spv_message_level_t, const char *, const spv_position_t &,
                                     const char *msg) { out.log += std::string(msg) + "\n"; });
        spvtools::ValidatorOptions validator;
        if (r.vulkan) {
            validator.SetScalarBlockLayout(true);
            validator.SetUniformBufferStandardLayout(true);
        }
        if (!tools.Validate(out.words.data(), out.words.size(), validator) ||
            !tools.Disassemble(out.words, &out.assembly,
                               SPV_BINARY_TO_TEXT_OPTION_FRIENDLY_NAMES | SPV_BINARY_TO_TEXT_OPTION_INDENT))
            return;
    }
    out.success = true;
}
} // namespace
extern "C" {
unsigned glsl_abi_version() { return 2; }
glsl_result *glsl_compile(const glsl_request *r) {
    auto out = std::unique_ptr<glsl_result>(new (std::nothrow) glsl_result);
    if (!out)
        return nullptr;
    try {
        if (!r)
            throw std::invalid_argument("Missing compiler request");
        compile(*r, *out);
    } catch (const std::exception &e) {
        try {
            out->log = e.what();
        } catch (...) {
            return nullptr;
        }
    } catch (...) {
        return nullptr;
    }
    return out.release();
}
int glsl_success(const glsl_result *r) { return r && r->success; }
const char *glsl_log(const glsl_result *r) { return r ? r->log.c_str() : "Compiler unavailable"; }
const char *glsl_assembly(const glsl_result *r) { return r ? r->assembly.c_str() : ""; }
const char *glsl_layout(const glsl_result *r) { return r ? r->layout.c_str() : ""; }
const uint32_t *glsl_words(const glsl_result *r, size_t *count) {
    if (count)
        *count = r ? r->words.size() : 0;
    return r ? r->words.data() : nullptr;
}
const char *glsl_stage_log(const glsl_result *r, size_t i) {
    return r && i < r->stageLogs.size() ? r->stageLogs[i].c_str() : "";
}
const char *glsl_link_log(const glsl_result *r) { return r ? r->linkLog.c_str() : ""; }
size_t glsl_trace_count(const glsl_result *r) { return r ? r->trace.size() : 0; }
glsl_trace_event glsl_trace_at(const glsl_result *r, size_t i) {
    if (!r || i >= r->trace.size())
        return {};
    const auto &e = r->trace[i];
    return {e.file.c_str(), e.kind.c_str(), e.name.c_str(), e.value.c_str(), e.line, e.depth};
}
void glsl_free(glsl_result *r) { delete r; }
}
