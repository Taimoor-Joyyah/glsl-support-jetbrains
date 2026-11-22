package com.glslsupport.ide;
import com.glslsupport.lang.Syntax;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
final class GlslIcons {
    static final Icon FILE = IconLoader.getIcon("/icons/shader.svg", GlslIcons.class);
    static final Icon PIPELINE = IconLoader.getIcon("/icons/pipeline.svg", GlslIcons.class);
    static Icon symbol(Syntax.Kind kind) {
        return switch (kind) {
            case FUNCTION -> AllIcons.Nodes.Function;
            case TYPE -> AllIcons.Nodes.Class;
            case BLOCK -> AllIcons.Nodes.Interface;
            case FIELD -> AllIcons.Nodes.Field;
            case GLOBAL -> AllIcons.Nodes.Property;
            case LOCAL -> AllIcons.Nodes.Variable;
            case PARAMETER -> AllIcons.Nodes.Parameter;
            case MACRO -> AllIcons.Nodes.Constant;
        };
    }
}
