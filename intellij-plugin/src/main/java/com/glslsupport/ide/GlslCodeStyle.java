package com.glslsupport.ide;

import com.glslsupport.lang.Formatting;
import com.intellij.application.options.*;
import com.intellij.lang.Language;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.*;
import java.util.HashSet;
import org.jetbrains.annotations.NotNull;

public final class GlslCodeStyle extends LanguageCodeStyleSettingsProvider {
    static Formatting.Style forFile(CodeStyleSettings settings, PsiFile file) {
        var indent = settings.getIndentOptionsByFile(file);
        var common = settings.getCommonSettings(GlslLanguage.INSTANCE);
        var spaced = new HashSet<String>();
        if (common.SPACE_BEFORE_IF_PARENTHESES) spaced.add("if");
        if (common.SPACE_BEFORE_FOR_PARENTHESES) spaced.add("for");
        if (common.SPACE_BEFORE_WHILE_PARENTHESES) spaced.add("while");
        if (common.SPACE_BEFORE_SWITCH_PARENTHESES) spaced.add("switch");
        return new Formatting.Style(indent.INDENT_SIZE, indent.USE_TAB_CHARACTER,
            common.SPACE_AFTER_COMMA, common.SPACE_AROUND_ASSIGNMENT_OPERATORS, spaced);
    }
    public @NotNull Language getLanguage() {
        return GlslLanguage.INSTANCE;
    }
    public String getConfigurableDisplayName() {
        return "GLSL";
    }
    public IndentOptionsEditor getIndentOptionsEditor() {
        return new IndentOptionsEditor(this);
    }
    public void customizeSettings(@NotNull CodeStyleSettingsCustomizable options, @NotNull SettingsType type) {
        if (type == SettingsType.INDENT_SETTINGS)
            options.showStandardOptions("USE_TAB_CHARACTER", "TAB_SIZE", "INDENT_SIZE");
        if (type == SettingsType.SPACING_SETTINGS)
            options.showStandardOptions("SPACE_AFTER_COMMA", "SPACE_AROUND_ASSIGNMENT_OPERATORS",
                "SPACE_BEFORE_IF_PARENTHESES", "SPACE_BEFORE_FOR_PARENTHESES", "SPACE_BEFORE_WHILE_PARENTHESES",
                "SPACE_BEFORE_SWITCH_PARENTHESES");
    }
    public @NotNull CodeStyleConfigurable createConfigurable(@NotNull CodeStyleSettings settings, @NotNull CodeStyleSettings original) {
        return new CodeStyleAbstractConfigurable(settings, original, "GLSL") {
            protected CodeStyleAbstractPanel createPanel(CodeStyleSettings editable) {
                return new TabbedLanguageCodeStylePanel(GlslLanguage.INSTANCE, getCurrentSettings(), editable) {
                    protected void initTabs(CodeStyleSettings values) {
                        addIndentOptionsTab(values);
                        addSpacesTab(values);
                    }
                };
            }
        };
    }
    public String getCodeSample(@NotNull SettingsType type) {
        return """
            #version 460 core
            layout(location = 0) out vec4 color;
            void main() {
                vec3 rgb = vec3(0.25, 0.5, 1.0);
                for (int i = 0; i < 3; ++i) {
                    if (rgb[i] > 0.0) rgb[i] *= 0.5;
                }
                color = vec4(rgb, 1.0);
            }
            """;
    }
}
