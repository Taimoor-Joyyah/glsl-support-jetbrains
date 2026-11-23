#pragma once

namespace glsl_support_trace {
struct Listener {
    virtual void event(const char *file, int line, unsigned depth, const char *kind,
                       const char *name, const char *value) = 0;
};
inline thread_local Listener *listener = nullptr;
}

// Called from the pinned glslang preprocessor. Negative string numbers are compiler preambles.
#define GLSL_TRACE(loc, kind, name, value)                                                        \
    do {                                                                                          \
        if (glsl_support_trace::listener && (loc).string >= 0)                                    \
            glsl_support_trace::listener->event((loc).getFilename() ? (loc).getFilename()          \
                                                                     : currentSourceFile.c_str(), \
                (loc).line, static_cast<unsigned>(includeStack.size()), kind, name, value);        \
    } while (false)
