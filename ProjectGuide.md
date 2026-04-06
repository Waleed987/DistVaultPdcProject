# DIST-VAULT: Distributed Medical Records Storage System
## Project Brief for Antigravity

---

## PROJECT OVERVIEW

Create a Java-based distributed storage system called **DIST-VAULT** that fragments and stores medical records across multiple nodes while ensuring data privacy. The system should be built as an offline desktop application following Google File System (GFS) architecture principles.

---

## CORE REQUIREMENTS

### 1. System Architecture (Based on GFS)

**Master Server Component:**
- Single master node that maintains all file system metadata
- Tracks chunk locations across chunk servers
- Manages namespace (file and directory hierarchy)
- Handles chunk lease management
- Coordinates system-wide activities like chunk migration and garbage collection
- Maintains operation log and checkpoints for fault tolerance
- Should NOT handle actual data transfers (only metadata)

**Chunk Server Component:**
- Multiple chunk servers that store actual data chunks
- Each chunk should be 64MB in size (GFS standard)
- Store chunks as plain Linux files on local disk
- Support chunk replication (default: 3 replicas per chunk)
- Report heartbeat and chunk inventory to master
- Handle read/write requests directly from clients
- Perform chunk integrity checks using checksums

**Client Library:**
- Desktop application interface for medical staff
- Communicates with master for metadata operations
- Communicates directly with chunk servers for data transfers
- Implements caching of metadata
- Handles chunk location caching
- Supports append operations (medical records are append-only)

### 2. Data Privacy & Security

**Encryption:**
- Implement AES-256 encryption for all medical record chunks
- Use separate encryption keys for each patient's records
- Implement key management system with secure key storage
- Support key rotation without re-encrypting entire datasets

**Access Control:**
- Role-based access control (RBAC):
  - Doctor (full read/write)
  - Nurse (read-only)
  - Administrator (system management)
  - Patient (read own records)
- Implement audit logging for all access attempts
- Track who accessed what data and when

**Data Fragmentation:**
- Fragment each medical record into multiple chunks
- Distribute chunks across different chunk servers
- No single server should have a complete medical record
- Implement chunk naming that doesn't reveal patient information

### 3. Key Features

