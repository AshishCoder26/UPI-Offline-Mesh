# UPI Offline Mesh Project - Flow Explanation

## Project Overview

**"UPI Offline Mesh"** is a Spring Boot demonstration of **offline payment routing through a Bluetooth mesh network**. It proves three key concepts:

1. **End-to-End Encryption** - Payments encrypted with RSA/AES can route through untrusted devices without exposure
2. **Idempotency** - The same payment can arrive multiple times but settles exactly once
3. **Tamper Detection** - Invalid or replayed packets are rejected before touching the ledger

---

## The Real-World Problem This Solves

Imagine you're in a basement with **zero internet connectivity**. You want to send ₹500 to your friend. 

**Current solution:** You can't. You're offline.

**This project's solution:** 
- Your phone encrypts the payment locally
- It broadcasts to nearby phones (Bluetooth mesh)
- The packet hops device-to-device: `phone1 → phone2 → phone3 → phone4`
- When ANY device walks outside and gets 4G, it uploads the batch of packets it's been carrying
- The backend decrypts, validates, and settles them silently
- Money moves even though the sender was offline the whole time

---

## Architecture Layers

### 1. **Frontend Layer** (Dashboard)
- **File:** `dashboard.html` + `dashboard.css`
- **Purpose:** Interactive UI to simulate the entire flow
- **Technology:** Thymeleaf template, real-time updates via JavaScript

### 2. **REST API Layer** (Controllers)
- **File:** `ApiController.java`, `DashboardController.java`
- **Endpoints:**
  - `/api/demo/send` - Simulate sender creating encrypted packet
  - `/api/mesh/gossip` - Run one round of packet spreading
  - `/api/mesh/flush` - Simulate bridge node uploading packets
  - `/api/bridge/ingest` - **REAL** endpoint for production bridge nodes
  - `/api/accounts`, `/api/transactions` - Dashboard data

### 3. **Service Layer** (Business Logic)
- **DemoService** - Seeds accounts, creates encrypted packets
- **MeshSimulatorService** - Simulates Bluetooth mesh network behavior
- **BridgeIngestionService** - Validates and processes inbound packets
- **SettlementService** - Executes account debits/credits
- **IdempotencyService** - Prevents duplicate processing

### 4. **Crypto Layer** (Security)
- **File:** `HybridCryptoService.java`, `ServerKeyHolder.java`
- **Encryption:** Hybrid RSA-2048 (key encryption) + AES-256-GCM (data)
- **Hashing:** SHA-256 for idempotency fingerprinting

### 5. **Data Layer** (Persistence)
- **Database:** H2 in-memory (dev/demo only)
- **Entities:**
  - `Account` - Ledger of user balances
  - `Transaction` - Immutable settlement records
  - `MeshPacket` - In-flight encrypted data (in-memory)

---

## Step-by-Step Flow Explanation

### **PHASE 1: Payment Creation (Sender Offline)**

```
User Action: Click "📤 Inject into Mesh"
              Select: Alice → Bob, ₹500, PIN: 1234
                     ↓
         [DemoService.createPacket()]
                     ↓
         1. Build PaymentInstruction
            {
              senderVpa: "alice@demo",
              receiverVpa: "bob@demo",
              amount: 500.00,
              pinHash: sha256("1234"),
              nonce: UUID (uniqueness),
              signedAt: Instant.now() (freshness)
            }
                     ↓
         2. Encrypt with Server's RSA Public Key
            [HybridCryptoService.encrypt()]
            - Generate random AES-256 session key
            - Encrypt instruction with AES-GCM
            - Encrypt session key with RSA-OAEP
            - Result: ciphertext (base64)
                     ↓
         3. Wrap in MeshPacket
            {
              packetId: UUID,
              ttl: 5 (max hops),
              createdAt: Instant.now(),
              ciphertext: "<encrypted blob>"
            }
                     ↓
         4. Inject into Virtual Device
            [MeshSimulatorService.inject()]
            mesh.devices.get("phone-alice").hold(packet)
                     ↓
         ✅ Dashboard shows:
            "phone-alice" now holds 1 packet
```

---

### **PHASE 2: Mesh Propagation (Gossip)**

```
User Action: Click "🔄 Run Gossip Round" (2-3 times)
                     ↓
         [MeshSimulatorService.gossipOnce()]
                     ↓
         Each online device shares its packets with every other device:
         
         Round 1:  phone-alice (1 pkt) → [bridge, stranger1, stranger2, stranger3]
                   Each receives a copy. TTL decrements: 5→4
                   Result: 4 transfers
         
         Round 2:  Now 4 devices have copies, each shares with others
                   Transfers: 12 (avoiding re-sharing same packet twice)
         
         Round 3:  All 5 devices now hold the same packet
                   The mesh is "flooded" with the payment
                     ↓
         ✅ Dashboard shows:
            - Device packet counts update
            - Activity log: "Gossip: 4 transfers"
            - All devices now show 1 packet in their hold queue
```

