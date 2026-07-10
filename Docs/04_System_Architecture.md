# 04 --- System Architecture

**Product:** Echo\
**Version:** 1.0 (Living Document)

# 1. Purpose

This document defines the technical architecture of Echo and serves as
the reference for engineers implementing new features.

# 2. Architectural Principles

-   Offline First
-   Privacy First
-   Clean Architecture
-   MVVM
-   Dependency Injection
-   Modular Design
-   AI as an optional layer
-   Feature isolation
-   Testability

# 3. High-Level Architecture

``` text
Presentation (Compose UI)
        │
        ▼
ViewModels (StateFlow)
        │
        ▼
Use Cases / Domain Services
        │
        ▼
Repositories
        │
 ┌──────┼───────────┐
 ▼      ▼           ▼
Room   Audio FS   WorkManager
                │
                ▼
        AI Providers (Future)
```

# 4. Module Structure

``` text
app/
data/
domain/
di/
ui/
util/

Future
feature/
core/
ai/
sync/
```

# 5. Presentation Layer

Responsibilities: - Render UI - Collect StateFlow - Handle user
interactions - No business logic

Screens: - Home - Record - Timeline - Search - Collections - Entry
Details - Settings

# 6. Domain Layer

Responsibilities: - Business rules - Repository contracts - Use cases -
Domain models

Examples: - StartRecording - StopRecording - SearchMemories -
SaveMemory - UpdateTags

# 7. Data Layer

Responsibilities: - Room persistence - Audio file management -
Repository implementations - Data mapping - Background processing

Repositories: - DiaryRepository - CollectionRepository - TagRepository -
(Future) MemoryRepository

# 8. Audio Pipeline

Capture → MediaRecorder → Local File → Room Metadata → Timeline

Future: → WorkManager → Transcription → Language Detection → Embeddings

# 9. Navigation

Single Activity architecture.

Compose Navigation controls all destinations.

Navigation is state-driven.

# 10. Dependency Injection

Hilt manages:

-   Database
-   DAO
-   Repositories
-   Audio Engine
-   WorkManager dependencies
-   Future AI providers

# 11. Background Processing

WorkManager will execute:

-   Transcription
-   Thumbnail generation
-   Backup
-   AI indexing
-   Sync (future)

# 12. Memory Model Evolution

Current: Recording → Metadata

Target: Memory ├── Audio ├── Transcript ├── Summary ├── Tags ├──
Collections ├── Projects ├── People ├── Places ├── Attachments └──
Relationships

# 13. Security

-   Local-first storage
-   Future biometric lock
-   Future encrypted database
-   Future encrypted cloud backup

# 14. Scalability

Architecture supports: - Multiple AI providers - Android - Future
Desktop/Web clients - Future cloud sync - Millions of memory records

# 15. Engineering Standards

-   Business logic never in UI
-   Repository pattern everywhere
-   Immutable UI state
-   StateFlow for reactive updates
-   Dependency injection for services
-   Unit-testable domain layer

# 16. Architecture Decision Records (ADR)

Major decisions should be documented with: - Context - Decision -
Alternatives - Consequences

This ensures the architecture remains understandable as Echo evolves.
