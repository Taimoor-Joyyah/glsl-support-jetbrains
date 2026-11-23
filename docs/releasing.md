# Testing and releasing

Use a JDK 25 toolchain with the 2026.1 IntelliJ Platform SDK. See the repository README for build prerequisites.

| Check | Coverage |
| --- | --- |
| `checkNative` | Tests the C ABI, stage compilation and linking, reflection, SPIR-V, ownership and concurrency. |
| `checkLanguage` and `checkBridge` | Run reusable JUnit editor, compiler integration, reference cache and source-validation tests. |
| `checkIde` | Installs the ZIP into separate IntelliJ IDEA, PyCharm and CLion test profiles and exercises project and editor workflows. |
| `checkKhronosReference` | Builds a fresh cache from the 499 verified Khronos originals and compares each formatted file byte for byte. |
| `checkOfflineReference` | Populates the disk cache from the finished plugin, opens it in Chromium with networking disabled and checks every equation, deferred script and cross-reference. |
| `checkDistribution` and `verifyPlugin` | Check the contents of the final ZIP and run JetBrains Plugin Verifier against the installed test hosts. |
| `benchmarks` | Measures editing, workspace indexing, recompilation after a header edit and process memory. |

Run `.\gradlew.bat checkAll --continue` for the release gates. Use `scripts/ide-test.ps1 -IdeHome '<CLion installation>' -DocsWindow` for the additional graphical CLion check. It exercises the Chromium tool window, cross-language selection and live swizzle diagnostics. Test reports are under `build/reports`, `build/verification` and `build/ide-tests`.

`GLSL_IDEA_263` can point to an installed IntelliJ IDEA 2026.3. Otherwise the harness downloads and verifies that distribution. `GLSL_TEST_IDES` accepts additional paths separated by semicolons. To activate a CLion test installation, run `scripts/open-clion-test-profile.ps1`, activate it through JetBrains and close the window before testing.

The offline verifier checks IntelliJ APIs. Its `-ignore-os-arch` option applies to synthetic OS and architecture modules. The release checks read the native DLL’s PE header and each IDE’s Windows x64 launch record, then load the compiler inside those IDEs.

## Preparing the reference edition

The maintained XHTML, styles and rendering assets are under `intellij-plugin/src/main/resources/reference_pages`. The plugin includes the search index, original notices, stylesheet, offline equation renderer and 45 supplemental pages. The checked-in editions of the 499 official pages are test expectations. The installed plugin downloads originals from the Khronos GitHub repository and formats them locally.

1. Update the pinned Khronos revision in the preparation script and `ReferenceLibrary`. Run `python scripts/sync-reference-pages.py --refresh`. Review the page changes and attribution. The registry index supplies symbol aliases.
2. Run `python scripts/reference-pack.py --catalog build/reference-generated` to validate the edition and prepare the resources for the installer. Commit the reviewed fixtures and checksums together.
3. Run the source, cache and offline-render checks before publishing. The SHA-256 catalog identifies the disk-cache edition.

## Publishing a release

1. Update the descriptor version and change notes. Run the full checks on the release commit and the graphical test on licensed CLion.
2. Copy the verified installer to `dist/` and publish it with the matching source revision. Verify that the ZIP downloaded from GitHub has the same SHA-256 as the tested local artifact.
3. In JetBrains Marketplace, select the ZIP from `build/distributions`, enter the public GitHub source and support links, select the MIT license for original code, and complete the publisher information. JetBrains reviews new listings. [Marketplace upload instructions](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html) · [Approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