---

### **PHASE 3: Bridge Upload (Device Gets Connectivity)**

```
User Action: Click "📡 Bridges Upload"
                     ↓
         [MeshSimulatorService.collectBridgeUploads()]
                     ↓
         Find all devices marked as "hasInternet" (only "phone-bridge")
         Collect their held packets
         Result: List<BridgeUpload> = [
                   { bridgeNodeId: "phone-bridge", packet: [...] }
                 ]
                     ↓
         For each collected packet:
         [BridgeIngestionService.ingest(packet, "phone-bridge", hopCount)]
         
         ────────────────────────────────────────────────────────
         CRITICAL SECURITY PIPELINE:
         ────────────────────────────────────────────────────────
         
         Step 1: Hash the ciphertext
                 packetHash = SHA-256(ciphertext)
                 
         Step 2: **IDEMPOTENCY GATE**
                 if (idempotencyCache.claim(packetHash)) {
                    // First time seeing this packet hash
                    // Reserve it atomically
                 } else {
                    // DUPLICATE! Already processed before
                    log.info("DUPLICATE_DROPPED")
                    return
                 }
         
         Step 3: Decrypt the ciphertext
                 PaymentInstruction = decrypt(ciphertext)
                 ↓
                 {
                   senderVpa: "alice@demo",
                   receiverVpa: "bob@demo",
                   amount: 500.00,
                   pinHash: "abc123...",
                   nonce: "xyz...",
                   signedAt: 1630000000000
                 }
                 
         Step 4: Freshness Check (Replay Protection)
                 ageSeconds = (now - instruction.signedAt) / 1000
                 if (ageSeconds > 86400) → REJECT (older than 1 day)
                 if (ageSeconds < -300)  → REJECT (future dated, clock skew)
         
         Step 5: Settlement
                 [SettlementService.settle()]
                 - Debit alice@demo by ₹500
                 - Credit bob@demo by ₹500
                 - Create immutable Transaction record
                 - Commit to DB
                     ↓
         ✅ Dashboard shows:
            - "✅ SETTLED" badge
            - Transaction appears in ledger
            - Balances updated:
              Alice: 5000 → 4500
              Bob:   1000 → 1500
            - Activity log: "📡 SETTLED alice@demo → bob@demo ₹500"
```

---

## Key Design Decisions

### 1. **Hybrid Encryption (RSA + AES)**

```
Why not just RSA?
  ❌ RSA can only encrypt ~245 bytes (2048-bit key)
  ❌ Including metadata, we have >245 bytes
  ✅ Solution: Hybrid approach

How it works:
  1. Generate random AES-256 session key
  2. Encrypt payment instruction with AES-GCM → 256 bytes ciphertext
  3. Encrypt AES key with RSA-OAEP → ~256 bytes ciphertext
  4. Combine: [RSA_encrypted_AES_key] + [AES_encrypted_instruction]
  5. Wrap in base64 for transport

Why AES-GCM?
  - Authenticated encryption (detects tampering)
  - Nonce per encryption (IVs never repeat)
  - Modern best practice
```

### 2. **Idempotency via Ciphertext Hash**

```
Problem: Same packet might arrive via multiple paths
  Path A: phone1 → bridge1 (at 10:30:05)
  Path B: phone1 → phone2 → phone3 → bridge2 (at 10:30:08)
  
  If processed twice:
    ❌ Alice debited ₹500 twice
    ❌ Bob credited ₹500 twice
    ❌ Data inconsistency

Solution: Hash-based claiming
  1. Hash ciphertext → fingerprint (SHA-256)
  2. Try to claim: cache.putIfAbsent(hash, claimed)
  3. If already claimed → DUPLICATE_DROPPED
  4. If fresh → proceed with settlement
  
Defense in depth:
  - In-memory cache (fast, for concurrent requests)
  - DB unique constraint on packetHash (persists across restarts)
```

### 3. **Freshness Check (Replay Protection)**

```
Problem: Attacker intercepts packet, replays it later
  - Original: 2025-01-01, Alice → Bob ₹500
  - Replay: 2025-12-01, same packet replayed
  
  If not checked:
    ❌ Bob gets ₹500 again (double spending!)

Solution: Timestamp validation
  - PaymentInstruction includes signedAt (when sender encrypted it)
  - Backend rejects if too old (> 86400 seconds = 24 hours)
  - Allows clock skew tolerance (±300 seconds)
```

