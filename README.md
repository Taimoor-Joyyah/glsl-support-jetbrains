# GLSL Support

GLSL Support adds OpenGL and Vulkan shader editing to JetBrains IDEs on Windows x64, starting with platform build 261 (2026.1). The compiler runs inside the bundled C++23 `glsl_support.dll`, which uses the Microsoft Visual C++ x64 runtime. The plugin works offline after installation.

## Install and use

In **Settings → Plugins**, open the gear menu, choose **Install Plugin from Disk**, select `build/distributions/GLSL-Support-1.0.0.zip`, and restart the IDE. The plugin ID is `com.glslsupport.language`. The descriptor declares a conflict with the previous GLSL Analyzer plugin, and project settings import compatible options and variants on first use.

- **Settings → Languages & Frameworks → GLSL Support** controls default and path-specific targets, include directories, stage macros, editor features, and named pipelines. OpenGL source validation supports desktop and ES shaders. Vulkan targets range from 1.0 to 1.4. The **Shader Variants** tool window configures stage, target, virtual version, and ordered definitions for the selected file. Saving a shader checks its configured variants.
- Use the standard IDE actions for completion, quick documentation, parameter info, navigation, call hierarchy, Find Usages, Rename, formatting, structure, folding, inspections, and selection expansion. Shader symbols are indexed across the project. On build 262 and newer, named variants and pipelines are also available in symbol search.
- **New → GLSL Shader or Header** creates graphics, compute, mesh, task, and ray-tracing stage files. Header guards incorporate the project-relative path. GLSL snippets are available under **Editor → Live Templates**. Shader colors can be adjusted under **Editor → Color Scheme → GLSL**.
- **Inline GLSL Local Variable** handles single constant-initialized locals. **Extract GLSL Function** handles typed values, constructors, and supported complete statements. Both show a diff before applying an undoable edit. Macro-dependent or ambiguous edits and selections with unsupported control flow report the reason.
- **Show GLSL Block Layout** opens a byte diagram and member table. Select a struct to preview its layout or inspect a uniform, storage, or push-constant block. The table shows padding, offsets, array and matrix strides, matrix order, and runtime-array prefixes. OpenGL shared and packed layouts are identified as driver-dependent.
- **Show SPIR-V** compiles the selected variant, validates the result, and opens the disassembly in **GLSL Inspector**. Source lines and instructions are linked wherever debug information supplies a location. The compiler reports which SPIR-V requirements a source shader fails.
- **OpenGL Docs** searches 901 names across 499 published Khronos pages and 45 GLSL Support pages. Khronos publishes pages for both indirect-count draw commands and polygon offset clamp. The supplements cover the other OpenGL 4.6 and GLSL 4.60 additions, including shader specialization. MathJax, its deferred scripts, glyphs, stylesheets, cross-references and linked specifications ship with the plugin. **Open in Browser** opens a local copy. [Khronos OpenGL 4.x reference pages](https://registry.khronos.org/OpenGL-Refpages/gl4/html/) · [GLSL 4.60 specification](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html)

## Build and test

Use Windows x64, PowerShell 7, a JDK 25 with `javac` and `jar` on PATH, a Visual Studio C++ toolset with Ninja, CMake 3.28 or later, and Python 3. Set `JAVA_HOME` to the JDK and `GLSL_IDE_HOME` to a 2026.1 IDE installation when it is outside the default user installation directory. The plugin targets Java 21 bytecode.

```powershell
.\gradlew.bat buildPlugin
.\gradlew.bat checkAll --continue
```

The distribution ZIP includes the DLL, Java classes, language data, templates, documentation, and third-party notices. The build pins glslang, SPIRV-Tools, and SPIRV-Headers by revision and archive checksum. Release ZIPs exclude test plugins, native build intermediates, IDE profiles, and activation state.

| Gradle task | What it exercises |
| --- | --- |
| `checkNative` | CTest against the C ABI, compiler, reflection, SPIR-V, linking, concurrency, and ownership |
| `checkLanguage` | Standalone JUnit editing, former analyzer fixtures, macros, navigation, refactoring, formatting, and offline reference integrity |
| `checkBridge` | JUnit with the real DLL, diagnostics, source mappings, compiler cache, and SPIR-V for all 14 stage templates |
| `checkIde` | Extracts the ZIP into separate IDE profiles and runs indexed project workflows inside each product |
| `checkOfflineReference` | Opens the extracted ZIP in Chromium with networking disabled and verifies the rendered equations, navigation and deferred scripts |
| `verifyPlugin` | Runs JetBrains Plugin Verifier against the supported installed test hosts |
| `benchmarks` | Measures warm member completion, source indexing, recompilation after a header edit, and process memory |

`GLSL_IDEA_263` can point to an existing IntelliJ IDEA 2026.3 installation. Otherwise the test harness downloads the official Windows distribution and checks its SHA-256. The matrix also discovers local CLion and PyCharm installations and accepts additional paths through `GLSL_TEST_IDES`. A paid product must have an active license available to its test profile. For CLion, run `scripts/open-clion-test-profile.ps1`, activate through JetBrains, and close the window before starting the matrix.

Reports are written under `build/reports`, `build/verification`, and `build/ide-tests`. The benchmark uses 256 shaders and one header for source indexing, then checks 64 independent compilation requests after one header edit. Timings depend on the machine.

The offline browser test uses Node.js 22 or newer and an installed Chrome or Edge. Set `GLSL_TEST_BROWSER` to select another Chromium executable. To update the reference library, run `python scripts/sync-reference-pages.py --refresh`. Use `--offline` to reproduce it from the previous download cache. The source and packaged page hashes are in `test-data/reference/khronos-snapshot.tsv`. The deferred assets and specifications are pinned in `test-data/reference/offline-assets.tsv`. Version Support and Copyright sections are omitted from the XHTML. Each published page links to the collected original notices in its edition notes.

## Source

| Directory | Responsibility |
| --- | --- |
| `native` | C ABI, JNI serialization, glslang compilation and layout reflection, SPIRV-Tools validation and disassembly |
| `language-core` | Source-preserving editor syntax, conditional visibility, symbol resolution, edits, completion, and catalog |
| `native-bridge` | Immutable compiler snapshots, diagnostic positions, instruction mappings, and bounded result caching |
| `intellij-plugin` | PSI, indexes, editor integrations, settings, tool windows, bundled resources, and installed-plugin tests |
| `test-data`, `benchmarks` | Reusable shader fixtures, registry acceptance data, and deterministic performance workloads |
