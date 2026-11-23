#include "glsl_support.h"
#include <array>
#include <future>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>

#include <psapi.h>
#endif
using Result = std::unique_ptr<glsl_result, decltype(&glsl_free)>;
void require(bool ok, const std::string &explanation) {
    if (!ok)
        throw std::runtime_error(explanation);
}
struct Example {
    glsl_unit unit{{"shaders/test.vert", "#version 450\nvoid main(){ gl_Position=vec4(1); }"}, GLSL_VERTEX};
    glsl_request request{0, GLSL_CHECK, &unit, 1};
    Result run(bool valid) {
        Result r(glsl_compile(&request), glsl_free);
        require(r && bool(glsl_success(r.get())) == valid,
                r ? std::string("Unexpected result: ") + glsl_log(r.get()) : "no result");
        return r;
    }
};
void run(const std::string &name) {
    Example x;
    if (name == "profiles") {
        x.unit.source.text = "#version 120\nattribute vec4 position; void main(){gl_Position=position;}";
        x.run(true);
        x.request.vulkan = 13;
        x.run(false);
        x.request.vulkan = 0;
        x.unit.source.text = "#version 100\nattribute vec4 v; void main(){gl_Position=v;}";
        x.run(true);
    } else if (name == "diagnostics") {
        x.unit.source.text = "#version 450\nvoid main(){\n wrongName=0;\n}";
        auto r = x.run(false);
        require(std::string(glsl_log(r.get())).find("wrongName") != std::string::npos, glsl_log(r.get()));
        require(std::string(glsl_log(r.get())).find(":3:") != std::string::npos, glsl_log(r.get()));
    } else if (name == "swizzles") {
        for (int target : {0, 13}) {
            x.request.vulkan = target;
            for (const std::string field : {"xyl", "xly", "lxy", "l", "ll", "xy_", "xy2", "xy", "rg", "st"}) {
                const std::string source =
                    "#version 450\nlayout(location=0) out vec2 uv;\nvoid main(){vec4 currentClip=vec4(1);\n"
                    "uv = currentClip." + field + " / currentClip.w;\ngl_Position=currentClip;}";
                x.unit.source.text = source.c_str();
                const bool valid = field == "xy" || field == "rg" || field == "st";
                auto r = x.run(valid);
                if (!valid) {
                    const std::string log = glsl_log(r.get());
                    require(log.find("'" + field + "' : unknown swizzle selection") != log.npos, log);
                }
            }
            x.unit.source.text = "#version 450\nstruct Clip { vec2 xyl; float w; };\n"
                                 "void main(){ Clip currentClip=Clip(vec2(1),1.);\n"
                                 "gl_Position=vec4(currentClip.xyl / currentClip.w,0,1);}";
            x.run(true);
        }
    } else if (name == "includes") {
        x.unit.source.text = "#version 450\n#include \"lib/base.glslh\"\nvoid main(){gl_Position=f();}";
        glsl_source headers[] = {{"shaders/lib/base.glslh", "#include <value.glsl>\nvec4 f(){return val;}\n"},
                                 {"includes/value.glsl", "const vec4 val=vec4(1);\n"}};
        const char *roots[] = {"includes"};
        x.request.headers = headers;
        x.request.header_count = 2;
        x.request.include_roots = roots;
        x.request.root_count = 1;
        x.run(true);
        headers[1].text = "const vec4 val=bad;\n";
        auto r = x.run(false);
        require(std::string(glsl_log(r.get())).find("includes/value.glsl") != std::string::npos,
                glsl_log(r.get()));
        headers[1].text = "#include <value.glsl>\n";
        x.run(false);
    } else if (name == "variants") {
        x.unit.source.text = "// shader\n#version 120\n#if MODE != 3\n#error wrong "
                             "variant\n#endif\nlayout(location=0) in vec4 p; void main(){gl_Position=p;}";
        glsl_define defs[] = {{"MODE", "3"}};
        x.unit.defines = defs;
        x.unit.define_count = 1;
        x.unit.version_override = 450;
        x.unit.profile_override = "core";
        x.run(true);
        defs[0].value = "2";
        x.run(false);
    } else if (name == "pipeline") {
        x.request.operation = GLSL_LINK;
        x.unit.stage = GLSL_COMPUTE;
        x.unit.source.text = "#version 450\nlayout(local_size_x=1) in; void helper(){}";
        auto missing = x.run(false);
        require(std::string(glsl_link_log(missing.get())).find("entry point") != std::string::npos,
                glsl_log(missing.get()));
        x.unit.stage = GLSL_VERTEX;
        glsl_unit units[] = {
            x.unit,
            {{"shaders/test.frag", "#version 450\nin vec3 c; out vec4 f; void main(){f=vec4(c,1);}"},
             GLSL_FRAGMENT}};
        units[0].source.text = "#version 450\nout vec3 c; void main(){c=vec3(1);gl_Position=vec4(1);}";
        x.request.units = units;
        x.request.unit_count = 2;
        x.run(true);
        units[1].source.text = "#version 450\nin vec2 c; out vec4 f; void main(){f=vec4(c,1,1);}";
        auto bad = x.run(false);
        require(std::string(glsl_link_log(bad.get())).find("fragment") != std::string::npos, glsl_log(bad.get()));
    } else if (name == "trace") {
        x.request.operation = GLSL_TRACE;
        x.unit.source.text = "#version 450\n#define TWICE(x) ((x)*2)\n#if TWICE(2)==4\n"
                             "#include \"head.glslh\"\n#else\n#ifdef NO\n#endif\n#endif\n"
                             "#undef FLAG\n#include \"head.glslh\"\nvoid main(){}";
        glsl_source header{"shaders/head.glslh", "#define FLAG 3\n"};
        x.request.headers = &header;
        x.request.header_count = 1;
        auto result = x.run(true);
        size_t includeCount = 0, defines = 0;
        bool taken = false, skipped = false, undef = false;
        for (size_t i = 0; i < glsl_trace_count(result.get()); ++i) {
            auto e = glsl_trace_at(result.get(), i);
            std::string kind = e.kind, key = e.name, value = e.value;
            if (kind == "include" && value == header.path) ++includeCount;
            if (kind == "define" && key == "FLAG" && e.depth == 1) ++defines;
            if (kind == "undef" && key == "FLAG") undef = true;
            if (kind == "branch" && value == "taken" && e.line == 3) taken = true;
            if (kind == "branch" && key == "ifdef" && value == "skipped (inactive parent)") skipped = true;
        }
        require(includeCount == 2 && defines == 2 && taken && skipped && undef, "native preprocessor trace");
    } else if (name == "spirv") {
        x.request.operation = GLSL_SPIRV;
        for (int target : {0, 10, 11, 12, 13, 14}) {
            x.request.vulkan = target;
            auto r = x.run(true);
            size_t n = 0;
            auto words = glsl_words(r.get(), &n);
            require(n > 5 && words[0] == 0x07230203, "SPIR-V header");
            auto assembly = std::string(glsl_assembly(r.get()));
            require(assembly.find("OpEntryPoint") != std::string::npos &&
                        assembly.find("OpLine") != std::string::npos,
                    assembly);
        }
    } else if (name == "layout") {
        x.unit.source.text = "#version 450\nlayout(std140,binding=0) uniform Scene {float a; vec3 b; float "
                             "c; mat3 m; float values[2];} scene;\nvoid main(){gl_Position=vec4(scene.b,1);}";
        x.request.operation = GLSL_LAYOUT;
        auto r = x.run(true);
        std::string t = glsl_layout(r.get());
        for (auto entry : {"block\tScene\tstd140", "member\tScene\tb\t16\t12\t16", "member\tScene\tc\t28",
                           "member\tScene\tm\t32", "member\tScene\tvalues\t80"})
            require(t.find(entry) != t.npos, t);
        x.unit.source.text = "#version 450\nlayout(std430,binding=0) buffer Buf {vec4 v; float tail[];} buf; "
                             "void main(){gl_Position=buf.v;}";
        r = x.run(true);
        t = glsl_layout(r.get());
        require(t.find("tail\t16\t-1\t4\t4") != t.npos, t);
    } else if (name == "decorations") {
        x.request.vulkan = 12;
        x.unit.source.text =
            "#version 450\nstruct S{vec3 v; float f;};layout(std140,row_major,binding=0)uniform B{S "
            "s;layout(offset=64)mat2x3 m;float a[2];} b;void main(){gl_Position=vec4(b.s.v,b.a[0]);}";
        x.request.operation = GLSL_LAYOUT;
        auto layout = x.run(true);
        std::string t = glsl_layout(layout.get());
        for (auto field : {"s.f\t12", "m\t64\t48\t16\t0\t16\trow", "a\t112\t32\t16\t16"})
            require(t.find(field) != t.npos, t);
        x.request.operation = GLSL_SPIRV;
        auto binary = x.run(true);
        std::string assembly = glsl_assembly(binary.get());
        for (auto decoration : {"Offset 64", "Offset 112", "MatrixStride 16", "ArrayStride 16", "RowMajor"})
            require(assembly.find(decoration) != assembly.npos, assembly);
    } else if (name == "layout_modes") {
        x.request.operation = GLSL_LAYOUT;
        x.unit.source.text =
            "#version 450\nlayout(shared) uniform B{float f;} b;void main(){gl_Position=vec4(b.f);}";
        auto r = x.run(true);
        require(std::string(glsl_layout(r.get())).find("driver-dependent") != std::string::npos,
                glsl_layout(r.get()));
        x.request.vulkan = 12;
        x.unit.source.text = "#version 450\n#extension GL_EXT_scalar_block_layout : "
                             "require\nlayout(scalar,push_constant)uniform P{float n;vec3 v;} p;void "
                             "main(){gl_Position=vec4(p.v,p.n);}";
        r = x.run(true);
        require(std::string(glsl_layout(r.get())).find("v\t4\t12\t4") != std::string::npos,
                glsl_layout(r.get()));
    } else if (name == "multidimensional") {
        x.request.vulkan = 12;
        x.request.operation = GLSL_LAYOUT;
        x.unit.source.text = "#version 450\nstruct S{vec3 f;};layout(std430,binding=0)buffer B{"
                             "mat2 m[2][3];S s[2][3];} b[3];void main(){gl_Position=vec4(b[0].s[0][0].f,1);}";
        auto r = x.run(true);
        std::string layout = glsl_layout(r.get());
        for (auto field : {"block\tB\tstd430\t192\t16", "m\t0\t96\t8\t48\t8\tcolumn", "s[0][0].f\t96"})
            require(layout.find(field) != layout.npos, layout);
        x.request.operation = GLSL_SPIRV;
        r = x.run(true);
        require(std::string(glsl_assembly(r.get())).find("MatrixStride 8") != std::string::npos,
                glsl_assembly(r.get()));
    } else if (name == "profile") {
        for (int i = 0; i < 100; ++i) {
            x.request.operation = i % 2 == 0 ? GLSL_CHECK : GLSL_SPIRV;
            x.run(true);
        }
#ifdef _WIN32
        PROCESS_MEMORY_COUNTERS counters{};
        require(GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof counters),
                "GetProcessMemoryInfo");
        std::cout << counters.PeakWorkingSetSize << '\n';
#endif
    } else if (name == "source_lines") {
        x.unit.source.text = "// header\n#error marker\nvoid main(){}";
        x.unit.version_override = 450;
        auto r = x.run(false);
        require(std::string(glsl_log(r.get())).find(":2:") != std::string::npos, glsl_log(r.get()));
        x.unit.source.text = "#version 450\n#line 85\n#error marker\nvoid main(){}";
        x.unit.version_override = 0;
        r = x.run(false);
        require(std::string(glsl_log(r.get())).find(":85:") != std::string::npos, glsl_log(r.get()));
    } else if (name == "concurrency") {
        std::vector<std::future<void>> jobs;
        for (int i = 0; i < 6; i++)
            jobs.push_back(std::async(std::launch::async, [] {
                for (int j = 0; j < 20; j++) {
                    Example sample;
                    sample.run(true);
                }
            }));
        for (auto &job : jobs)
            job.get();
    } else if (name == "ownership") {
        require(glsl_abi_version() == 2, "ABI");
        glsl_free(nullptr);
        Result bad(glsl_compile(nullptr), glsl_free);
        require(bad && !glsl_success(bad.get()), "invalid request");
        for (int i = 0; i < 100; i++) {
            auto r = x.run(true);
            size_t n = 9;
            glsl_words(r.get(), &n);
            require(n == 0, "validation generated binary");
        }
    } else
        throw std::runtime_error("unknown test");
}
int main(int argc, char **argv) {
    try {
        if (argc != 2)
            return 2;
        run(argv[1]);
        return 0;
    } catch (const std::exception &e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
