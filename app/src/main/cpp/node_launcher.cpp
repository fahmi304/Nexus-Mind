// Standalone Node.js launcher — compiled as libnode-launcher.so.
// Android API 29+ allows executing files from nativeLibraryDir, so naming
// this with the lib*.so convention lets it be both packaged and run as a binary.
// bridge.js passes it as the executable path when spawning child node processes.

namespace node {
    int Start(int argc, char** argv);
}

int main(int argc, char** argv) {
    return node::Start(argc, argv);
}
