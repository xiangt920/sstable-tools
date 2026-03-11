# CLAUDE.md — AI Assistant Guide for sstable-tools

## Project Overview

**sstable-tools** is a Cassandra 3.x utility for analyzing and managing SSTables. It provides commands for inspecting SSTable statistics, viewing timestamp ranges, listing SSTables for a given table, and automating cold data migration between storage tiers (SSD → HDD).

---

## Repository Structure

```
sstable-tools/
├── bin/
│   └── sstable-tools             # Shell launcher script (sets up classpath, env vars)
├── conf/
│   ├── logback-sstable.xml       # Logging configuration (rolling file appenders)
│   ├── migrate.properties        # Cold migration parameters (keyspace, table, cron, dirs)
│   └── quartz.properties         # Quartz scheduler thread pool config
├── src/
│   ├── main/java/cn/ac/iie/
│   │   ├── drive/                # CLI framework: entry point + command routing
│   │   │   ├── Driver.java       # Main class; registers and dispatches commands
│   │   │   ├── Options.java      # Global config holder (cluster settings, etc.)
│   │   │   └── commands/         # One class per CLI command
│   │   │       ├── base/         # Abstract base commands (validate/execute pattern)
│   │   │       ├── DescribeCmd.java
│   │   │       ├── TimestampCmd.java
│   │   │       ├── SSTableCmd.java
│   │   │       ├── MigrateCmd.java
│   │   │       ├── RestoreCmd.java
│   │   │       └── CleanupCmd.java
│   │   ├── cassandra/            # Cassandra metadata interaction
│   │   │   ├── CassandraUtils.java
│   │   │   ├── NoSuchKeyspaceException.java
│   │   │   └── NoSuchTableException.java
│   │   ├── sstable/              # SSTable parsing and ANSI-colored output
│   │   │   ├── SSTableUtils.java
│   │   │   └── TableTransformer.java
│   │   ├── migrate/              # Cold data migration logic
│   │   │   ├── MigrateUtils.java
│   │   │   ├── MigrateDirectories.java
│   │   │   ├── MigrateDirectory.java
│   │   │   └── MigrateDirectoryComparator.java
│   │   ├── task/                 # Quartz job implementations
│   │   │   ├── MigrateTask.java
│   │   │   ├── DoMigrateTask.java
│   │   │   └── CleanupTask.java
│   │   └── utils/                # General utilities
│   │       ├── FileUtils.java    # File locking, copying, verification
│   │       ├── InvokeUtils.java  # Reflection helpers
│   │       └── SortedList.java   # Custom sorted list
│   └── test/
│       ├── java/cn/ac/iie/       # JUnit 4 + PowerMock tests
│       └── resources/            # Real binary SSTable test files (ma-*, mb-* formats)
├── pom.xml                       # Maven build descriptor
├── .travis.yml                   # CI: Oracle JDK 8, Cassandra 3.7
└── README.md                     # Chinese-language user documentation
```

---

## Build System

**Tool:** Apache Maven 3.x
**Java version:** 8 (source and target)
**Output:** `target/sstable-tools.jar` (fat JAR via maven-shade-plugin)

### Common Commands

```bash
# Full build (fat JAR)
mvn clean package

# Build, skip tests
mvn clean package -DskipTests

# Run tests only
mvn test

# Install to local Maven repo (skip tests)
mvn install -DskipTests=true
```

### Key Maven Plugins

| Plugin | Version | Purpose |
|--------|---------|---------|
| maven-compiler-plugin | 3.1 | Compile with `-opt -deprecation` |
| maven-shade-plugin | 2.3 | Fat JAR bundling all dependencies |
| exec-maven-plugin | 1.4.0 | Run application directly via Maven |
| maven-surefire-plugin | 2.19.1 | Unit test runner (assertions disabled) |

---

## Running the Tool

```bash
# Via fat JAR
java -jar target/sstable-tools.jar <command> [options]

# Via shell launcher
./bin/sstable-tools <command> [options]
```

### Available Commands

| Command | Description |
|---------|-------------|
| `describe -f <Data.db>` | Print SSTable statistics (partitions, rows, tombstones, compression, timestamps) |
| `timestamp -f <Data.db>` | Show min/max timestamp range of data in an SSTable |
| `sstable -k <keyspace> -t <table>` | List all SSTables for a table on the current node |
| `migrate -k <ks> -t <table> -c "<cron>" -e <secs> -m <retries> <dir1> <dir2>...` | Schedule cold data migration from SSD to HDD |
| `restore -f <Data.db>` | Restore a previously migrated SSTable |
| `cleanup -k <keyspace> -t <table>` | Remove orphaned files from migration directories |

### Environment Variables (used by `bin/sstable-tools`)

| Variable | Purpose |
|----------|---------|
| `CASSANDRA_HOME` | Cassandra installation root |
| `CASSANDRA_INCLUDE` | Path to Cassandra include script |
| `JAVA_HOME` | JDK installation directory |
| `CLASSPATH` | Extended classpath for Cassandra integration |

