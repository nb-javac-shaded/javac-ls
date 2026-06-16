# Instance Registry

## Overview

Each javac-ls process registers itself in `~/.javacls/running/` during startup to enable discovery of running instances.

## Implementation

### Registry Location
- **Directory**: `~/.javacls/running/`
- **File naming**: Each file is named with the port number (e.g., `27511`)
- **File content**: The absolute path to the workspace directory

### Lifecycle

1. **Startup** (`JavacLsServerLauncher.initialize()`):
   - Creates `~/.javacls/running/` directory if it doesn't exist
   - Creates a file named with the server port
   - Writes the workspace path to the file

2. **Shutdown** (`JavacLsServerLauncher.shutdown()`):
   - Deletes the registry file
   - Cleans up on normal shutdown and via shutdown hook

## Usage

### Workspace Selection

The workspace is chosen via system property:
```bash
-Djavacls.workspace.path=/path/to/workspace
```

Default: `~/.javacls/workspace`

### Starting Multiple Instances

```bash
# Instance 1: Port 27511, default workspace
java -Djavacls.server.port=27511 -jar javac-ls.jar

# Instance 2: Port 27512, custom workspace
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
2. Read each file to get the workspace path
3. Connect to the port specified by the filename

Example discovery:
```java
File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
for (File registryFile : runningDir.listFiles()) {
    int port = Integer.parseInt(registryFile.getName());
    String workspacePath = Files.readString(registryFile.toPath());
    System.out.println("Found instance: port=" + port + ", workspace=" + workspacePath);
}
```

## Classes

- **`InstanceRegistry`**: Manages registration/unregistration
- **`ServerFlags`**: Provides workspace path configuration
- **`JavacLsServerLauncher`**: Integrates registry into server lifecycle

## Tests

See `InstanceRegistryTest.java` for:
- Registry file creation
- File content verification
- Unregistration cleanup
- Multiple instance support
- Launcher integration
