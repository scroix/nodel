# BUILDING FROM SCRATCH
In short, clone this repository and run `gradlew build`.

> [!NOTE]
> This branch hosts node scripts on **GraalVM Python (GraalPy, Python 3)**
> instead of Jython 2.5. The build compiles and tests with **GraalVM Community
> 25.2.4 on JDK 25.0.4**, which Gradle provisions automatically through
> Foojay. You only need enough Java on the PATH to bootstrap Gradle itself
> (JDK 17+). *Running*
> the built `nodelhost` jar requires that same GraalVM line; other JDK
> distributions are not supported.

The steps below describe usage on both Windows and Linux
* The inline snippets refer to **Windows**
* The section further below shows a complete example for **Linux**

---

## STEP 1: ENSURE PRIMARY DEPENDENCIES ARE PRESENT†
  1. **GraalVM Community 25.2.4** recommended (JDK 17+ is enough to bootstrap
      Gradle because the build auto-provisions its GraalVM toolchain, but the
      built jar itself requires GraalVM), see the [GraalVM Community releases](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.2.4)
   2. **Git**, see [latest versions](https://git-scm.com/downloads)
      * latest Windows snapshot link - [PortableGit-2.50.0-64-bit.7z.exe](https://github.com/git-for-windows/git/releases/download/v2.50.0.windows.1/PortableGit-2.50.0-64-bit.7z.exe)
      * Linux install `apt-get install git`

† all support "portable" low-impact installation, examples here are extracted to `C:\Apps\jdk25` and `C:\Apps\git\bin` respectively.

## STEP 2: ENSURE DEPENDENCIES ARE ACCESSIBLE
* ensure dependencies above, are on path, e.g. on Windows:
```bat
set PATH=C:\Apps\git\bin;C:\Apps\jdk25\bin;%PATH%
```

## STEP 3: CLONE REPOSITORY
* ensure primary dependencies, above, are on path (see example in STEP 2, above)
* then Clone! e.g. cloning into `c:\temp\nodel-build` directory:
```bat
git clone https://github.com/museumsvictoria/nodel c:\temp\nodel-build
```

## STEP 4: EXECUTE BUILD
* Build! Example, from the `c:\temp\nodel-build` directory:
```bat
cd  c:\Temp\nodel-build
gradlew build
```
* on Windows some Firewall pop-up may need to be acknowledged
* check `nodel-jyhost/build/distributions/standalone` directory for binary release e.g. `nodelhost-dev-2.2.1-rev521.jar`

---

## Running for Development/Testing (Without Full Build)

To run the application directly for development or testing purposes without creating a standalone JAR file, use the Gradle `run` task. It executes with the auto-provisioned GraalVM Community 25.2.4 toolchain and its optimizing runtime.

From the project root directory (`nodel`), execute:

```bash
./gradlew :nodel-jyhost:run
```

The application will start, print its status messages (including the web interface URL if applicable), and wait for you to press `Enter` in the console to initiate a shutdown.

This method is useful for quick testing cycles as it avoids the overhead of building the full distributable package.

---

**Full example using clean Linux environment¹**

```bash
# download and extract GraalVM Community 25.2.4 (Linux arm64 example)
wget https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz
tar xf graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz

# Your Java ends up in ~/graalvm-community-25.2.4+7.1

# (git is normally already available)

# adjust PATH to ensure your Java is used first
export JAVA_HOME=~/graalvm-community-25.2.4+7.1
export PATH="$JAVA_HOME/bin:$PATH"

# quickly verify versions of all dependencies

java -version 
# e.g. >> OpenJDK Runtime Environment GraalVM CE 25.2.4

git version
# e.g. >> git version 2.39.5

# clone repo
git clone https://github.com/museumsvictoria/nodel nodel-build

# execute full build
cd ~/nodel-build
./gradlew build
# e.g. >> Starting a Gradle Daemon (subsequent builds will be faster)
#      >> ...
#      >> BUILD SUCCESSFUL in 1m 15s
#      >> 22 actionable tasks: 21 executed, 1 up-to-date

# check output (standalone JAR file)
cd ~/nodel-build/nodel-jyhost/build/distributions/standalone/
ls
# e.g. >> -rw-r--r-- 1 nodel nodel 20214394 May 19 16:03 nodelhost-dev-2.2.1-rev521.jar

# run Nodel (optional; needs GraalVM Community 25.2.4, no extra JVM flags —
# the jar's manifest carries the required Add-Opens/native-access entries).
java -jar ~/nodel-build/nodel-jyhost/build/distributions/standalone/nodelhost-dev-2.2.1-rev521.jar -p 0
# e.g. >> Nodel [GraalPython] v2.2.1-dev_r521 is running.
#      >>
#      >> Press Enter to initiate a shutdown.
#      >>
#      >>    (web interface available at http://172.17.128.1:8085)
```

**Source update, clean and build re-run**
```
cd ~/nodel-build
git pull

./gradlew clean
# or
git clean -fxd

./gradlew build
```

**Full cleanup**
```bash
rm graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz
rm -fr ~/graalvm-community-25.2.4+7.1
rm -fr ~/nodel-build
```

---
¹ For macOS, adjust to suit.

## TESTING (Integration & E2E)

Nodel includes Playwright-based integration and E2E tests that run a dedicated nodehost on port `18085` in a temporary `nodel-jyhost/nodelhost-temp/` directory.

### Run tests
```bash
./gradlew :nodel-jyhost:integrationTest
./gradlew :nodel-jyhost:e2eTest
```

### Visual debugging
`HEADED` and `SLOWMO` are read by the test JVM (not tracked as Gradle task inputs), so add `--rerun` or an up-to-date task will silently skip the tests and no browser will appear:
```bash
HEADED=1 SLOWMO=500 ./gradlew :nodel-jyhost:e2eTest --rerun
```
or on Windows (PowerShell):
```powershell
$env:HEADED=1; $env:SLOWMO=500; ./gradlew :nodel-jyhost:e2eTest --rerun
```

### When tests fail
1. Check `nodel-jyhost/nodelhost-temp/output.log` and `nodel-jyhost/nodelhost-temp/error.log` for server issues.
2. Re-run with `HEADED=1` and/or `PWDEBUG=1` to watch or debug the browser.
3. Check `nodel-jyhost/build/reports/tests/` for JUnit HTML reports.

### Skip tests
The default `test` task runs the full suite (integration and E2E); skip it entirely with:
```bash
./gradlew build -x test
```

### Discovery mode (tests)
By default, tests use LocalAutoDNS for deterministic discovery results (the test suite fails fast if it did not load). To exercise real multicast discovery, set:
```bash
NODEL_TEST_DISCOVERY=1 ./gradlew :nodel-jyhost:integrationTest --tests org.nodel.DiscoverySmokeTests --rerun
```
(`--rerun` because the environment variable is not a Gradle task input.)


## SELF-CONTAINED PACKAGING (no Java on the target machine)

`./gradlew :nodel-jyhost:packageAppImage` builds a **jpackage app-image** —
the standalone jar plus a bundled GraalVM Community runtime — and archives it under
`nodel-jyhost/build/distributions/app-image/` (`.tar.gz` on Linux, `.zip` on
macOS). The image itself is left in `nodel-jyhost/build/jpackage/image/`
(launcher: `nodelhost/bin/nodelhost` on Linux,
`nodelhost.app/Contents/MacOS/nodelhost` on macOS). The plain
`build`→runnable-jar flow is unaffected; packaging is additive.

Verify a packaged launcher with the deterministic functional gate:
```bash
./scripts/packaged-smoke.sh run nodel-jyhost/build/jpackage/image/nodelhost/bin/nodelhost
```
and/or point the full suites at it (`GRAAL_NODEL_JAR=<launcher>` — anything
not ending in `.jar` is executed directly):
```bash
GRAAL_NODEL_JAR=$PWD/nodel-jyhost/build/jpackage/image/nodelhost/bin/nodelhost ./scripts/compat-smoke.sh
```
CI (`.github/workflows/package.yml`) builds the Linux x64 package on every
push, smokes it inside a java-less `debian:bookworm-slim` container, then
uploads the archive as a workflow artifact.

### Experimental: GraalVM Native Image

A true native binary also builds and passes the full smoke suites:
```bash
export GRAALVM_HOME=<GraalVM Community 25.2.4 on JDK 25.0.4>
./gradlew -PnativeImage :nodel-jyhost:nativeCompile
# -> nodel-jyhost/build/native/nativeCompile/nodelhost (~300 MB, needs ~12 GB RAM to build)
```
**Caveat (why it's not the shipped artifact):** recipes may interop with any
Java class (`java.type(...)`), but a native image only contains classes
registered at build time — e.g. the default recipes-sync node (JGit) fails
under the native binary. See POLYGLOT_INTEGRATION.md (goal 3) for the full
config ledger before touching the metadata under
`nodel-jyhost/src/main/resources/META-INF/native-image/`.

### Wire compatibility vs the stock Jython release
`scripts/compat-smoke.sh` runs a stock release `nodelhost` jar (Jython) and this
branch's GraalVM host side-by-side using real multicast discovery and the Nodel
TCP binding protocol, then verifies mutual discovery plus remote action/event
round-trips in both directions:
```bash
./scripts/compat-smoke.sh
```
Useful overrides: `STOCK_NODEL_VERSION` (release tag), `SMOKE_JAVA` (GraalVM
Community 25 executable), `JY_PORT`/`GR_PORT`.

The non-required **Wire Compatibility** workflow runs this network-dependent
check weekly and on manual dispatch. Once those runs establish reliable GitHub
runner networking, the intended follow-up is targeted, non-required pull
request runs for changes under `nodel-framework/`, `nodel-jyhost/`,
`scripts/compat-smoke.sh`, or `scripts/smoke-lib.sh`.
