package com.glslsupport.ide;

import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.*;

import com.glslsupport.lang.*;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.tree.IElementType;
import java.util.*;

enum ShaderPalette {
    NAME("Identifiers//Identifier", IDENTIFIER),
    RESERVED("Language//Keyword", KEYWORD),
    CONTROL("Language//Control flow", KEYWORD),
    BOOL("Literals//Boolean", CONSTANT),
    STORAGE("Qualifiers//Storage", KEYWORD),
    MEMORY("Qualifiers//Memory", KEYWORD),
    INTERPOLATION("Qualifiers//Interpolation", KEYWORD),
    PRECISION("Qualifiers//Precision", KEYWORD),
    OTHER_QUALIFIER("Qualifiers//Other", KEYWORD),
    LAYOUT("Qualifiers//Layout keyword", KEYWORD),
    LAYOUT_ITEM("Qualifiers//Layout parameter", METADATA),
    BUILTIN_TYPE("Types//Built-in type", CLASS_NAME),
    STRUCT("Types//Struct declaration", CLASS_NAME),
    TYPE_USE("Types//User type reference", CLASS_REFERENCE),
    BLOCK("Types//Interface block", INTERFACE_NAME),
    FUNC("Functions//Declaration", FUNCTION_DECLARATION),
    CALL("Functions//Call", FUNCTION_CALL),
    BUILTIN_FUNC("Functions//Built-in function", STATIC_METHOD),
    BUILTIN_VAR("Variables//Built-in variable", PREDEFINED_SYMBOL),
    BUILTIN_CONST("Variables//Built-in constant", CONSTANT),
    GLOBAL("Variables//Global", GLOBAL_VARIABLE),
    UNIFORM("Variables//Uniform", STATIC_FIELD),
    IO("Variables//Shader input or output", INSTANCE_FIELD),
    LOCAL("Variables//Local", LOCAL_VARIABLE),
    ARG("Variables//Parameter", PARAMETER),
    WRITTEN_LOCAL("Variables//Reassigned local", REASSIGNED_LOCAL_VARIABLE),
    WRITTEN_ARG("Variables//Reassigned parameter", REASSIGNED_PARAMETER),
    CONST("Variables//Constant", CONSTANT),
    FIELD("Members//Struct or block field", INSTANCE_FIELD),
    SWIZZLE("Members//Vector swizzle", FIELD.key),
    METHOD("Members//Built-in method", INSTANCE_METHOD),
    CASE_LABEL("Language//Case label", LABEL),
    NUMBER_INT("Literals//Integer", NUMBER),
    NUMBER_FLOAT("Literals//Floating point", NUMBER),
    STRING_VALUE("Literals//String", STRING),
    ESCAPE("Literals//Valid escape", VALID_STRING_ESCAPE),
    BAD_ESCAPE("Literals//Invalid escape", INVALID_STRING_ESCAPE),
    BAD("Language//Invalid character", HighlighterColors.BAD_CHARACTER),
    LINE("Comments//Line", LINE_COMMENT),
    MULTILINE("Comments//Block", BLOCK_COMMENT),
    DOC("Comments//Documentation", DOC_COMMENT),
    DOC_TAG("Comments//Documentation tag", DOC_COMMENT_TAG),
    DOC_VALUE("Comments//Documentation tag argument", DOC_COMMENT_TAG_VALUE),
    DOC_CODE("Comments//Inline code", DOC_CODE_INLINE),
    DOC_MARKUP("Comments//Documentation markup", DOC_COMMENT_MARKUP),
    DOC_BLOCK("Comments//Documentation code block", DOC_CODE_BLOCK),
    DIRECTIVE("Preprocessor//Directive", METADATA),
    MACRO_PARAMETER("Preprocessor//Macro parameter", PARAMETER),
    MACRO_OPERATOR("Preprocessor//Stringification and token pasting", OPERATION_SIGN),
    MACRO("Preprocessor//Macro", CONSTANT),
    HEADER("Preprocessor//Include header path", HIGHLIGHTED_REFERENCE),
    EXTENSION("Preprocessor//Extension name", METADATA),
    BRACE("Punctuation//Braces", BRACES),
    BRACKET("Punctuation//Brackets", BRACKETS),
    PAREN("Punctuation//Parentheses", PARENTHESES),
    PERIOD("Punctuation//Dot", DOT),
    SEMI("Punctuation//Semicolon", SEMICOLON),
    SEPARATOR("Punctuation//Comma", COMMA),
    ASSIGN("Operators//Assignment", OPERATION_SIGN),
    ARITH("Operators//Arithmetic", OPERATION_SIGN),
    LOGIC("Operators//Logical", OPERATION_SIGN),
    COMPARE("Operators//Comparison", OPERATION_SIGN),
    BIT("Operators//Bitwise", OPERATION_SIGN),
    CONDITIONAL("Operators//Conditional", OPERATION_SIGN);
    final String label;
    final TextAttributesKey key;
    final IElementType token;
    ShaderPalette(String label, TextAttributesKey base) {
        this.label = label;
        key = TextAttributesKey.createTextAttributesKey("GLSLSUPPORT_" + switch (name()) {
            case "RESERVED" -> "KEYWORD";
            case "BUILTIN_TYPE" -> "TYPE";
            case "FUNC" -> "FUNCTION";
            case "ARG" -> "PARAMETER";
            case "BUILTIN_VAR" -> "BUILTIN";
            case "IO" -> "INTERFACE";
            default -> name();
        }, base);
        token = new IElementType("highlight " + name(), GlslLanguage.INSTANCE);
    }
    static ShaderPalette word(String s) {
        return switch (s) {
            case "layout" -> LAYOUT;
            case "true", "false" -> BOOL;
            case "if", "else", "while", "do", "for", "return", "break", "continue", "discard", "switch",
                "terminateInvocation", "terminateRayEXT", "ignoreIntersectionEXT", "demote" ->
                CONTROL;
            case "case", "default" -> CASE_LABEL;
            case "uniform", "buffer", "shared", "in", "out", "inout", "attribute", "varying", "const",
                "tileImageEXT", "rayPayloadNV", "rayPayloadEXT", "rayPayloadInNV", "rayPayloadInEXT",
                "hitAttributeNV", "hitAttributeEXT", "callableDataNV", "callableDataEXT", "callableDataInNV",
                "callableDataInEXT", "taskNV", "taskPayloadSharedEXT" ->
                STORAGE;
            case "readonly", "writeonly", "coherent", "volatile", "restrict", "devicecoherent",
                "queuefamilycoherent", "workgroupcoherent", "subgroupcoherent", "shadercallcoherent", "nonprivate",
                "nontemporal" ->
                MEMORY;
            case "flat", "smooth", "noperspective", "centroid", "sample", "patch", "pervertexNV", "pervertexEXT",
                "perprimitiveEXT", "perprimitiveNV", "perviewNV", "__explicitInterpAMD" ->
                INTERPOLATION;
            case "precision", "highp", "mediump", "lowp", "precise" -> PRECISION;
            default -> {
                if (Catalog.has(s, "types"))
                    yield BUILTIN_TYPE;
                if (Syntax.QUALIFIERS.contains(s))
                    yield OTHER_QUALIFIER;
                if (Catalog.has(s, "functions"))
                    yield BUILTIN_FUNC;
                if (Catalog.has(s, "variables"))
                    yield s.startsWith("gl_Max") || s.startsWith("gl_Min") || s.startsWith("GL_") ? BUILTIN_CONST
                                                                                                  : BUILTIN_VAR;
                if (Catalog.has(s, "keywords"))
                    yield RESERVED;
                if (Catalog.has(s, "extensions"))
                    yield EXTENSION;
                yield NAME;
            }
        };
    }
    static ShaderPalette symbol(String s) {
        return switch (s) {
            case "{", "}" -> BRACE;
            case "[", "]" -> BRACKET;
            case "(", ")" -> PAREN;
            case "." -> PERIOD;
            case ";" -> SEMI;
            case "," -> SEPARATOR;
            case "?", ":" -> CONDITIONAL;
            case "=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=" -> ASSIGN;
            case "==", "!=", "<", ">", "<=", ">=" -> COMPARE;
            case "&&", "||", "^^", "!" -> LOGIC;
            case "&", "^", "|", "~", "<<", ">>" -> BIT;
            default -> ARITH;
        };
    }
    static ShaderPalette lexical(Lexer.Token t) {
        return switch (t.kind()) {
            case WORD -> word(t.text());
            case SYMBOL -> symbol(t.text());
            case SPACE -> null;
            case COMMENT ->
                t.text().startsWith("/**") || t.text().startsWith("///") ? DOC
                    : t.text().startsWith("//")                          ? LINE
                                                                         : MULTILINE;
            case NUMBER -> {
                String n = t.text().toLowerCase(Locale.ROOT);
                yield(n.startsWith("0x") ? n.contains("p") : n.contains(".") || n.contains("e") || n.endsWith("f"))
                    ? NUMBER_FLOAT
                    : NUMBER_INT;
            }
            case DIRECTIVE -> DIRECTIVE;
            case STRING -> STRING_VALUE;
            case BAD -> BAD;
        };
    }
}
