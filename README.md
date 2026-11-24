# GLSL Support (OpenGL/Vulkan)

Edit OpenGL and Vulkan shaders in JetBrains IDEs on **Windows x64, 2026.1 or later**. The plugin runs glslang and SPIRV-Tools locally. Shader editing, compilation, navigation and refactoring work without an LSP server or LSP4IJ.

## Installation

Download the Windows x64 ZIP, then choose **Settings → Plugins → ⚙ → Install Plugin from Disk** and restart the IDE. The Windows compiler and its C++ runtime are included. Existing projects using GLSL Analyzer can import compatible settings and variants when first opened. [Download version 1.3.1](dist/GLSL-Support-OpenGL-Vulkan-1.3.1-windows-x86_64.zip?raw=true) · [SHA-256 checksums](dist/SHA256SUMS)

## Working with shaders

| Task | Where to find it |
| --- | --- |
| Find a declaration, include, or usage | Use Go to Declaration, Find Usages, Call Hierarchy and Search Everywhere. Rename updates resolved project usages, including closed files. |
| Edit and format | Complete GLSL built-ins, layout qualifiers and struct fields. Set indentation and spacing under **Editor → Code Style → GLSL** and token colors under **Editor → Color Scheme → GLSL**. |
| Validate unsaved code | glslang reports errors and warnings for the selected stage and target, including invalid vector swizzles. The default delay is six seconds after the last edit. Choose immediate validation or a five- to ten-second delay in GLSL Support settings. |
| Configure compilation | **Languages & Frameworks → GLSL Support** manages target overrides, includes and ordered macros. **Shader Variants** selects the active stage, profile and definitions. New variants default to the core profile. |
| Choose an editing context | Click the GLSL status-bar selector. **Auto** uses a unique matching pipeline stage for a shader or shared header. Choose a stage explicitly when a file belongs to several pipelines. |
| Link shader stages | Run **Validate** or **Validate All** in **GLSL Pipelines**. Expand an error in **GLSL Inspector** and open a source location with Enter or a double-click. The original compiler log is available below the results. |
| Trace compilation | **Show Include and Macro Trace** lists resolved includes, macro origins and conditional branches. Choose a second variant to see its differences alongside the first. |
| Inspect and export | **Show GLSL Block Layout** displays offsets, padding and strides. **Show SPIR-V** opens validated disassembly with source navigation. Use **Export SPIR-V…** to save the displayed OpenGL or Vulkan module. |
| Create and refactor | **New → GLSL Shader or Header** includes stage templates and guarded headers. GLSL Live Templates, folding and Structure help with editing. **Inline GLSL Local Variable** and **Extract GLSL Function** preview supported edits before applying them. |

The project settings guide includes a tested XML example for sharing targets, includes, variants and pipelines with your team. [Project configuration](docs/project-settings.md)

## OpenGL Docs

Use **Tools → Search OpenGL Docs** and enter a symbol or part of its name, such as `BindTexture`. Press **Enter** or select a result. The **Index** button shows or hides the sidebar. With **Follow editor** enabled, a recognized identifier in a shader, C++ file, Python file or another editor opens its page. Manual search pins the result until you turn following back on.

The searchable index covers **901 names**. The plugin includes 45 supplemental pages for OpenGL 4.6 and GLSL 4.60. For the 499 Khronos pages, it opens a saved copy if available. On first use it downloads the original XHTML directly from the official Khronos GitHub repository at a pinned revision. It verifies the source, removes the Version Support and Copyright sections, adapts the links and math, and verifies the formatted copy before saving it to disk. The original notices remain available in the edition notes. [Khronos source directory](https://github.com/KhronosGroup/OpenGL-Refpages/tree/main/gl4/html)

Visited pages reopen without contacting a server. The stylesheet, MathJax renderer and deferred SVG glyphs are included with the plugin. **Download all pages** also saves unvisited pages and both specifications. Interrupted downloads retain verified files. **Open in Browser** uses the same cache while the IDE is running. The viewer follows your active editor color scheme.

## Building and testing

Use Windows x64, PowerShell 7, JDK 25, Python 3.11 or newer, CMake 3.28 or later, and a Visual Studio C++ installation with Ninja. Set `JAVA_HOME` to the JDK and `GLSL_IDE_HOME` to an IntelliJ Platform 2026.1 installation. The plugin targets Java 21 bytecode.

```powershell
.\gradlew.bat buildPlugin
.\gradlew.bat checkAll --continue
```

The installable ZIP is written to `build/distributions/`. The full suite exercises C++ compiler behavior, Java editing and JNI, installed IDE workflows, Khronos source and cache integrity, an offline Chromium render of every page and JetBrains Plugin Verifier. Node.js 22 or newer and Chrome or Edge are required for the browser checks. [Test and release instructions](docs/releasing.md)

## License, development and support

The original plugin code is released under the MIT License. The compiler, SPIR-V tools, shader catalog, reference texts, fonts and MathJax retain their respective licenses. [MIT License](LICENSE) · [Third-party notices](licenses/NOTICE.txt)

**AI disclosure.** OpenAI Codex has been used during implementation, testing and documentation. The installed plugin analyzes shader source locally. Opening an uncached reference page contacts Khronos’s official GitHub repository. Specification downloads come from the Khronos Registry. Shader source and project configuration stay on your machine.

Report bugs and request features through GitHub Issues. For a reproducible shader problem, include the IDE build, plugin version, shader stage, target and a minimal example you can share. [Issue tracker](https://github.com/Taimoor-Joyyah/glsl-support-jetbrains/issues)
