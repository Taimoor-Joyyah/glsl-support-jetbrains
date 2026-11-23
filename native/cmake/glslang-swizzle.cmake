# glslang 16.6.0 appends only recognized components to selector. Check every
# input position. The first unknown character is diagnosed before selector[i] is read.
set(source "${SOURCE_DIR}/glslang/MachineIndependent/ParseContextBase.cpp")
file(READ "${source}" contents)
set(before "// Additional error checking.\n    for (int i = 0; i < selector.size(); ++i) {\n        if (fieldSet[i] == ebadswizzle)")
set(after  "// Additional error checking.\n    for (int i = 0; i < size; ++i) {\n        if (fieldSet[i] == ebadswizzle)")
string(FIND "${contents}" "${after}" already_fixed)
if(already_fixed EQUAL -1)
    string(REPLACE "${before}" "${after}" patched "${contents}")
    if(patched STREQUAL contents)
        message(FATAL_ERROR "Review the swizzle fix against this glslang version")
    endif()
    file(WRITE "${source}" "${patched}")
endif()
