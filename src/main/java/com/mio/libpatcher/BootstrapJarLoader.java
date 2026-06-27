package com.mio.libpatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class BootstrapJarLoader {
    private static final Object UNSAFE;
    private static final Method DEFINE_CLASS;
    private static final Method DEFINE_CLASS_SHORT;

    static {
        Object unsafe = null;
        Method defineClass = null;
        Method defineClassShort = null;
        Class<?> unsafeClass = null;

        // Try jdk.internal.misc.Unsafe first
        try {
            Class<?> candidate = Class.forName("jdk.internal.misc.Unsafe");
            java.lang.reflect.Field f = candidate.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            unsafeClass = candidate;
            System.out.println("[MioLibPatcher/BootstrapJarLoader] Using jdk.internal.misc.Unsafe");
        } catch (Exception e) {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] jdk.internal.misc.Unsafe not accessible: " + e);
        }

        // Fall back to sun.misc.Unsafe
        if (unsafe == null) {
            try {
                Class<?> candidate = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field f = candidate.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                unsafe = f.get(null);
                unsafeClass = candidate;
                System.out.println("[MioLibPatcher/BootstrapJarLoader] Using sun.misc.Unsafe");
            } catch (Exception e) {
                System.out.println("[MioLibPatcher/BootstrapJarLoader] sun.misc.Unsafe also not accessible: " + e);
            }
        }

        // Fall back to sun.misc.Unsafe.getUnsafe() via reflection
        if (unsafe == null) {
            try {
                Class<?> candidate = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Method getUnsafe = candidate.getMethod("getUnsafe");
                unsafe = getUnsafe.invoke(null);
                unsafeClass = candidate;
                System.out.println("[MioLibPatcher/BootstrapJarLoader] Using sun.misc.Unsafe.getUnsafe()");
            } catch (Exception e) {
                System.out.println("[MioLibPatcher/BootstrapJarLoader] sun.misc.Unsafe.getUnsafe() also not accessible: " + e);
            }
        }

        if (unsafeClass != null && unsafe != null) {
            try {
                defineClass = unsafeClass.getMethod("defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class);
                System.out.println("[MioLibPatcher/BootstrapJarLoader] Unsafe.defineClass(long) found: " + defineClass);
            } catch (NoSuchMethodException e) {
                try {
                    defineClassShort = unsafeClass.getMethod("defineClass", String.class, byte[].class, int.class, int.class);
                    System.out.println("[MioLibPatcher/BootstrapJarLoader] Unsafe.defineClass(short) found: " + defineClassShort);
                } catch (NoSuchMethodException e2) {
                    System.out.println("[MioLibPatcher/BootstrapJarLoader] No defineClass method found on " + unsafeClass);
                }
            }
        } else {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] Failed to init Unsafe (no class or null instance)");
        }

        UNSAFE = unsafe;
        DEFINE_CLASS = defineClass;
        DEFINE_CLASS_SHORT = defineClassShort;
    }

    public static boolean isAvailable() {
        return UNSAFE != null && (DEFINE_CLASS != null || DEFINE_CLASS_SHORT != null);
    }

    public static void loadFromJar(File jarFile) {
        if (!isAvailable()) {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] Unsafe not available, cannot load: " + jarFile);
            return;
        }
        if (jarFile == null) {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] jarFile is null");
            return;
        }
        if (!jarFile.isFile()) {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] jarFile does not exist: " + jarFile);
            return;
        }
        System.out.println("[MioLibPatcher/BootstrapJarLoader] Loading bootstrap JAR: " + jarFile + " (" + jarFile.length() + " bytes)");
        int count = 0;
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                if (entry.isDirectory()) continue;
                String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 6);
                try (InputStream is = jar.getInputStream(entry)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    byte[] classBytes = baos.toByteArray();
                    if (DEFINE_CLASS != null) {
                        DEFINE_CLASS.invoke(UNSAFE, className, classBytes, 0, classBytes.length, (ClassLoader) null, (ProtectionDomain) null);
                    } else {
                        DEFINE_CLASS_SHORT.invoke(UNSAFE, className, classBytes, 0, classBytes.length);
                    }
                    count++;
                } catch (Exception e) {
                    System.out.println("[MioLibPatcher/BootstrapJarLoader] Failed to define class " + className + ": " + e);
                }
            }
        } catch (Exception e) {
            System.out.println("[MioLibPatcher/BootstrapJarLoader] Failed to read JAR " + jarFile + ": " + e);
            e.printStackTrace(System.out);
        }
        System.out.println("[MioLibPatcher/BootstrapJarLoader] Loaded " + count + " classes from " + jarFile);
    }
}
