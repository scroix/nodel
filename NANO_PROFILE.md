# Nano native-image profile

The nano profile builds an experimental, Python-only Nodel host for small
ARMv8 devices. It removes the Truffle optimizing runtime from the native-image
classpath so GraalPy uses its interpreter-only fallback engine. The standard
JAR, app-image, tests, and default native image keep their existing classpath
and behaviour unless `-PnanoProfile` is supplied.

## Build

GraalVM Community 25.2.4 and about 11 GiB of builder heap are required:

```sh
NATIVE_IMAGE_OPTIONS=-J-Xmx11g ./gradlew -PnanoProfile :nodel-jyhost:nativeCompile
```

The executable is written to:

```text
nodel-jyhost/build/native/nativeCompile/nodelhost-nano
```

When running the raw binary non-interactively (background, service, benchmark),
pass `-Dnodel.consoleless=true`: without it an instant stdin EOF is treated as
an orderly shutdown request, exactly like the plain jar.

The image defaults to a 128 MiB maximum runtime heap (64 MiB boots but fails
the node-reload transient that a parameter save triggers — briefly two GraalPy
contexts — with MemoryError). Set another baked-in
ceiling when benchmarking by rebuilding, for example:

```sh
NATIVE_IMAGE_OPTIONS=-J-Xmx11g ./gradlew -PnanoProfile -PnanoMaxHeap=80m :nodel-jyhost:nativeCompile
```

The build uses `-Os`, `-march=compatibility`, and the serial garbage collector.

## Compatibility boundary

This is not a drop-in replacement for the standard v3 distribution:

- Recipes must be Python 3. JavaScript recipes are not included.
- The first-run Recipes Sync node is disabled. Launching with
  `-Dnodel.recipesSync.enabled=true` restores the bootstrap for diagnostics,
  but the node cannot function because JGit is excluded.
- JGit, SNMP4J, and JJWT are not included. Drop-in JARs are unsupported.
- Java interop is limited to types and members registered in the native-image
  reachability metadata. Arbitrary `java.type()` calls are unsupported.
- The Truffle optimizing runtime is absent. Guest code runs in the fallback
  interpreter, and the expected interpreter-only warning is suppressed only
  in this profile.
- The baked-in maximum heap defaults to 128 MiB. Change it with
  `-PnanoMaxHeap=NNm` and rebuild; do not assume a larger heap still meets the
  device RSS gate.

Use the standard JAR or app-image for unrestricted Java interop, JavaScript,
Recipes Sync, convenience libraries, or third-party JARs.

## Regenerate reachability metadata

The checked-in agent trace was produced by running the JVM host under the
Native Image tracing agent and driving the complete packaged smoke test. Use
the same workflow after changing the REST surface or script-to-host interop:

```sh
./gradlew :nodel-jyhost:shadowJar
TRACE_DIR="$(mktemp -d)"
HOST_JAR="$(ls -t nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1)"
JAVA_TOOL_OPTIONS="-agentlib:native-image-agent=config-output-dir=$TRACE_DIR" \
  ./scripts/packaged-smoke.sh run "$HOST_JAR"
```

Review the generated files before merging them into
`nodel-jyhost/src/main/resources/META-INF/native-image/org.nodel/nodel-jyhost/`.
Keep the hand-written `nodel-jyhost-manual` registrations: the tracing agent
does not observe all guest-to-host Java interop or Nodel's reflective model
types. Rebuild both the normal native image and the nano image after any
metadata update.
