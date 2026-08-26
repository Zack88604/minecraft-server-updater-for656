package com.zack88604.autoupdater.gui.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable optional JVM arguments supplied by a Java-helper preset. */
public final class JavaHelperLaunchSpec {

    private static final JavaHelperLaunchSpec EMPTY = new JavaHelperLaunchSpec(
            Collections.<String>emptyList());

    private final List<String> jvmArguments;

    private JavaHelperLaunchSpec(List<String> jvmArguments) {
        this.jvmArguments = Collections.unmodifiableList(new ArrayList<String>(jvmArguments));
    }

    /** Return a launch specification with no additional JVM arguments. */
    public static JavaHelperLaunchSpec empty() {
        return EMPTY;
    }

    /** Begin building a launch specification. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Return extra arguments placed before the helper JVM's classpath options.
     * The updater owns its executable, classpath, module path, and main class.
     */
    public List<String> getJvmArguments() {
        return jvmArguments;
    }

    /** Builder for {@link JavaHelperLaunchSpec}. */
    public static final class Builder {
        private final List<String> jvmArguments = new ArrayList<String>();

        private Builder() {
        }

        /** Add one non-empty, single-line JVM argument. */
        public Builder addJvmArgument(String argument) {
            String value = Objects.requireNonNull(argument, "argument").trim();
            if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("JVM argument must be a non-empty single line");
            }
            jvmArguments.add(value);
            return this;
        }

        /** Build an immutable launch specification. */
        public JavaHelperLaunchSpec build() {
            return jvmArguments.isEmpty() ? EMPTY : new JavaHelperLaunchSpec(jvmArguments);
        }
    }
}
