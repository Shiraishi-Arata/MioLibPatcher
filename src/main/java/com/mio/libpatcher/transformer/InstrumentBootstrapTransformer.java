package com.mio.libpatcher.transformer;

import com.mio.libpatcher.BootstrapJarLoader;
import com.mio.libpatcher.util.LogUtil;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URI;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class InstrumentBootstrapTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "settingdust.lazyyyyy.forge.core.ClassLoaderInjector";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        // Only make injectBootstrap a no-op (prevents SIGSEGV from appendToBootstrapClassLoaderSearch)
        // Leave injectMcBootstrap() intact — it needs to run to load the mc-bootstrap module
        try {
            CtMethod method = clazz.getDeclaredMethod("injectBootstrap");
            method.setBody("{}");
            LogUtil.info("Made injectBootstrap() a no-op");
        } catch (Exception e) {
            LogUtil.error("Could not modify injectBootstrap(): " + e);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        String target = getTargetClassName().replace(".", "/");
        if (!target.equals(className)) {
            return classfileBuffer;
        }
        LogUtil.info("Transform called for " + className);

        // Preload BootstrapHooks before injectMcBootstrap() needs it
        // This runs during class LOADING, before the class is used
        preloadBootstrapHooks(protectionDomain, loader, classfileBuffer);

        // Now use javassist to make injectBootstrap a no-op
        try {
            CtClass clazz = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
            transform(clazz);
            byte[] bytes = clazz.toBytecode();
            clazz.detach();
            LogUtil.info("Transform returning " + bytes.length + " bytes");
            return bytes;
        } catch (Throwable e) {
            LogUtil.error("javassist transform failed: " + e);
        }
        return classfileBuffer;
    }

    private void preloadBootstrapHooks(ProtectionDomain protectionDomain, ClassLoader loader, byte[] classfileBuffer) {
        try {
            Class.forName("settingdust.lazyyyyy.forge.core.BootstrapHooks", false, null);
            LogUtil.info("BootstrapHooks already accessible in boot classloader");
            return;
        } catch (ClassNotFoundException e) {
            LogUtil.info("BootstrapHooks not yet accessible, attempting to preload");
        }

        if (BootstrapJarLoader.isAvailable()) {
            // Strategy 1: Find core JAR via the class's own resource URL (works during initial loading)
            findAndExtractFromCoreJar(loader);

            // Strategy 2: Check for already-exported copy in .lazyyyyy/
            if (!isBootstrapHooksLoaded()) {
                tryLoadFromExportedDir();
            }
        } else {
            LogUtil.error("BootstrapJarLoader not available (Unsafe not found)");
        }

        if (isBootstrapHooksLoaded()) {
            LogUtil.info("SUCCESS: BootstrapHooks is now loaded");
        } else {
            LogUtil.error("FAILED: Could not load BootstrapHooks by any strategy");
        }
    }

    private boolean isBootstrapHooksLoaded() {
        try {
            Class.forName("settingdust.lazyyyyy.forge.core.BootstrapHooks", false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void findAndExtractFromCoreJar(ClassLoader loader) {
        try {
            URL url = (loader != null)
                ? loader.getResource("settingdust/lazyyyyy/forge/core/ClassLoaderInjector.class")
                : ClassLoader.getSystemResource("settingdust/lazyyyyy/forge/core/ClassLoaderInjector.class");
            if (url == null) {
                LogUtil.info("Class resource not found, scanning directories");
                tryScanForCoreJar();
                return;
            }
            LogUtil.info("Found class resource: " + url);

            if (!"jar".equals(url.getProtocol())) {
                LogUtil.info("Class not inside a JAR (protocol=" + url.getProtocol() + "), scanning directories");
                tryScanForCoreJar();
                return;
            }

            String path = url.getPath();
            int sep = path.indexOf('!');
            if (sep < 0) {
                LogUtil.info("Unexpected JAR URL format, scanning directories");
                tryScanForCoreJar();
                return;
            }

            String jarUrl = path.substring(0, sep);
            if (jarUrl.startsWith("file:")) jarUrl = jarUrl.substring(5);
            File jarFile = new File(new URI(jarUrl));
            LogUtil.info("Core JAR location: " + jarFile + " isFile=" + jarFile.isFile());

            if (jarFile.isFile()) {
                JarFile coreJar = new JarFile(jarFile);
                loadBootstrapJarFromCore(coreJar, "lazyyyyy-lexforge-bootstrap.jar");
                coreJar.close();
            } else {
                LogUtil.info("Core JAR file does not exist, scanning directories");
                tryScanForCoreJar();
            }
        } catch (Exception e) {
            LogUtil.error("findAndExtractFromCoreJar failed: " + e);
            tryScanForCoreJar();
        }
    }

    private void loadBootstrapJarFromCore(JarFile coreJar, String embeddedName) {
        try {
            JarEntry entry = coreJar.getJarEntry(embeddedName);
            if (entry == null) {
                LogUtil.info("Embedded JAR not found: " + embeddedName);
                return;
            }
            File tempJar = File.createTempFile("bootstrap-", ".jar");
            tempJar.deleteOnExit();
            try (java.io.InputStream is = coreJar.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(tempJar)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            }
            LogUtil.info("Extracted " + embeddedName + " -> " + tempJar + " (" + tempJar.length() + " bytes)");
            BootstrapJarLoader.loadFromJar(tempJar);
            if (tempJar.length() == 0) {
                LogUtil.error("Extracted file is empty!");
            }
        } catch (Exception e) {
            LogUtil.error("loadBootstrapJarFromCore failed: " + e);
        }
    }

    private void tryLoadFromExportedDir() {
        try {
            File cwd = new File(System.getProperty("user.dir", "."));
            File exportedJar = new File(new File(cwd, ".lazyyyyy"), "lazyyyyy-lexforge-bootstrap.jar");
            LogUtil.info("Checking exported bootstrap JAR: " + exportedJar + " exists=" + exportedJar.isFile());
            if (exportedJar.isFile()) {
                LogUtil.info("Loading from exported JAR: " + exportedJar);
                BootstrapJarLoader.loadFromJar(exportedJar);
            } else {
                LogUtil.info("Exported bootstrap JAR not found at " + exportedJar);
            }
        } catch (Exception e) {
            LogUtil.error("tryLoadFromExportedDir failed: " + e);
        }
    }

    private void tryScanForCoreJar() {
        try {
            File[] searchDirs = {
                new File(System.getProperty("user.dir", ".")),
                new File(System.getProperty("user.dir", "."), "mods"),
                new File(".").getAbsoluteFile(),
            };
            for (File dir : searchDirs) {
                if (dir.isDirectory()) {
                    for (File f : dir.listFiles()) {
                        if (f.isFile() && f.getName().contains("lazyyyyy-lexforge-core") && f.getName().endsWith(".jar")) {
                            LogUtil.info("Found core JAR by scanning: " + f);
                            JarFile coreJar = new JarFile(f);
                            loadBootstrapJarFromCore(coreJar, "lazyyyyy-lexforge-bootstrap.jar");
                            coreJar.close();
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error("tryScanForCoreJar failed: " + e);
        }
    }
}