**Medical Record Management:**
- Support CRUD operations for medical records
- Handle various formats: text notes, PDFs, images (X-rays, scans)
- Maintain version history of records
- Support append-only operations (medical records shouldn't be deleted, only amended)
- Metadata should include: patient ID, doctor ID, timestamp, record type, keywords

**Distribution & Replication:**
- Automatic chunk distribution across available chunk servers
- 3-way replication by default (configurable)
- Re-replication when chunk servers fail
- Load balancing based on server capacity and network proximity

**Fault Tolerance:**
- Master server should maintain operation log and periodic checkpoints
- Automatic failover using shadow masters (optional enhancement)
- Chunk server failure detection via heartbeat
- Automatic chunk re-replication on server failure
- Corrupted chunk detection and recovery

**Consistency Model:**
- Relaxed consistency model (similar to GFS)
- Support concurrent appends from multiple clients
- Guarantee atomic append operations
- Handle stale replica detection

---

## TECHNICAL SPECIFICATIONS

### Technology Stack

**Core Framework:**
- Java 17 or higher
- JavaFX or Swing for desktop GUI
- Apache Maven or Gradle for build management

**Networking:**
- Java Sockets for client-master and client-chunk server communication
- Implement custom RPC protocol or use gRPC
- Support for both TCP and UDP where appropriate

**Storage:**
- Local file system for chunk storage
- SQLite or H2 database for master metadata
- File-based operation logs for master

**Security Libraries:**
- Java Cryptography Extension (JCE) for encryption
- Bouncy Castle for advanced cryptographic operations
- Java KeyStore for key management

**Additional Libraries:**
- Apache Commons IO for file operations
- SLF4J with Logback for logging
- JUnit 5 for testing

### System Components to Implement

**1. Master Server (`MasterServer.java`)**
```
Classes needed:
- MasterServer: Main server class
- NamespaceManager: File/directory hierarchy management
- ChunkManager: Chunk location tracking and lease management
- MetadataStore: Persistent storage for metadata
- OperationLog: Write-ahead log for operations
- CheckpointManager: Periodic state snapshots
- HeartbeatMonitor: Monitor chunk server health
```

**2. Chunk Server (`ChunkServer.java`)**
```
Classes needed:
- ChunkServer: Main chunk server class
- ChunkStorage: Local chunk file management
- ChecksumValidator: Chunk integrity verification
- ReplicationManager: Handle chunk replication
- HeartbeatSender: Report status to master
```

**3. Client Application (`DistVaultClient.java`)**
```
Classes needed:
- ClientGUI: Desktop interface (JavaFX/Swing)
- MasterClient: Communication with master
- ChunkServerClient: Direct data transfer with chunk servers
- MetadataCache: Cache file locations and metadata
- RecordManager: Medical record operations
- EncryptionManager: Handle encryption/decryption
- AuthenticationManager: User login and access control
```

**4. Data Models**
```
- MedicalRecord: Patient record structure
- ChunkInfo: Chunk metadata
- FileMetadata: File system metadata
- UserCredentials: Authentication data
- AuditLog: Access logging
```

### Configuration Files

**master-config.properties:**
```
master.port=9000
master.checkpoint.interval=600000
master.chunk.size=67108864
master.replication.factor=3
master.metadata.db.path=/var/distvault/master/metadata.db
master.operation.log.path=/var/distvault/master/oplog
```

**chunkserver-config.properties:**
```
chunkserver.port=9001
chunkserver.storage.path=/var/distvault/chunks
chunkserver.master.host=localhost
chunkserver.master.port=9000
chunkserver.heartbeat.interval=30000
chunkserver.max.storage=100GB
```

**client-config.properties:**
```
client.master.host=localhost
client.master.port=9000
client.cache.ttl=300000
client.encryption.algorithm=AES256
```

---

## FUNCTIONAL REQUIREMENTS

### Medical Record Operations

**1. Upload Medical Record:**
- User selects file or enters text
- System fragments file into 64MB chunks
- Each chunk is encrypted with patient-specific key
- Chunks are distributed to chunk servers (3 replicas each)
- Master updates metadata and returns file handle to client
- Audit log records the upload

**2. Retrieve Medical Record:**
- User searches by patient ID, date range, or keywords
- Client queries master for chunk locations
- Client contacts chunk servers directly for chunks
- Chunks are downloaded, decrypted, and reassembled
- Audit log records the access

**3. Append to Medical Record:**
- Support atomic append operations
- New data is appended as new chunks
- Maintain version history
- Update metadata with append timestamp

**4. Search Medical Records:**
- Search by patient ID, doctor ID, date range, keywords
- Master maintains searchable index
- Return list of matching records

### System Administration

**1. Add/Remove Chunk Servers:**
- Dynamic addition of chunk servers
- Automatic chunk migration on server removal
- Re-balancing of chunks across servers

**2. Monitor System Status:**
- Dashboard showing:
  - Available storage per chunk server
  - Number of chunks stored
  - Replication status
  - Active connections
  - System health metrics

**3. User Management:**
- Create/delete users
- Assign roles and permissions
- Reset passwords
- View audit logs

---

## NON-FUNCTIONAL REQUIREMENTS

### Performance
- Support at least 10 concurrent clients
- Chunk upload speed: minimize by using parallel transfers
- Chunk download speed: leverage multiple chunk servers
- Master metadata operations: < 100ms latency
- Support files up to 10GB

### Scalability
- Support up to 100 chunk servers
- Handle millions of chunks
- Store petabytes of data (theoretically)

### Reliability
- 99.9% uptime for chunk servers
- Automatic recovery from single chunk server failure
- No data loss on single server failure
- Master checkpoint every 10 minutes

### Security
- All data encrypted at rest
- Secure communication channels (SSL/TLS optional)
- No plaintext storage of patient data
- Audit trail for all operations
- HIPAA compliance considerations (basic)

### Usability
- Intuitive desktop GUI
- Clear error messages
- Progress indicators for long operations
- Comprehensive documentation

---

## DEVELOPMENT PHASES

### Phase 1: Core Infrastructure (Weeks 1-2)
- Basic master server with metadata management
- Simple chunk server with local storage
- Basic client-master-chunk server communication
- Simple file fragmentation and reassembly

### Phase 2: GFS Features (Weeks 3-4)
- Chunk replication (3-way)
- Heartbeat and failure detection
- Chunk re-replication on failure
- Operation log and checkpoints
- Namespace management

### Phase 3: Security & Privacy (Weeks 5-6)
- AES-256 encryption implementation
- Key management system
- Access control and authentication
- Audit logging

### Phase 4: Desktop Application (Weeks 7-8)
- JavaFX/Swing GUI development
- Medical record upload/download interface
- Search and browse functionality
- Admin dashboard

### Phase 5: Testing & Optimization (Weeks 9-10)
- Unit testing (JUnit)
- Integration testing
- Performance testing and optimization
- Security testing
- Bug fixes

### Phase 6: Documentation & Deployment (Weeks 11-12)
- User manual
- System administration guide
- API documentation
- Deployment scripts
- Training materials

---

## GFS ARCHITECTURE COMPLIANCE

Ensure the system follows these GFS principles:

1. **Single Master Design:**
   - One master maintains all metadata
   - Clients interact with master only for metadata
   - Data flows directly between clients and chunk servers

2. **Large Chunk Size:**
   - 64MB chunks reduce master metadata overhead
   - Reduces client-master interactions
   - Efficient for large medical imaging files

3. **Append-Only Semantics:**
   - Medical records are primarily appended, not overwritten
   - Supports concurrent appends
   - Record append is atomic

4. **Relaxed Consistency:**
   - Chunks may be temporarily inconsistent across replicas
   - Eventual consistency is acceptable
   - Stale replica detection mechanisms

5. **No Client Caching of Data:**
   - Client caches metadata only
   - Data is read directly from chunk servers
   - Ensures data freshness

6. **Chunk Replica Placement:**
   - Spread replicas across different chunk servers
   - Maximize availability and fault tolerance

---

## DELIVERABLES

1. **Source Code:**
   - Complete Java source code
   - Maven/Gradle build files
   - Configuration files

2. **Executable Application:**
   - Compiled JAR files for master, chunk server, and client
   - Launcher scripts for Windows and Linux

3. **Documentation:**
   - Architecture design document
   - API documentation (JavaDoc)
   - User manual with screenshots
   - Administrator guide
   - Installation guide

4. **Test Suite:**
   - Unit tests for all major components
   - Integration tests
   - Test data and scenarios

5. **Sample Data:**
   - Sample medical records
   - Test user accounts
   - Configuration examples

---

## SAMPLE USE CASE WORKFLOW

**Scenario: Doctor uploads patient X-ray**

1. Doctor logs into DIST-VAULT desktop app
2. Selects "Upload Medical Record"
3. Chooses X-ray image file (5MB)
4. Enters metadata: Patient ID, Record Type: "X-Ray Chest", Date
5. Client application:
   - Fragments file into 1 chunk (< 64MB)
   - Encrypts chunk with patient's encryption key
   - Contacts master for chunk server assignments
   - Uploads encrypted chunk to 3 chunk servers in parallel
6. Master updates metadata with chunk locations
7. System records audit log entry
8. Doctor receives confirmation

**Scenario: Nurse retrieves patient history**

1. Nurse logs into DIST-VAULT desktop app
2. Searches for Patient ID
3. System displays list of all records for patient
4. Nurse selects record to view
5. Client application:
   - Queries master for chunk locations
   - Downloads chunks from nearest chunk servers
   - Decrypts chunks
   - Reassembles and displays the record
6. System records audit log entry

---

## TESTING REQUIREMENTS

1. **Unit Tests:**
   - Test each component in isolation
   - Mock dependencies
   - Coverage: minimum 70%

2. **Integration Tests:**
   - Test master-chunk server interaction
   - Test client-master-chunk server workflow
   - Test replication and failure scenarios

3. **Performance Tests:**
   - Test with 10 concurrent clients
   - Measure upload/download speeds
   - Test with large files (1GB, 5GB)

4. **Security Tests:**
   - Verify encryption strength
   - Test access control enforcement
   - Attempt unauthorized access

5. **Failure Tests:**
   - Simulate chunk server crashes
   - Test master recovery from checkpoints
   - Test network partition scenarios

---

## ADDITIONAL NOTES

- Follow Java coding conventions and best practices
- Use meaningful variable and method names
- Comment complex algorithms
- Implement proper exception handling
- Use design patterns where appropriate (Singleton for Master, Factory for chunk creation, etc.)
- Consider thread safety for concurrent operations
- Implement proper logging at all levels (DEBUG, INFO, WARN, ERROR)
- Make the system configurable via properties files
- Support graceful shutdown of all components

---

## REFERENCE MATERIALS

Study the original Google File System paper:
- "The Google File System" by Ghemawat, Gobioff, and Leung (2003)
- Focus on: architecture, chunk management, consistency model, fault tolerance

Key GFS concepts to implement:
- Chunk handle and chunk version
- Lease mechanism for mutations
- Record append semantics
- Snapshot and namespace operations
- Garbage collection of deleted chunks

---

## SUCCESS CRITERIA

The project will be considered successful when:

1. ✅ Medical records can be uploaded, fragmented, encrypted, and distributed across chunk servers
2. ✅ Records can be retrieved, decrypted, and reassembled correctly
3. ✅ System survives single chunk server failure without data loss
4. ✅ Access control prevents unauthorized access to records
5. ✅ Audit logs track all system access
6. ✅ Desktop GUI is functional and user-friendly
7. ✅ All core GFS principles are implemented
8. ✅ System can handle at least 10 concurrent users
9. ✅ Documentation is complete and clear
10. ✅ Test coverage is adequate (>70%)

---

**END OF PROJECT BRIEF**