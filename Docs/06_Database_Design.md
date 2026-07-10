# 06 --- Database Design Specification

## 1. Design Principles

• Offline-first • Privacy-first • Memory-centric model • Normalized
schema • UUID primary keys • Immutable audio assets • AI metadata stored
separately from original content

## 2. Current Storage

Room stores structured metadata. Audio files are stored in the
application file system and referenced by URI/path. WorkManager performs
asynchronous enrichment tasks.

## 3. Core Entities

Memory (planned canonical entity) - id (UUID) - createdAt - updatedAt -
title - audioPath - duration - favorite - deleted - language -
transcriptStatus - summaryStatus

Current implementation includes DiaryEntry, Tag, Collection and their
many-to-many cross-reference tables.

## 4. Relationships

Memory ↔ Tags (many-to-many) Memory ↔ Collections (many-to-many) Memory
↔ Projects (future) Memory ↔ People (future) Memory ↔ Places (future)
Memory ↔ Attachments (future) Memory ↔ Related Memories (self-reference)

## 5. Indexing Strategy

Indexes: - createdAt - updatedAt - favorite - deleted - title -
language - tagName - collectionId

Future: - transcript full-text search - embedding lookup table -
semantic vector index (provider dependent).

## 6. Migrations

Use Room versioned migrations. Never perform destructive migration in
production. Include migration tests for every schema version. Maintain
backward compatibility whenever possible.

## 7. Repository Layer

Repositories abstract persistence: - DiaryRepository - TagRepository -
CollectionRepository - Future MemoryRepository

UI never accesses DAOs directly.

## 8. Data Lifecycle

Capture → Save Audio → Create Metadata → Index → User Edits → AI
Enrichment → Search → Archive/Delete

## 9. Backup & Restore

Future support: - Local encrypted backup - Import/Export - Optional
cloud sync - Schema version verification during restore

## 10. Security

Audio remains local by default. Sensitive metadata encrypted in future
releases. Biometric lock protects application access. AI annotations
never overwrite original user data.

## 11. Testing Requirements

Validate: - CRUD operations - Relationship integrity - Cascade
behavior - Migration correctness - Performance with 100k+ memories
(target) - Search latency

## 12. Future Evolution

Introduce canonical Memory table replacing DiaryEntry. Separate AI
metadata, embeddings and transcripts into dedicated tables. Support
cross-platform synchronization while preserving local-first ownership.
