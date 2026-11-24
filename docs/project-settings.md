# Project settings and pipelines

Use **View → Tool Windows → GLSL Pipelines**, or the **Pipelines** tab under GLSL Support settings. Add a named graphics, compute, mesh, ray-tracing, or custom pipeline. Assign shader files to its stages by browsing or typing a project path. Empty stage slots are optional. The variant column accepts a variant name or ID. The definitions table completes macro names from the assigned shaders, their headers, and their variants. Use **Save** in the tool window, **Apply** in Settings, or **Validate** to save and link the selected pipeline. On the Targets tab, choosing a shader suggests an API using the path settings, stage and source. Both OpenGL and Vulkan accept common extensions such as `.vert`. You can override the suggestion.

The project file is `.idea/glslSupport.xml`. Copy the example into that location or merge its `GlslSupport` component into the existing file. Close the project before editing externally so a running IDE cannot overwrite unsaved settings. The example is exercised by the installed-plugin test. [Complete project XML example](../test-data/editor/glslSupport.xml)

| Setting | Accepted values and behavior |
| --- | --- |
| `target`, `targets` | `0` selects OpenGL. `10` through `14` select Vulkan 1.0 through 1.4. Path rules match files or directories. The longest matching path wins. |
| `includes` | Ordered project-relative or absolute directory paths. Quoted includes first search beside the including file. Use forward slashes in shared configuration. |
| `fallback`, `stageMacros` | Use the stage names below. A variant stage takes precedence over a stage macro, the shader filename extension, and the project fallback, in that order. |
| `files` | Keys are project-relative shader paths. `active` is a variant ID. Variants accept `id`, `name`, `stage`, `target`, `version`, `profile`, and a `macros` map. Version zero preserves the source directive. Explicit versions accept `core`, `compatibility`, `es`, or an empty profile. |
| `pipelineDefinitions` | Give each pipeline a unique name. Type is `Graphics`, `Compute`, `Mesh`, `Ray tracing`, or `Custom`. Target `-1` uses stage targets, which must agree when linked. A pipeline target overrides all stage targets. |
| `stages`, `definitions` | Bind a file and optional variant to a stage. An omitted stage uses the file configuration. An omitted variant uses the active variant. Pipeline macros override the same names in stage variants for this link. |
| `validationDelaySeconds` | Use `0` to validate after each edit, or `5` through `10` for a delay. The default is `6`. |
| Editor switches | Boolean options `diagnostics`, `builtins`, `semantic`, `colors`, `inlays`, `signatures`, `format`, `range`, and `onType` default to true. Set `imported` to true for an explicitly authored configuration that has already accounted for legacy settings. |

Stage names are `VERTEX`, `TESS_CONTROL`, `TESS_EVAL`, `GEOMETRY`, `FRAGMENT`, `COMPUTE`, `TASK`, `MESH`, `RAYGEN`, `INTERSECTION`, `ANY_HIT`, `CLOSEST_HIT`, `MISS`, and `CALLABLE`.

## Pipeline contexts in the editor

The GLSL status-bar selector defaults to **Auto**. A shader assigned to one pipeline stage uses that stage's variant, target and definitions. An included header inherits the translation unit that includes it. Auto uses the file's active variant when it finds zero or multiple matching stages. Choose a stage or **File variant** explicitly to pin your preference for that file. The IDE saves these choices in your local project workspace. Deleting a pinned binding restores Auto.

Open **Show Include and Macro Trace** from the shader editor to inspect the compiler's include choices, active macro definitions, removals and conditional results. Double-click a source directive to open it. To compare, choose another saved variant of the same root shader. Both columns use the selected pipeline's overrides. Bold rows show differences. Incomplete shaders show the events the compiler collected and its diagnostics.

**Validate All** links each configured pipeline on demand, including single-stage compute pipelines. A failed pipeline has its own entry in the results tree. Select an entry to read its compiler log, or expand a stage-interface mismatch to navigate to its declarations. **Show SPIR-V → Export SPIR-V…** saves the validated binary for the currently displayed context.

### Pipeline and trace shortcuts in 1.3.1

- Type a pipeline name in the focused pipeline list or the status-bar selector to find it.
- In a trace comparison, select **Differences only** to hide matching entries. Select a directive and press **Enter** to open its source.
- When validation results show **Inputs changed**, click **Rerun**. A single-pipeline run checks that pipeline again. After **Validate All**, it checks every pipeline currently configured.
