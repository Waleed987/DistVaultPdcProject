# 🏥 DIST-VAULT — Distributed Medical Records Storage System

> A **Google File System (GFS)-inspired** distributed storage solution for medical records, built in pure Java. Files are fragmented into 64 MB chunks, encrypted with AES-256, replicated across multiple chunk servers, and managed by a single master node — all accessible through a polished Swing desktop GUI.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [High-Level Diagram](#high-level-diagram)
  - [Component Breakdown](#component-breakdown)
- [Project Structure](#project-structure)
- [Source Files](#source-files)
- [Protocol](#protocol)
- [Data Flow](#data-flow)
  - [Upload Flow](#upload-flow)
  - [Download Flow](#download-flow)
- [Security Model](#security-model)
- [Role-Based Access Control (RBAC)](#role-based-access-control-rbac)
- [Fault Tolerance & Replication](#fault-tolerance--replication)
- [Persistence](#persistence)
- [GUI Panels](#gui-panels)
- [Default Accounts](#default-accounts)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build](#build)
  - [Run (All-in-One)](#run-all-in-one)
  - [Run (Manually)](#run-manually)
- [Configuration Constants](#configuration-constants)
- [GFS Compliance](#gfs-compliance)
- [Known Limitations & Future Work](#known-limitations--future-work)

---

## Overview

**DIST-VAULT** is an offline Java desktop application that implements a distributed file system specifically tailored for medical records. It is inspired by the **Google File System (GFS)** paper (Ghemawat, Gobioff & Leung, 2003) and applies its key architectural principles:

| GFS Principle | DIST-VAULT Implementation |
|---|---|
| Single master | `MasterNode` — metadata-only, never touches data |
| Large chunk size | 64 MB per chunk (configurable via `Protocol.CHUNK_SIZE_BYTES`) |
| 3-way replication | Every chunk is stored on 3 chunk servers |
| Heartbeat monitoring | Chunk servers ping master every 30 seconds |
| Operation log | `master-oplog.txt` — append-only write-ahead log |
| Checkpointing | `master-checkpoint.dat` — periodic binary snapshot |
| Client-chunk direct I/O | Client talks directly to chunk servers for data transfer |

Medical records are fragmented, individually encrypted per patient, and transparently distributed and reassembled on retrieval — with all access events recorded to an audit log.

---

## Architecture

### High-Level Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLIENT (Swing GUI)                           │
│                                                                     │
│  LoginPanel ─► DashPanel ─► UploadPanel / SearchPanel / AdminPanel │
│                                                                     │
│   ┌────────────────────┐          ┌──────────────────────────────┐  │
│   │    MasterComm      │◄────────►│       ChunkComm              │  │
│   │ (metadata only)    │          │  (raw binary data transfer)  │  │
└───┴────────┬───────────┘──────────┴──────────────────────────────┴──┘
             │ TCP :5000                     │ TCP :5001 / :5002 / :5003
             ▼                              ▼
   ┌──────────────────────┐      ┌──────────────────────────────────────┐
   │      MasterNode      │      │            Chunk Servers             │
   │  ─────────────────── │      │  ┌───────────┐ ┌───────────┐        │
   │  • File table        │◄────►│  │  CS1:5001 │ │  CS2:5002 │  ...  │
   │  • Chunk table       │      │  │ chunks/CS1│ │ chunks/CS2│        │
   │  • Server registry   │      │  └───────────┘ └───────────┘        │
   │  • UserManager       │      │  (store .chunk files on local disk)  │
   │  • AuditLogger       │      └──────────────────────────────────────┘
   │  • Heartbeat monitor │
   │  • Checkpoint / OpLog│
   └──────────────────────┘
```

### Component Breakdown

#### 1. `MasterNode` — The Brain
The single master node is the **metadata authority** of the system. It never stores or transfers actual file data.

**Responsibilities:**
- Maintains three concurrent hash maps:
  - `fileTable` — `fileId → FileMetaData` (file namespace)
  - `chunkTable` — `chunkHandle → ChunkMetaData` (chunk locations & replicas)
  - `serverTable` — `serverId → ChunkServerInfo` (registered chunk servers)
- Allocates chunk handles (`AtomicLong`, starting at 1000)
- Picks `REPLICATION_FACTOR` (default: 3) chunk servers per chunk using a random shuffle of alive servers
- Authenticates all client requests via `UserManager` (inline, stateless per-request auth)
- Monitors chunk server health every 30 seconds in a daemon thread
- Persists full state to `master-checkpoint.dat` every 5 minutes
- Appends every mutation to `master-oplog.txt`
- Handles all system commands via `MasterWorker` threads in a `CachedThreadPool`

**Ports:** Listens on TCP **port 5000**

---

#### 2. `ChunkServer` — The Storage Worker
Multiple chunk server instances store the **actual encrypted chunk data**.

**Responsibilities:**
- Each server instance gets an integer ID (1, 2, 3, …) at launch
  - Server N listens on port `5000 + N` (CS1 → 5001, CS2 → 5002, CS3 → 5003)
  - Stores chunks in `chunks/CSn/` directory
- Registers with the master on startup via `REGISTER_CS` command
- Sends a heartbeat every 30 seconds containing its current chunk count
- Serves `STORE_CHUNK`, `GET_CHUNK`, and `LIST_CHUNKS` requests from clients
- Computes **CRC32 checksums** for every stored chunk; verifies on read to detect corruption
- Loads existing `.chunk` files on startup (supports restart without data loss)

**Ports:** TCP ports **5001, 5002, 5003** (for CS1, CS2, CS3)

---

#### 3. `Client` — The Desktop Application
A **Java Swing** GUI application that gives medical staff access to the system.

**Architecture inside the client:**
- `Client` (main `JFrame`) — manages navigation via `CardLayout`
- **`MasterComm`** — static inner class that handles all text-based TCP communication with the master. Automatically injects session credentials (`CMD|user|pass|args`) for authenticated commands.
- **`ChunkComm`** — static inner class for raw binary TCP communication with chunk servers. Handles `STORE_CHUNK` (sends data) and `GET_CHUNK` (receives data).
- Five panels managed via `CardLayout`:
  - `LoginPanel`
  - `DashPanel`
  - `UploadPanel`
  - `SearchPanel`
  - `AdminPanel`

---

#### 4. Supporting Components

| Class | Purpose |
|---|---|
| `Protocol` | All protocol constants: command strings, role names, ports, chunk size, separators |
| `EncryptionManager` | Singleton. AES-256-CBC encryption/decryption per patient. Uses `JCEKS` KeyStore (`distvault.ks`) |
| `UserManager` | Singleton. SHA-256 hashed passwords, role-based access, persisted to `users.dat` |
| `AuditLogger` | Singleton. Thread-safe CSV audit log (`audit.log`) — every login, upload, download, admin action |
| `FileMetaData` | Serializable model: fileId, fileName, fileSize, patientId, doctorId, recordType, uploadDate, keywords, chunkHandles, version |
| `ChunkMetaData` | Serializable model: chunkHandle, version, replicas list, primary replica, checksum |
| `ChunkServerInfo` | In-memory model: serverId, host, port, lastHeartbeat, chunkCount, alive status |

---

## Project Structure

```
DistVaultPdcProject/
├── src/
│   ├── Protocol.java          # Protocol constants (commands, ports, roles, separators)
│   ├── AuditLogger.java       # Thread-safe CSV audit logger
│   ├── UserManager.java       # User accounts, SHA-256 auth, RBAC helpers
│   ├── EncryptionManager.java # AES-256-CBC encryption, per-patient JCEKS KeyStore
│   ├── MasterNode.java        # Master server + FileMetaData/ChunkMetaData/ChunkServerInfo models + MasterWorker
│   ├── ChunkServer.java       # Chunk server + ChunkWorker thread
│   ├── Client.java            # Main Swing frame + LoginPanel + MasterComm/ChunkComm helpers
│   ├── DashPanel.java         # Dashboard with system stats cards
│   ├── UploadPanel.java       # File upload form with progress bar
│   ├── SearchPanel.java       # Search/browse records + download
│   └── AdminPanel.java        # Admin: user mgmt, audit log, system stats
├── chunks/
│   ├── CS1/                   # Storage directory for Chunk Server 1
│   ├── CS2/                   # Storage directory for Chunk Server 2
│   └── CS3/                   # Storage directory for Chunk Server 3
├── compile.bat                # Compiles all sources → bin/
├── run-all.bat                # One-click: compile + start master + 3 chunk servers + client
├── run-master.bat             # Start master node only
├── run-chunkserver.bat        # Start a single chunk server (pass ID as arg)
├── run-client.bat             # Start the GUI client only
├── users.dat                  # Serialized user store (auto-created on first run)
├── ProjectGuide.md            # Original project specification document
└── README.md                  # This file
```

**Auto-generated at runtime (in the project root):**
```
master-checkpoint.dat    # Binary serialized full state snapshot
master-oplog.txt         # Append-only operation log (WAL)
audit.log                # CSV audit trail of all access events
distvault.ks             # JCEKS KeyStore with per-patient AES-256 keys
```

---

## Source Files

### `Protocol.java`
Defines all shared string constants, ports, and sizes.

| Constant | Value | Purpose |
|---|---|---|
| `MASTER_PORT` | `5000` | Master server TCP port |
| `CHUNK_BASE_PORT` | `5001` | Base port; CS1=5001, CS2=5002, … |
| `CHUNK_SIZE_BYTES` | `67,108,864` (64 MB) | Max size of one chunk |
| `SEP` | `\|` | Field separator in text protocol |
| `LIST_SEP` | `;` | List separator |
| `END` | `END` | Multi-line response terminator |

### `MasterNode.java`
Contains 4 classes:
- `FileMetaData` — file record with chunk handle list
- `ChunkMetaData` — replica addresses for a single chunk
- `ChunkServerInfo` — live registration state of a chunk server
- `MasterNode` — server lifecycle, chunk allocation, heartbeat monitoring, checkpointing
- `MasterWorker` — per-connection command dispatcher

### `ChunkServer.java`
Contains 2 classes:
- `ChunkServer` — server lifecycle, registration, heartbeat sender, CRC loading
- `ChunkWorker` — handles `STORE_CHUNK`, `GET_CHUNK`, `LIST_CHUNKS` per connection

### `Client.java`
Contains 3 classes:
- `Client` — `JFrame`, `CardLayout`, global session state, shared UI factories
- `MasterComm` — text-based TCP wrapper with credential injection
- `ChunkComm` — binary TCP wrapper for chunk store/retrieve  
- `LoginPanel` — embeds login form and calls `app.afterLogin()`

---

## Protocol

The system uses a **custom, text-based TCP protocol** over UTF-8 newline-delimited messages. Fields are separated by `|`.

### Master Commands (Client → Master)

| Command | Format | Who can use | Description |
|---|---|---|---|
| `LOGIN` | `LOGIN\|user\|pass` | Everyone | Auth and get role |
| `LOGOUT` | `LOGOUT` | Everyone | No-op (session is stateless) |
| `CREATE_FILE` | `CREATE_FILE\|user\|pass\|filename\|size\|patientId\|doctorId\|type\|keywords` | DOCTOR, ADMIN | Allocate a file + chunks |
| `GET_LOCATIONS` | `GET_LOCATIONS\|user\|pass\|fileId` | All roles | Get chunk replica addresses |
| `LIST_FILES` | `LIST_FILES\|user\|pass[\|patientId]` | All roles | List file records |
| `SEARCH_FILES` | `SEARCH_FILES\|user\|pass\|keyword` | All roles | Full-text search |
| `DELETE_FILE` | `DELETE_FILE\|user\|pass\|fileId` | ADMIN only | Lazy-delete a file |
| `ADD_USER` | `ADD_USER\|user\|pass\|newUser\|newPass\|role\|patientId` | ADMIN only | Create user |
| `DELETE_USER` | `DELETE_USER\|user\|pass\|username` | ADMIN only | Remove user |
| `LIST_USERS` | `LIST_USERS\|user\|pass` | ADMIN only | Enumerate accounts |
| `GET_AUDIT_LOG` | `GET_AUDIT_LOG\|user\|pass` | ADMIN only | Last 200 audit entries |
| `GET_SYSTEM_STATS` | `GET_SYSTEM_STATS\|user\|pass` | All authenticated | files, chunks, servers, alive |

### Chunk Server Commands (Client → Chunk Server)

| Command | Format | Description |
|---|---|---|
| `STORE_CHUNK` | `STORE_CHUNK\|handle\|length\n<raw bytes>` | Write encrypted chunk to disk |
| `GET_CHUNK` | `GET_CHUNK\|handle` | Read encrypted chunk from disk (with CRC check) |
| `LIST_CHUNKS` | `LIST_CHUNKS` | List all chunk handles on this server |

### Internal Commands (Chunk Server → Master)

| Command | Format | Description |
|---|---|---|
| `REGISTER_CS` | `REGISTER_CS\|serverId\|port` | Register on startup |
| `HEARTBEAT` | `HEARTBEAT\|serverId\|chunkCount` | Periodic liveness ping |

### Response Codes

| Code | Meaning |
|---|---|
| `OK` | Success (may be followed by `\|data` or `\nlines\nEND`) |
| `ERROR` | Generic error with message |
| `DENIED` | Authorization failure |
| `END` | Terminates a multi-line response |

---

## Data Flow

### Upload Flow

```
User selects file + enters metadata
        │
        ▼
[Client] Read file bytes completely into memory
        │
        ▼
[Client → Master] CREATE_FILE|user|pass|filename|size|patientId|doctorId|type|keywords
        │
        ▼
[Master] Validates role (DOCTOR or ADMIN required)
         Calculates numChunks = ceil(fileSize / 64MB)
         For each chunk:
           Allocates unique handle (AtomicLong)
           Picks 3 alive servers randomly
           Records chunk in chunkTable
         Returns: OK|fileId|handle1:CS1,CS2,CS3|handle2:...
        │
        ▼
[Client] For each chunk i:
           Extract bytes [i*64MB … (i+1)*64MB)
           Encrypt with EncryptionManager.encrypt(patientId, chunkBytes)
             → AES-256-CBC with random IV prepended
           For each replica address:
             STORE_CHUNK|handle|length\n<encrypted bytes>  → ChunkServer
        │
        ▼
[ChunkServer] Writes .chunk file to chunks/CSn/<handle>.chunk
              Computes CRC32 and stores in checksumMap
              Responds OK|crc
        │
        ▼
[Client] Progress bar updates, final status shown
[AuditLogger] UPLOAD event recorded (user, role, filename, patientId, timestamp)
```

### Download Flow

```
User selects a record from search results
        │
        ▼
[Client → Master] GET_LOCATIONS|user|pass|fileId
        │
        ▼
[Master] Checks access (PATIENT role: only own records)
         Returns: OK|fileId|handle1:CS1,CS2,CS3|handle2:...
        │
        ▼
[Client] For each chunk:
           Try each replica address in order until one succeeds
           GET_CHUNK|handle → ChunkServer
[ChunkServer] Verifies CRC32 (rejects if corrupted)
              Responds OK|size|crc\n<encrypted bytes>
        │
        ▼
[Client] Decrypts each chunk: EncryptionManager.decrypt(patientId, encryptedBytes)
           Extract IV (first 16 bytes) + ciphertext → AES-256-CBC decrypt
         Concatenates decrypted chunks in order
         Writes to user-chosen save path
         Optionally opens the file with the OS default application
[AuditLogger] GET_LOCATIONS event recorded
```

---

## Security Model

### Encryption
- **Algorithm:** AES-256-CBC with PKCS5 padding
- **Key storage:** Java JCEKS KeyStore (`distvault.ks`), protected by a hardcoded passphrase
- **Key granularity:** One unique 256-bit AES key per `patientId`; auto-generated on first upload for that patient
- **IV:** 16 random bytes generated fresh for every encryption operation; prepended to the ciphertext
- **On disk:** Chunk files (`*.chunk`) contain only `IV + AES-256-CBC(chunk_data)` — no plaintext is ever written

### Authentication
- Passwords are **SHA-256 hashed** before storage in `users.dat`
- Every authenticated command carries `user|pass` inline so the master can validate each request independently (stateless per-connection model)
- Failed login attempts are recorded in the audit log

### Audit Logging
Every operation writes a CSV row to `audit.log`:
```
timestamp, user, role, operation, resource, result, details
```
Operations tracked: `LOGIN`, `LOGOUT`, `UPLOAD`, `GET_LOCATIONS`, `DELETE`, `ADD_USER`, `DELETE_USER`, `SERVER_FAILURE`

---

## Role-Based Access Control (RBAC)

| Role | Upload | Download | Search | List All | Admin Panel | Own Records Only |
|---|---|---|---|---|---|---|
| `DOCTOR` | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| `NURSE` | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ |
| `ADMIN` | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `PATIENT` | ❌ | ✅ | ✅ | ✅ (filtered) | ❌ | ✅ |

- **DOCTOR / ADMIN** can call `CREATE_FILE` (write access)
- **NURSE** can download and search but cannot upload
- **PATIENT** can only see records where `patientId` matches their linked `patientId` in `users.dat`
- **ADMIN** exclusively can: add/delete users, view audit logs, view system stats in Admin Panel, delete files

---

## Fault Tolerance & Replication

### Replication
- `REPLICATION_FACTOR = 3` (hardcoded in `MasterNode`)
- On every `CREATE_FILE`, the master randomly selects 3 alive chunk servers and assigns them as replicas
- The client uploads the same encrypted chunk bytes to **all 3 replicas** in sequence
- On download, the client tries each replica in order, falling back to the next if one fails

### Heartbeat & Failure Detection
- Each chunk server sends `HEARTBEAT|serverId|chunkCount` to the master every **30 seconds**
- The master's `HeartbeatMonitor` daemon thread checks every 30 seconds whether any server's `lastHeartbeat` exceeds **90 seconds** (3 missed heartbeats)
- A server that misses 3 heartbeats is marked `alive = false`
- Dead servers are excluded from chunk allocation
- A `SERVER_FAILURE` audit event is logged when a server transitions from alive → dead

> **Note:** Automatic re-replication to replace chunks from a failed server is not yet implemented. The system relies on the existing replicas on surviving servers.

### CRC32 Integrity Checks
- Every chunk gets a CRC32 checksum computed at store time and kept in memory
- On `GET_CHUNK`, the stored CRC is compared to the freshly computed CRC of the file on disk
- A mismatch returns an `ERROR` response so the client can try the next replica

---

## Persistence

| File | Format | Contents | When saved |
|---|---|---|---|
| `master-checkpoint.dat` | Java binary serialization | Full `fileTable`, `chunkTable`, counter values | Every 5 minutes (daemon thread) |
| `master-oplog.txt` | Plain text (one op per line) | Timestamped operation journal (WAL) | Every mutation |
| `users.dat` | Java binary serialization | `HashMap<String, UserRecord>` | On every user add/remove |
| `audit.log` | CSV | Timestamped access events | Every auditable operation |
| `distvault.ks` | JCEKS KeyStore | AES-256 keys keyed by `patient-<patientId>` alias | When a new patient key is generated |
| `chunks/CSn/<handle>.chunk` | Raw binary | `IV (16 bytes) + AES-256 ciphertext` | Every `STORE_CHUNK` operation |

The master loads `master-checkpoint.dat` on startup and restores `fileTable`, `chunkTable`, and the counter values, giving it full knowledge of all prior uploads even after a restart.

---

## GUI Panels

### Login Panel
- App title with hospital emoji
- Username / password fields
- Quick-reference default account hint grid
- Calls `LOGIN` command on master; stores session credentials in `Client.currentUser` / `Client.currentPassword` / `Client.currentRole`

### Dashboard (`DashPanel`)
- Left sidebar with navigation buttons (Admin Panel button only visible to `ADMIN` role)
- Four real-time stat cards: Total Files, Chunks, Servers, Alive Servers (fetched from `GET_SYSTEM_STATS`)
- About panel with system description
- Refresh button

### Upload Panel (`UploadPanel`)
- File browser dialog (any file type, any size)
- Metadata form: Patient ID, Doctor/Uploader ID, Record Type (dropdown), Keywords
- Progress bar (updates per-chunk at 5 → 15 → 25 → 95 → 100%)
- Background upload via `SwingWorker` (non-blocking UI)
- Role guard: only `DOCTOR` and `ADMIN` can proceed

### Search Panel (`SearchPanel`)
- Keyword search field → calls `SEARCH_FILES`
- Patient ID filter field → calls `LIST_FILES|patientId`
- Results shown in a dark-themed `JTable` with columns: File ID, Name, Size, Patient ID, Doctor ID, Type, Date, Keywords, Chunks
- "Download & View Selected" button → retrieves chunks, decrypts, reassembles, saves to disk, optionally opens with OS

### Admin Panel (`AdminPanel`)
- Tabbed interface (only shown to `ADMIN` role):
  - **Users** tab: table of all users with Add/Delete functionality via dialogs; calls `LIST_USERS`, `ADD_USER`, `DELETE_USER`
  - **Audit Log** tab: scrollable monospace text area showing last 200 audit entries; calls `GET_AUDIT_LOG`
  - **System Stats** tab: formatted display of `GET_SYSTEM_STATS` response

---

## Default Accounts

These accounts are created automatically on first run (when `users.dat` is empty):

| Username | Password | Role | Patient ID |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | — |
| `doctor1` | `doctor123` | DOCTOR | — |
| `nurse1` | `nurse123` | NURSE | — |
| `patient1` | `patient123` | PATIENT | `P001` |

> Passwords are stored as SHA-256 hashes in `users.dat`.

---

## Getting Started

### Prerequisites

- **Java 17+** (or Java 11+ minimum — `readAllBytes()` and `var` patterns used)
- No external dependencies — pure Java SE (Swing, JCE, standard sockets)

### Build

```bat
compile.bat
```

This runs:
```bat
javac -d bin -sourcepath src src\Protocol.java src\AuditLogger.java src\UserManager.java src\EncryptionManager.java src\MasterNode.java src\ChunkServer.java src\DashPanel.java src\UploadPanel.java src\SearchPanel.java src\AdminPanel.java src\Client.java
```

Compiled `.class` files are placed in `bin/`.

### Run (All-in-One)

```bat
run-all.bat
```

This single script:
1. Compiles all sources
2. Opens a new console window running `MasterNode`
3. Waits 3 seconds, then opens 3 console windows running `ChunkServer 1`, `ChunkServer 2`, `ChunkServer 3`
4. Waits 3 seconds, then opens the `Client` GUI

### Run (Manually)

**Step 1 — Start Master Node:**
```bat
run-master.bat
```
or:
```bat
java -cp bin MasterNode
```

**Step 2 — Start Chunk Servers** (run each in a separate terminal):
```bat
java -cp bin ChunkServer 1
java -cp bin ChunkServer 2
java -cp bin ChunkServer 3
```

**Step 3 — Start Client GUI:**
```bat
run-client.bat
```
or:
```bat
java -cp bin Client
```

> The master **must** be started before chunk servers so they can register. At least **one** chunk server must be running before any upload.

---

## Configuration Constants

All key system parameters are defined as constants in `Protocol.java` and `MasterNode.java`. To change them, edit the constants and recompile.

| Parameter | Location | Default | Description |
|---|---|---|---|
| Master port | `Protocol.MASTER_PORT` | `5000` | TCP port for master node |
| Chunk base port | `Protocol.CHUNK_BASE_PORT` | `5001` | First chunk server port |
| Chunk size | `Protocol.CHUNK_SIZE_BYTES` | `67,108,864` (64 MB) | Max bytes per chunk |
| Replication factor | `MasterNode.REPLICATION_FACTOR` | `3` | Replicas per chunk |
| Heartbeat timeout | `MasterNode.HEARTBEAT_TIMEOUT_MS` | `90,000` ms | When to mark server as dead |
| Heartbeat interval | `ChunkServer.heartbeatLoop()` | `30,000` ms | How often CS pings master |
| Checkpoint interval | `MasterNode.main()` | `300,000` ms | How often master snapshots |

---

## GFS Compliance

| GFS Design Principle | Implementation Status |
|---|---|
| **Single master for metadata** | ✅ — `MasterNode` handles all metadata; never relays data |
| **Large 64 MB chunks** | ✅ — `Protocol.CHUNK_SIZE_BYTES = 64 * 1024 * 1024` |
| **Client-to-chunk-server direct I/O** | ✅ — `ChunkComm` bypasses the master for data |
| **Chunk replication (3-way)** | ✅ — master assigns 3 replicas; client writes all 3 |
| **Heartbeat monitoring** | ✅ — 30s interval, 90s timeout |
| **Operation log (WAL)** | ✅ — `master-oplog.txt` append-only |
| **Checkpointing** | ✅ — Binary serialized snapshot every 5 minutes |
| **Chunk version numbering** | ⚠️ — Field exists in `ChunkMetaData.version` but not actively incremented on writes |
| **Lease mechanism** | ❌ — Not implemented (primary is replica[0] by default) |
| **Re-replication on failure** | ❌ — Detected but not acted upon automatically |
| **Garbage collection of deleted chunks** | ❌ — Master metadata removed; chunk files remain on disk (lazy GC noted in code) |
| **Shadow masters** | ❌ — Not implemented |
| **Atomic record append** | ⚠️ — Append-only metaphor applied at the file level; not GFS-style concurrent append |

---

## Known Limitations & Future Work

1. **No automatic re-replication** — If a chunk server dies permanently, its chunks are not automatically re-replicated to a surviving server.
2. **Lazy GC for deleted chunk files** — `DELETE_FILE` removes master metadata but the actual `.chunk` files on disk remain.
3. **In-memory only for chunk server checksums** — The `checksumMap` is rebuilt from CRC computation on restart, not persisted separately.
4. **No TLS** — All socket communication is plaintext TCP. Adding SSL/TLS is recommended for any non-local deployment.
5. **Keystore passphrase is hardcoded** — `EncryptionManager.KS_PASSWORD` should be externalized to a config file or environment variable.
6. **No chunk version enforcement** — The `version` field in `ChunkMetaData` is set to 1 and never updated; stale replica detection via version comparison is not active.
7. **Single JVM per process** — The master, chunk servers, and client all run as separate JVM processes. Embedding into a single deployable JAR with process isolation is a potential improvement.
8. **No concurrent append semantics** — GFS-style atomic concurrent record append is not implemented.

---

## License

This project was developed as an academic assignment demonstrating distributed systems concepts (Parallel & Distributed Computing course). Not intended for production medical use.

---

*Built with ❤️ in Java — no external frameworks, just sockets, Swing, and JCE.*
