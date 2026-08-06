<div align="center">

  <img src="http://nodel.io/media/1066/logo-nodel.png" alt="Nodel logo" height="110">

  <p>
    <a href="http://www.mozilla.org/MPL/2.0">
      <img src="https://img.shields.io/badge/license-MPL%202.0-brightgreen.svg" alt="License: MPL 2.0">
    </a>
    <a href="https://github.com/museumsvictoria/nodel/releases">
      <img src="https://img.shields.io/github/v/release/museumsvictoria/nodel.svg" alt="Latest release">
    </a>
    <a href="https://github.com/museumsvictoria/nodel/releases">
      <img src="https://img.shields.io/github/downloads/museumsvictoria/nodel/total?logo=github" alt="Total downloads">
    </a>
    <img src="https://img.shields.io/badge/GraalVM%20CE-25.2.4-orange.svg" alt="Requires GraalVM Community 25.2.4">
  </p>

</div>

> [!IMPORTANT]
> You are on the **`v3` branch** — **Nodel 3.x**, the next-generation line where node
> scripts run on **GraalVM** instead of Jython 2.5: recipes can be written in
> **Python 3** (`script.py`, GraalPy) or **JavaScript** (`script.js`, GraalJS), side by
> side in the same host. It is developed alongside regular Nodel (2.2.x, the `dev`
> branch) and remains fully wire-compatible with existing 2.2.x hosts. See
> `POLYGLOT_INTEGRATION.md` for architecture and status, `recipes/python3/` for modern
> Python recipes and `recipes/javascript/` for the JavaScript authoring guide.

Nodel is an open source digital media control system for museums and galleries.

It uses a series of nodes that perform **"actions"** or respond to **"signals"** to control various devices on a network, allowing quick and easy management of digital media devices.

Nodel is typically used to control digital media in galleries, museums, corporate meeting rooms and digital signage.

In short, Nodel can manage any programmable device across a wide range of platforms.

###### Why Nodel?

* It is simple to deploy, operate and maintain
* It runs on any operating system
* It runs on any browser including mobile devices
* Best of all, it is open source and free