### 4. **Virtual Mesh Simulator**

```
Why not use real Bluetooth?
  - Requires hardware
  - Hard to test on a laptop
  - Slow to demonstrate

Solution: In-memory simulation
  - VirtualDevice = mock phone (holds packets, has internet flag)
  - Gossip = broadcast all packets to all peers (conceptually BLE mesh)
  - TTL = prevents infinite loops
  - Bridge node = device with hasInternet=true

Limitations:
  ⚠️  All devices "hear" each other instantly (real BLE has range)
  ⚠️  No packet loss simulation
  ⚠️  No congestion/latency
  ✅  Good for demonstrating core concepts
```

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     USER INTERACTION (Dashboard)                    │
└────────────────────────────────────┬────────────────────────────────┘
                                     │
        ┌────────────────────────────┼────────────────────────────────┐
        │                            │                                │
        ▼                            ▼                                ▼
   [Send Flow]              [Gossip Flow]                      [Bridge Upload]
        │                            │                                │
    DemoService              MeshSimulatorService          BridgeIngestionService
        │                            │                                │
    createPacket()              gossipOnce()                  ingest()
        │                            │                                │
   Encrypt with                Spread packets                Hash → Claim
   Server's Public Key         device-to-device             │
        │                            │                       ├─→ Decrypt
        │                            │                       │
        │                            │                       ├─→ Freshness Check
        │                            │                       │
        │                            │                       ├─→ Settlement
        │                            │                       │
        └────────┬───────────────────┴───────────┬───────────┘
                 │                               │
                 ▼                               ▼
        ┌─────────────────────────────────────────────┐
        │     Virtual Mesh (In-Memory Devices)        │
        │  [phone-alice] [phone-bridge] [stranger1]   │
        │      Holds packets, simulates routing       │
        └──────────────────┬──────────────────────────┘
                           │
                           ▼
        ┌─────────────────────────────────────────────┐
        │        Persistent Storage (H2/DB)           │
        │  ┌──────────────┐     ┌──────────────┐     │
        │  │  Accounts    │     │ Transactions │     │
        │  │  (Ledger)    │     │ (Immutable)  │     │
        │  │              │     │              │     │
        │  │ Alice: 4500  │     │ ID: 1        │     │
        │  │ Bob: 1500    │     │ Status:      │     │
        │  │ Carol: 2500  │     │ SETTLED ✅   │     │
        │  │ Dave: 500    │     │              │     │
        │  └──────────────┘     └──────────────┘     │
        │                                             │
        │  packetHash (UNIQUE constraint)             │
        │  (Defense against duplicates)               │
        └─────────────────────────────────────────────┘
                           │
                           ▼
        ┌─────────────────────────────────────────────┐
        │     Dashboard displays results              │
        │     - Balances updated ✅                   │
        │     - Transaction ledger ✅                 │
        │     - Activity log ✅                       │
        └─────────────────────────────────────────────┘
