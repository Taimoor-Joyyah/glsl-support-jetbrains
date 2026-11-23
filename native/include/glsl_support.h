#pragma once
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32) && defined(GLSL_SUPPORT_BUILD)
#define GLSL_API __declspec(dllexport)
#elif defined(_WIN32)
#define GLSL_API __declspec(dllimport)
#else
#define GLSL_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

enum glsl_stage {
    GLSL_VERTEX,
    GLSL_TESS_CONTROL,
    GLSL_TESS_EVAL,
    GLSL_GEOMETRY,
    GLSL_FRAGMENT,
    GLSL_COMPUTE,
    GLSL_RAYGEN,
    GLSL_INTERSECTION,
    GLSL_ANY_HIT,
    GLSL_CLOSEST_HIT,
    GLSL_MISS,
    GLSL_CALLABLE,
    GLSL_TASK,
    GLSL_MESH
};
enum glsl_operation { GLSL_CHECK, GLSL_SPIRV, GLSL_LAYOUT, GLSL_LINK, GLSL_TRACE };
/* UTF-8 strings. Source names use / and are unique within a request.
   vulkan is zero for OpenGL or 10..14 for Vulkan 1.0..1.4. */
typedef struct {
    const char *path;
    const char *text;
} glsl_source;
typedef struct {
    const char *name;
    const char *value;
} glsl_define;
typedef struct {
    glsl_source source;
    int stage;
    const glsl_define *defines;
    size_t define_count;
    int version_override;
    const char *profile_override;
} glsl_unit;
typedef struct {
    int vulkan;
    int operation;
    const glsl_unit *units;
    size_t unit_count;
    const glsl_source *headers;
    size_t header_count;
    const char *const *include_roots;
    size_t root_count;
} glsl_request;
typedef struct glsl_result glsl_result;
GLSL_API unsigned glsl_abi_version(void);
/* Independent requests may run concurrently. Result views live until glsl_free. */
GLSL_API glsl_result *glsl_compile(const glsl_request *request);
GLSL_API int glsl_success(const glsl_result *result);
GLSL_API const char *glsl_log(const glsl_result *result);
GLSL_API const char *glsl_assembly(const glsl_result *result);
GLSL_API const char *glsl_layout(const glsl_result *result);
GLSL_API const uint32_t *glsl_words(const glsl_result *result, size_t *count);
GLSL_API const char *glsl_stage_log(const glsl_result *result, size_t index);
GLSL_API const char *glsl_link_log(const glsl_result *result);
typedef struct {
    const char *file, *kind, *name, *value;
    int line;
    unsigned depth;
} glsl_trace_event;
GLSL_API size_t glsl_trace_count(const glsl_result *result);
GLSL_API glsl_trace_event glsl_trace_at(const glsl_result *result, size_t index);
GLSL_API void glsl_free(glsl_result *result);
#ifdef __cplusplus
}
#endif
