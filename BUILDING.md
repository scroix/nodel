# BUILDING FROM SCRATCH
In short, clone this repository and run `gradlew build`.

> [!NOTE]
> This branch hosts node scripts on **GraalVM Python (GraalPy, Python 3)**
> instead of Jython 2.5. The build compiles and tests with a **Java 21
> toolchain that Gradle provisions automatically** (foojay resolver) — you only
> need enough Java on the PATH to bootstrap Gradle itself (JDK 11+). *Running*
> the built `nodelhost` jar requires **Java 21 or newer**.

The steps below describe usage on both Windows and Linux
* The inline snippets refer to **Windows**
* The section further below shows a complete example for **Linux**

---

## STEP 1: ENSURE PRIMARY DEPENDENCIES ARE PRESENT†
  1. **Java JDK 21** recommended (JDK 11+ is enough to bootstrap Gradle — the build
      auto-provisions a JDK 21 toolchain for compiling and testing, but running the
      built jar needs 21+), see production ready distributions from [Amazon Corretto](https://aws.amazon.com/corretto)
      * latest Windows x64 redirected [download link](https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip)
      * latest Linux aarch64 redirected [download link](https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-linux-jdk.tar.gz)
      * latest Linux x64 redirected [download link](https://corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz)
   3. **Git**, see [latest versions](https://git-scm.com/downloads)
      * latest Windows snapshot link - [PortableGit-2.50.0-64-bit.7z.exe](https://github.com/git-for-windows/git/releases/download/v2.50.0.windows.1/PortableGit-2.50.0-64-bit.7z.exe)
      * Linux install `apt-get install git`

† all support "portable" low-impact installation, examples here are extracted to `C:\Apps\jdk21` and `C:\Apps\git\bin` respectively.

## STEP 2: ENSURE DEPENDENCIES ARE ACCESSIBLE
* ensure dependencies above, are on path, e.g. on Windows:
```bat
set PATH=C:\Apps\git\bin;C:\Apps\jdk21\bin;%PATH%
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

To run the application directly for development or testing purposes without creating a standalone JAR file, you can use the Gradle `run` task. This compiles the necessary code and executes the application using the configured Java 21 toolchain (auto-provisioned by Gradle if not installed locally).

From the project root directory (`nodel`), execute:

```bash
./gradlew :nodel-jyhost:run
```

The application will start, print its status messages (including the web interface URL if applicable), and wait for you to press `Enter` in the console to initiate a shutdown.

This method is useful for quick testing cycles as it avoids the overhead of building the full distributable package.

---

**Full example using clean Linux environment¹**

```bash
# download and extract Java JDK 21
wget https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-linux-jdk.tar.gz
tar xf amazon-corretto-21-aarch64-linux-jdk.tar.gz

# Your Java ends up in ~/amazon-corretto-21.x.y.z-linux-aarch64

# (git is normally already available)

# adjust PATH to ensure your Java is used first
export PATH=~/amazon-corretto-21.x.y.z-linux-aarch64/bin:$PATH

# quickly verify versions of all dependencies

java -version 
# e.g. >> openjdk version "21.0.x" LTS

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

# run Nodel (optional; needs Java 21+, no extra JVM flags — the jar's manifest
# carries the required Add-Opens entries)
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
rm amazon-corretto-21-aarch64-linux-jdk.tar.gz
rm -fr ~/amazon-corretto-21.x.y.z-linux-aarch64
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


### Wire compatibility vs the stock Jython release
`scripts/compat-smoke.sh` runs a stock release `nodelhost` jar (Jython) and this
branch's GraalVM host side-by-side using real multicast discovery and the Nodel
TCP binding protocol, then verifies mutual discovery plus remote action/event
round-trips in both directions:
```bash
./scripts/compat-smoke.sh
```
Useful overrides: `STOCK_NODEL_VERSION` (release tag), `SMOKE_JAVA` (Java 21+
executable), `JY_PORT`/`GR_PORT`.
