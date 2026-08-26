package com.zack88604.autoupdater.gui.api;

/** Internal child-JVM main class used to execute a preset helper entry point. */
public final class JavaHelperLauncher {

    private JavaHelperLauncher() {
    }

    /** Launch the helper class named by the trusted, already-approved preset manifest. */
    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("Java helper launcher requires one entry-point class name");
            System.exit(2);
            return;
        }
        try {
            JavaHelperSession session = JavaHelperSession.open();
            Class<?> candidate = Class.forName(arguments[0], true,
                    JavaHelperLauncher.class.getClassLoader());
            Class<? extends JavaHelperEntrypoint> entrypointType =
                    candidate.asSubclass(JavaHelperEntrypoint.class);
            JavaHelperEntrypoint entrypoint = entrypointType.getDeclaredConstructor().newInstance();
            entrypoint.run(session);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
