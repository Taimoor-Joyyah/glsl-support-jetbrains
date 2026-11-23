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