-------------
![activityroomexample](http://nodel.io/media/1065/activityroomnodel_cut.png)

About
============

Nodel is comprised of two parts - the platform and the recipes.

### Platform 

The **platform** or core system is written in Java and is the communication layer that allows nodes to interact with each other.

###### Why Java?

* It is one of the most portable and ubiquitous managed execution environments in the industry, running 3 billion devices worldwide
* It allows large numbers of nodes to be efficiently hosted with the smallest footprint
* It is a very mature and robust ecosystem

An active instance of the platform (aka. "nodehost") can manage any number of integrated media devices, or assistant tools (schedulers, monitors, groups) to form a coherent and manageable network of *nodes*.

These nodes are discoverable from other nodehosts on the same network, by which a series of user specified *bindings* allows the seamless propagation of remote actions and signals between a group of nodes.

### Recipes

**Recipes** are scripted in Python and are programmed to perform tasks, which might involve switching on a projector or playing a certain audio or video clip.

###### Why Python?

* It is equally ubiquitous in the industry with readily accessible documentation, libraries and source code
* It excels as a scripting environment both for heavy and lightweight tasks
* Short, succinct and neat code makes scripting a breeze

There are example available on the [recipes](https://github.com/museumsvictoria/nodel-recipes) repository.

The simple high-level nature of the Nodel protocol means aternative languages and environments can easily participate in the Nodel network as long as they come with a minimal set of networking functions.

### Nodes

A node is a self-managed, self-announcing, self-describing member of the subscription-based event-network. It's generated when a recipe sits inside the `/nodes/node_name` folder of a nodehost.

![5b4c17b046eae](https://i.loli.net/2018/07/16/5b4c17b046eae.png)

A node might represent and manage:

* A physical device
* A virtual software component on the network
* Sub-components

Each node features:

* A simple high-level addressing scheme
* A simple messaging protocol using short, well defined, human-friendly message “packets”
* A means to fully query its behaviour points – i.e. actions and signals
* Automatic management of connections between nodes
* A native TCP terminal-like interface for simple low-latency programmatic control
* An HTTP/HTML interface for high-level as well as simple programmatic control

Nodes communicate with each other using a simple, open, language independent, text-based, human readable protocol written in JSON (JavaScript Object Notation) using the principles of REST (REpresentational State Transfer). Being text-based and human readable, commonly available tools such as Telnet and Notepad can be used to test and diagnose problems on a given network.


Specifications
============

A node can run on any hardware which runs the Java Virtual Machine, which includes OS X, Windows and Linux based devices. As such, no proprietary hardware is required to install and run the system. This requires no complex and expensive infrastructure or servers to operate and allows a wide range of devices such as the cost-effective Raspberry Pi to be used in a control system design.

The platform-independent nature of Nodel also allows it to easily scale to suit your environment. Your organisation is not limited in the choice of hardware or software and the simplicity of the Nodel architecture makes it the ideal choice as a control system.

System requirements
============

* The **self-contained package** needs no Java at all (bundled runtime) – Linux x64/arm64 and macOS arm64
* The classic jar runs on operating systems supported by GraalVM Community 25.2.4 – including macOS, Windows and Linux
* A current web browser
* Made for mobile

Quick start (no Java required)
==============================

Every build produces a **self-contained package** with Java built in — nothing
to install first. This is the recommended way to run Nodel on a fresh machine
(e.g. a gallery PC or a Raspberry Pi-class device):

1. **Download** the latest tested v3 package:
   * [Linux x64](https://github.com/scroix/nodel/releases/download/v3-tip/nodelhost-v3-linux-x64.tar.gz)
   * [Linux arm64](https://github.com/scroix/nodel/releases/download/v3-tip/nodelhost-v3-linux-arm64.tar.gz) (Raspberry Pi 4/5 class)
   * [macOS arm64](https://github.com/scroix/nodel/releases/download/v3-tip/nodelhost-v3-macos-arm64.zip) (Apple silicon)
2. **Extract it** somewhere permanent, e.g. your home folder:
   * Linux: `tar xzf nodelhost-v3-linux-*.tar.gz`
   * macOS: double-click the zip, or `unzip nodelhost-v3-macos-arm64.zip`
3. **Make a folder for Nodel's files and start it from there** (Nodel keeps
   its nodes and settings in the folder you start it from):
   * Linux:
     ```
     mkdir -p ~/nodel-home && cd ~/nodel-home
     ~/nodelhost/bin/nodelhost
     ```
   * macOS:
     ```
     mkdir -p ~/nodel-home && cd ~/nodel-home
     ~/nodelhost.app/Contents/MacOS/nodelhost
     ```
     (first run on macOS may need right-click → Open once, or
     `xattr -dr com.apple.quarantine ~/nodelhost.app`, because the package is
     not code-signed)
4. **Open http://localhost:8085** in a browser — the Nodel web UI appears.
5. Drop some [recipes](https://github.com/museumsvictoria/nodel-recipes) into
   the `nodes` folder it created, or add nodes from the web UI. Python 3
   (`script.py`) and JavaScript (`script.js`) recipes both work.

To stop Nodel, press Enter in its console (or just close the terminal).
The [v3 tip prerelease](https://github.com/scroix/nodel/releases/tag/v3-tip)
also includes a plain `nodelhost-v3.jar` for machines with GraalVM Community
25.2.4 installed. Both forms use the optimizing runtime; the self-contained
packages are recommended because they carry the matching runtime with them.

Quick start (classic jar)
=========================
![5b4c2e9005ffc](https://i.loli.net/2018/07/16/5b4c2e9005ffc.gif)
* **download a [release](https://github.com/museumsvictoria/nodel/releases)** (needs GraalVM Community 25.2.4 installed)
* open a console
* `java -jar nodel.jar`
* drop some [recipes](https://github.com/museumsvictoria/nodel-recipes) into `nodes` folder
* check http://localhost:8085

Building and releases
=====================
* Latest releases can be found in [github releases](https://github.com/museumsvictoria/nodel/releases)
* Can be easily built from scratch using these [instructions](BUILDING.md)

Notes
=====
* the self-contained package requires no Java install; the classic jar on this branch requires **GraalVM Community 25.2.4**
* for service / daemon use, see [wiki pages](https://github.com/museumsvictoria/nodel/wiki)
* check `bootstrap` files for startup config


Credits
=======

Nodel is a joint venture established between [Museums Victoria](http://museumsvictoria.com.au) and [Automatic](http://automatic.com.au). It was imagined as a replacement for Museum Victoria’s gallery control system at the time.

You may find out more about the Nodel project on the [White Paper](https://raw.githubusercontent.com/museumsvictoria/nodel/gh-pages/docs/White_Paper-Nodel.pdf).