```

---

## The Three Security Challenges Solved

| Challenge | Problem | Solution |
|-----------|---------|----------|
| **Privacy** | Bridge nodes, mesh devices shouldn't see payment details | End-to-end encryption (RSA+AES). Only server can decrypt with its private key |
| **Idempotency** | Same packet might arrive multiple times through different paths | Ciphertext hash + atomic claim. DB unique constraint on packetHash |
| **Freshness** | Attacker intercepts and replays old packets later | Timestamp validation. Reject if >24 hours old or clock-skewed |

---

## File-by-File Breakdown

### Core Services

| File | Purpose |
|------|---------|
| `DemoService.java` | Seeds accounts, creates encrypted packets (simulating sender phone) |
| `MeshSimulatorService.java` | Virtual Bluetooth mesh: devices, gossip, packet spreading |
| `BridgeIngestionService.java` | Validates, decrypts, and processes inbound packets from bridges |
| `SettlementService.java` | Executes debit/credit transactions and persists to DB |
| `IdempotencyService.java` | In-memory cache claiming (fast path for duplicate detection) |

### Security

| File | Purpose |
|------|---------|
| `HybridCryptoService.java` | RSA-OAEP + AES-GCM encryption/decryption |
| `ServerKeyHolder.java` | Generates and holds server's RSA keypair (2048-bit) |

### Models (Data Classes)

| File | Purpose |
|------|---------|
| `PaymentInstruction.java` | What gets encrypted: sender, receiver, amount, PIN hash, nonce, timestamp |
| `MeshPacket.java` | Container: packetId, TTL, createdAt, ciphertext |
| `Transaction.java` | Immutable ledger record: who paid whom, when, status |
| `Account.java` | User account: VPA, name, balance |

### Controllers & Views

| File | Purpose |
|------|---------|
| `ApiController.java` | REST endpoints for demo flow, bridge ingestion, dashboard queries |
| `DashboardController.java` | Serves dashboard.html with initial data |
| `dashboard.html` | Interactive UI (Thymeleaf template) |
| `dashboard.css` | Styling (gradient cards, animations, responsive) |

---

## How to Explain This Project

### To Non-Technical People

> "Imagine you're in a basement with no internet. You want to send money. Your phone encrypts it, passes it to a friend's phone, who passes it to another friend, until someone goes outside and posts it all to the bank. The bank never touches the message until someone with internet delivers it. This project proves that's secure and works."

### To Technical Interviewers

> "This is a demo of **end-to-end encrypted payment routing through an untrusted mesh network**. It showcases:
> 
> 1. **Hybrid Encryption** (RSA-2048 + AES-256-GCM) for confidentiality/integrity
> 2. **Idempotent Processing** via ciphertext hashing + atomic claiming
> 3. **Replay Protection** via timestamp freshness checks
> 4. **Eventual Settlement** — payments created offline settle when network is available
> 
> The backend is a Spring Boot REST service with a virtual mesh simulator. The frontend is an interactive dashboard showing the full flow. Tests include a concurrency test that fires three threads delivering the same packet simultaneously to prove only one settles."

### To Other Engineers (Code Review)

> "The key insight is separating concerns:
> 
> - **DemoService** handles the sender-side logic (what a real phone app would do)
> - **MeshSimulatorService** models the network (real code would use actual BLE)
> - **BridgeIngestionService** is the real production flow—decrypt, validate, settle
> - **IdempotencyService** uses a ConcurrentHashMap for atomic claiming
> - **HybridCryptoService** does the heavy lifting: RSA-OAEP for key wrapping, AES-GCM for payload
> 
> The DB schema has a UNIQUE constraint on packetHash as defense-in-depth. The dashboard uses real-time polling to refresh state. Tests are solid, especially the concurrency test."

---

## Testing Strategy

### Unit Tests
- Crypto operations (encrypt/decrypt roundtrip)
- Idempotency claiming (concurrent threads)

### Integration Test: `IdempotencyConcurrencyTest.java`
```
Scenario:
  1. Create one MeshPacket
  2. Fire 3 threads simultaneously, each calling ingest() with same packet
  3. Assert:
     ✅ Exactly 1 transaction created
     ✅ Exactly 2 duplicates dropped
     ✅ Balance changes reflect only 1 settlement
```

---

## Deployment Notes

### Development / Demo
- Use in-memory H2 database
- Virtual mesh simulator only
- Spring Boot dev server (Tomcat on 8080)

### Production (Hypothetical)
- Real database (PostgreSQL, MySQL)
- Real bridge nodes (Android apps running mesh code)
- Load balancer + multiple backend instances
- Caching layer (Redis) for idempotency
- Monitoring, alerting, audit logs
- Rate limiting on `/api/bridge/ingest`

---

## Limitations & What Would Change

| What's Demo-Only | Production Change |
|------------------|-------------------|
| Virtual mesh | Real Bluetooth Low Energy mesh |
| In-memory device storage | Device hardware storage |
| Single server instance | Horizontally scaled backend |
| In-memory idempotency cache | Redis or distributed cache |
| H2 database | Production RDBMS (PostgreSQL) |
| No authentication | OAuth/JWT for bridge nodes |
| No rate limiting | DDoS protection, per-device limits |
| No audit logging | Full audit trail for compliance |

---

## Running the Demo Yourself

1. **Start the server:**
   ```bash
   ./mvnw spring-boot:run    # Mac/Linux
   mvnw.cmd spring-boot:run  # Windows
   ```

2. **Open dashboard:**
   ```
   http://localhost:8080
   ```

3. **Run the flow:**
   - **Step 1:** Select Alice → Bob, ₹500, PIN 1234, click "📤 Inject"
   - **Step 2:** Click "🔄 Gossip" 2-3 times (watch packets spread)
   - **Step 3:** Click "📡 Upload" (settlement happens)
   - **Result:** Watch transaction appear in ledger, balances update

4. **Run tests:**
   ```bash
   ./mvnw test
   ```

---

## Key Takeaways

✅ **End-to-end encryption** through untrusted intermediaries  
✅ **Idempotent processing** prevents double-spending  
✅ **Replay protection** rejects stale payments  
✅ **Elegant separation of concerns** (sender, mesh, bridge, settlement)  
✅ **Concurrent-safe** atomic operations on idempotency gate  

---

*This project is an excellent interview piece because it combines distributed systems, cryptography, database design, and clean architecture in a compelling, demonstrable package.*
