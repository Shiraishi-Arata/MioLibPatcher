package com.mio.libpatcher.transformer;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public class ImGuiMoulberryTransformer implements BaseTransformer {

    @Override
    public String getTargetClassName() {
        return "imgui.moulberry92.ImGui";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod loadLibraryMethod = clazz.getDeclaredMethod("tryLoadFromClasspath");

        loadLibraryMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getClassName().equals("java.lang.System")
                        && m.getMethodName().equals("load")) {

                    m.replace(
                            "{ " +
                                    "   String libPath = java.lang.System.getProperty(\"imgui_moulberry_path\");" +
                                    "   if (libPath != null) {" +
                                    "       java.lang.System.load(new java.io.File(libPath).getAbsolutePath());" +
                                    "   } else {" +
                                    "       $_ = $proceed($$);" +
                                    "   }" +
                                    "}"
                    );
                }
            }
        });
    }
}