package com.mio.libpatcher.transformer;

import com.mio.libpatcher.util.LogUtil;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * Removes calls to BootstrapHooks.filterAutomaticModules() from
 * java.lang.module.Resolver, preventing the NoClassDefFoundError
 * that occurs when ModuleLayerHandler.buildLayer() resolves modules.
 */
public class ResolverTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "java.lang.module.Resolver";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        LogUtil.info("Transforming Resolver");
        final boolean[] found = {false};
        for (CtMethod method : clazz.getDeclaredMethods()) {
            method.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall mc) throws CannotCompileException {
                    if (mc.getClassName().equals("settingdust.lazyyyyy.forge.core.BootstrapHooks") &&
                        mc.getMethodName().equals("filterAutomaticModules")) {
                        LogUtil.info("Removing BootstrapHooks.filterAutomaticModules() call from " + method.getName());
                        // Replace with a no-op; if void, {} works, otherwise return null
                        try {
                            mc.replace("{}");
                        } catch (CannotCompileException e) {
                            // Method returns a value, return null instead
                            mc.replace("$_ = ($r) null;");
                        }
                        found[0] = true;
                    }
                }
            });
        }
        if (found[0]) {
            LogUtil.info("Successfully removed BootstrapHooks calls from Resolver");
        } else {
            LogUtil.info("No BootstrapHooks calls found in Resolver");
        }
    }
}
