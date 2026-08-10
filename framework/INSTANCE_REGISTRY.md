# Instance Registry

## Overview

Each javac-ls process registers itself in `~/.javacls/running/` during startup to enable discovery of running instances.

## Implementation

### Registry Location
- **Directory**: `~/.javacls/running/`
- **File naming**: Each file is named with the port number (e.g., `27511`)
- **File content**: The absolute path to the server's state directory (index cache, classpath cache, etc.)

### Lifecycle

1. **Startup** (`JavacLsServerLauncher.initialize()`):
   - Creates `~/.javacls/running/` directory if it doesn't exist
   - Cleans stale entries (oldest 20 files, >3 min old, port not listening)
   - Checks for port conflicts (throws if port already in use)
   - Creates a file named with the server port
   - Writes the state directory path to the file

2. **Shutdown** (`JavacLsServerLauncher.shutdown()`):
   - Deletes the registry file
   - Cleans up on normal shutdown and via shutdown hook

## Workspace vs. Projects

The `-Djavacls.workspace.path` system property (default `~/.javacls/workspace`) controls the server's **internal state directory**, not a project workspace. This directory holds the index cache, classpath cache, and other persisted state.

Actual project content is provided by LSP clients. When a client connects and sends `initialize` with `workspaceFolders`, the server calls `WorkspaceModel.addProject()` and `indexProjectAsync()` for each folder. The server starts with zero projects and only learns about them at client connection time.

This means the registry file does **not** tell you which projects a given server is indexing — only where its state is stored.

### Limitations

- **No `didChangeWorkspaceFolders` support**: Workspace folders are only processed during the LSP `initialize` handshake. Dynamic folder adds/removes after connection are not yet handled.

## Usage

### Configuration

The state directory is chosen via system property:
```bash
-Djavacls.workspace.path=/path/to/state/dir
```

Default: `~/.javacls/workspace`

### Starting Multiple Instances

```bash
# Instance 1: Port 27511, default state directory
java -Djavacls.server.port=27511 -jar javac-ls.jar

# Instance 2: Port 27512, custom state directory
java -Djavacls.server.port=27512 \
     -Djavacls.workspace.path=/home/user/projects/workspace2 \
     -jar javac-ls.jar
```

After startup:
```
~/.javacls/running/27511  → contains "/home/user/.javacls/workspace"
~/.javacls/running/27512  → contains "/home/user/projects/workspace2"
```

### Discovering Running Instances

Client applications can:
1. List files in `~/.javacls/running/`
2. Read each file to get the state directory path
3. Connect to the port specified by the filename
4. Send LSP `initialize` with `workspaceFolders` to register projects

Example discovery:
```java
File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
for (File registryFile : runningDir.listFiles()) {
    int port = Integer.parseInt(registryFile.getName());
    String stateDirPath = Files.readString(registryFile.toPath());
    System.out.println("Found instance: port=" + port + ", stateDir=" + stateDirPath);
}
```

## Classes

- **`InstanceRegistry`**: Manages registration/unregistration and stale entry cleanup
- **`ServerFlags`**: Provides state directory path and port configuration
- **`JavacLsServerLauncher`**: Integrates registry into server lifecycle
- **`JavacLSServerImpl`**: Handles LSP `initialize` and maps workspace folders to projects

## Tests

See `InstanceRegistryTest.java` for:
- Registry file creation
- File content verification
- Unregistration cleanup
- Multiple instance support
- Launcher integration