---

## Key Dependencies

| Artifact | Version | Role |
|----------|---------|------|
| `cassandra-all` | 3.7 | Cassandra server library (SSTable reader) |
| `cassandra-driver-core` | 3.0.0 | Cassandra cluster/session driver |
| `quartz` | 2.2.3 | Cron-based job scheduling for migration |
| `airline` | 0.6 | CLI argument parsing framework |
| `logback-classic` | 1.1.3 | Logging implementation |
| `jackson-core` | 2.6.4 | JSON processing |
| `jline` | 2.13 | Terminal input handling |
| `junit` | 4.6 | Test framework |
| `powermock-module-junit4` | 1.6.4 | Mocking for tests |

---

## Code Conventions

### Naming

- **Classes:** PascalCase — command classes end in `Cmd`, task classes in `Task`, exception classes in `Exception`
- **Methods/variables:** camelCase
- **Constants:** `UPPER_SNAKE_CASE`
- **Packages:** use Chinese academic domain style: `cn.ac.iie.*`

### Design Patterns

- **Template Method:** `SSTableToolCmd.run()` always calls `validate()` then `execute()` — override both in subclasses
- **Command Pattern:** Each CLI command implements `Runnable` and is registered in `Driver`
- **Job Pattern:** Quartz `Job` implementations in `cn.ac.iie.task` handle scheduled migration work

### Comments and Documentation

- In-source comments and `@date` annotations are written in **Chinese**
- Console output uses ANSI escape codes for color (constants like `ANSI_CYAN`, `ANSI_RED` in `TableTransformer`)

### Error Handling

- Domain-specific exceptions (`NoSuchKeyspaceException`, `NoSuchTableException`) are thrown from `CassandraUtils`
- File operations in `FileUtils` use file locking before copying and verify checksums after

---

## Testing

**Framework:** JUnit 4.6 + PowerMock 1.6.4
**Test data:** Real binary SSTable files located in `src/test/resources/` (formats `ma-*` and `mb-*`)

### Test SSTable Schemas

The test resources include SSTables for several table types:

- **composites** — composite partition/clustering key table
- **users** — simple user records
- **wide** — wide-row tables
- **collections** — tables with collection columns

### Running Tests

```bash
mvn test
```

Tests live in `src/test/java/cn/ac/iie/`. Key test files:

- `TestCUtils.java` — CQL parsing and SSTable metadata extraction
- `Utils.java` — shared test utilities and CQL schema definitions

---

## Configuration Files

### `conf/migrate.properties`

Controls cold data migration behavior:

```properties
# Target keyspace and table
keyspace=my_keyspace
table=my_table

# Cron expression for migration schedule
cron=0 0 * * * ?

# Data age threshold (seconds) to consider "cold"
expiration=2592000

# Max retry attempts per SSTable
max_retry=10

# Target migration directories (e.g., HDD mount points)
dirs=/mnt/hdd1,/mnt/hdd2
```

### `conf/quartz.properties`

```properties
org.quartz.threadPool.threadCount=2
```

### `conf/logback-sstable.xml`

- Rolling file appender: `sstable-tools.log` (INFO) and `sstable-tools.debug.log` (DEBUG)
- Max file size: 20 MB, 20 backup files retained
- DEBUG level enabled for Cassandra packages

---

## CI/CD

**Platform:** Travis CI (`.travis.yml`)

```yaml
language: java
jdk: oraclejdk8
install: mvn install -DskipTests=true
script: mvn test
cache:
  directories:
    - ~/.m2
```

Cassandra 3.7 is the tested version.

---

## Development Guidelines for AI Assistants

1. **Java 8 only** — do not use Java 9+ APIs or language features.
2. **Cassandra 3.7 API** — all SSTable reading uses `cassandra-all:3.7` internals; avoid assumptions about newer Cassandra APIs.
3. **No breaking CLI changes** — command names and option flags are part of the public interface; adding new options is fine, removing or renaming existing ones is not.
4. **Template Method contract** — any new command must extend the appropriate base class (`SSTableFileCmd` for file-based, `ClusterTableCmd` for cluster-based) and implement `validate()` + `execute()`.
5. **Thread safety in migration** — `MigrateUtils` and tasks run under Quartz; shared state must be thread-safe.
6. **File safety** — always use `FileUtils` locking and checksum verification when copying SSTable components; never copy partial files.
7. **Fat JAR** — all new dependencies must be compatible with shade-plugin bundling; check for conflicting `META-INF/services` entries.
8. **Tests use real SSTables** — when adding tests, prefer using existing test data in `src/test/resources/`; adding large binary files to the repo should be avoided unless necessary.
9. **Logging** — use SLF4J (`LoggerFactory.getLogger(...)`) everywhere; do not use `System.out` for operational output except for formatted console display in command classes.
10. **Chinese comments** — existing code comments are in Chinese; new comments may be in English but should be consistent within a file.
