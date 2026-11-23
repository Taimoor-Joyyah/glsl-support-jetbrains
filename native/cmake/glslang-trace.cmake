# Compile an instrumented copy of the pinned preprocessor. Report its own decisions,
# including function-like macro evaluation and directives skipped by an outer branch.
set(pp_dir "${glslang_source_SOURCE_DIR}/glslang/MachineIndependent/preprocessor")
set(pp_source "${pp_dir}/Pp.cpp")
set(pp_generated "${CMAKE_CURRENT_BINARY_DIR}/GlslSupportPp.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${pp_source}")
file(READ "${pp_source}" pp)
function(trace_at before after)
    string(FIND "${pp}" "${before}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "Review the preprocessor trace hooks against the pinned glslang source: ${before}")
    endif()
    string(REPLACE "${before}" "${after}" patched "${pp}")
    set(pp "${patched}" PARENT_SCOPE)
endfunction()
trace_at([[#include "PpContext.h"]] [[#include "PpContext.h"
#include "preprocessor_trace.h"]])
trace_at([[    // check for duplicate definition]] [[    GLSL_TRACE(defineLoc, "define", atomStrings.getString(defAtom), "");
    // check for duplicate definition]])
trace_at([[    parseContext.reservedPpErrorCheck(ppToken->loc, ppToken->name, "#undef");]]
         [[    GLSL_TRACE(ppToken->loc, "undef", ppToken->name, "");
    parseContext.reservedPpErrorCheck(ppToken->loc, ppToken->name, "#undef");]])
trace_at([[int TPpContext::CPPif(TPpToken* ppToken)
{]] [[int TPpContext::CPPif(TPpToken* ppToken)
{
    const auto conditionLoc = ppToken->loc;
    const bool isElif = strcmp(ppToken->name, "elif") == 0;]])
trace_at([[    if (!res && !err)]] [[    GLSL_TRACE(conditionLoc, "branch", isElif ? "elif" : "if", err ? "invalid" : res ? "taken" : "false");
    if (!res && !err)]])
trace_at([[int TPpContext::CPPifdef(int defined, TPpToken* ppToken)
{]] [[int TPpContext::CPPifdef(int defined, TPpToken* ppToken)
{
    const auto conditionLoc = ppToken->loc;]])
trace_at([[        if (((macro != nullptr && !macro->undef) ? 1 : 0) != defined)]]
         [[        GLSL_TRACE(conditionLoc, "branch", defined ? "ifdef" : "ifndef",
                   ((macro != nullptr && !macro->undef) ? 1 : 0) == defined ? "taken" : "false");
        if (((macro != nullptr && !macro->undef) ? 1 : 0) != defined)]])
trace_at([[        int nextAtom = atomStrings.getAtom(ppToken->name);]]
         [[        int nextAtom = atomStrings.getAtom(ppToken->name);
        if (nextAtom == PpAtomIf || nextAtom == PpAtomIfdef || nextAtom == PpAtomIfndef ||
            ((nextAtom == PpAtomElse || nextAtom == PpAtomElif) && (depth != 0 || !matchelse)))
            GLSL_TRACE(ppToken->loc, "branch", ppToken->name, depth != 0 || nextAtom == PpAtomIf ||
                       nextAtom == PpAtomIfdef || nextAtom == PpAtomIfndef ? "skipped (inactive parent)" :
                       "skipped (earlier branch taken)");
        else if (nextAtom == PpAtomElse && depth == 0 && matchelse)
            GLSL_TRACE(ppToken->loc, "branch", "else", "taken");]])
trace_at([[        case PpAtomElse:
            if (elseSeen]] [[        case PpAtomElse:
            GLSL_TRACE(ppToken->loc, "branch", "else", "skipped (earlier branch taken)");
            if (elseSeen]])
trace_at([[        case PpAtomElif:
            if (ifdepth]] [[        case PpAtomElif:
            GLSL_TRACE(ppToken->loc, "branch", "elif", "skipped (earlier branch taken)");
            if (ifdepth]])
trace_at([[    // Process the results]] [[    GLSL_TRACE(directiveLoc, "include", filename.c_str(), res ? res->headerName.c_str() : "");
    // Process the results]])
trace_at([[int TPpContext::CPPversion(TPpToken* ppToken)
{]] [[int TPpContext::CPPversion(TPpToken* ppToken)
{
    const auto versionLoc = ppToken->loc;]])
trace_at([[        parseContext.notifyVersion(line, versionNumber, nullptr);]]
         [[        GLSL_TRACE(versionLoc, "version", std::to_string(versionNumber).c_str(), "");
        parseContext.notifyVersion(line, versionNumber, nullptr);]])
trace_at([[        parseContext.notifyVersion(line, versionNumber, ppToken->name);]]
         [[        GLSL_TRACE(versionLoc, "version", std::to_string(versionNumber).c_str(), ppToken->name);
        parseContext.notifyVersion(line, versionNumber, ppToken->name);]])

file(CONFIGURE OUTPUT "${pp_generated}" CONTENT "${pp}" @ONLY)
set_source_files_properties("${pp_source}" TARGET_DIRECTORY glslang PROPERTIES HEADER_FILE_ONLY TRUE)
set_source_files_properties("${pp_generated}" TARGET_DIRECTORY glslang PROPERTIES
    INCLUDE_DIRECTORIES "${pp_dir};${CMAKE_CURRENT_SOURCE_DIR}/src")
target_sources(glslang PRIVATE "${pp_generated}")
