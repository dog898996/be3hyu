# Durable Android device journal

Phone observations first enter private `files/device-journal/pending` JSON files. A callback receives a successful spool receipt only after flushing and atomic publication. A separate background worker inserts the source-backed event into SQLite using its deduplication key, then moves the original bytes to `archive/<captured-day>/<original-filename>.json`. A crash after the SQLite commit but before acknowledgement replays through the same deduplication key.

Keeping one archived file per event indefinitely wastes filesystem allocation units and can exhaust the backup's100000-entry limit. The ingestion worker now replaces groups of acknowledged originals with immutable raw containers. It copies bytes rather than summarizing/re-encoding them. Source filenames, internal JSON checksums and original content remain intact; each frame also carries a SHA-256 of its exact byte sequence.

## Segment format

Files live at `archive/segments/segment-<complete-SHA256>.hja`. Each published file is at most32MiB. The normal writer batches at most256 files across day directories, rotating earlier when the byte limit would be exceeded. Packing across days avoids accumulating a partly filled group for every day.

All numbers use big-endian binary encoding:

| Field | Encoding |
|---|---|
| Header |64-bit HOYOUNG1 magic,32-bit version1 |
| Repeated original records |32-bit UTF-8 path-byte length, original `<day>/<filename>.json` path,32-bit body-byte length,32-byte SHA-256, exact original body bytes |
| Footer |32-bit sentinel−1,32-bit record count,64-bit ENDRAW01 magic |

The validator rejects unsafe/duplicate frame paths, malformed UTF-8 metadata, oversize names/bodies/files, unsupported headers, body-checksum mismatch, incomplete frames, invalid footers, trailing bytes and a complete hash that disagrees with the published filename. It streams verification with a bounded buffer. Original contents need not be reserialized as JSON.

## Publication, cleanup and restart

Compaction runs only on the journal worker, outside Android callbacks. It writes a temporary container, flushes it, verifies all frames, publishes it through atomic rename, flushes the directory and verifies the published container. Only then may matching original files be unlinked. Original hashing/copying and directory flushing do not hold the callback monitor. Each short unlink checks that no active snapshot pins the originals.

A small checksum-protected .done marker records completed cleanup. After a crash between publication and cleanup, recovery verifies the container and resumes removal of only originals whose length and byte hash still match. A changed original remains alongside the earlier bytes and causes an explicit failure. A complete interrupted temporary container can be verified/published. An incomplete one is preserved in quarantine and generates a gap event; its source originals are retained.

An ordinary failed compaction does not discard original records. Persistent failure status and gap events distinguish archive validation/storage failures from complete capture. Full storage loss can prevent even a failure marker from being saved. File/directory flushing cannot guarantee recovery from every hardware failure; an independently stored backup is still useful.

## Backup and restore

Snapshot capture pins originals while linking/copying existing immutable files. A segment published after segment enumeration cannot delete its originals until the snapshot finishes, preventing a capture from missing both representations. Remaining uncompacted raw files are packed into snapshot containers, so a large old backlog does not require one ZIP entry per event. The destination must be empty; an export retry cannot overwrite a hardlink to a live original.

The journal snapshot is captured before the independent SQLite snapshot. Observations captured after the journal cut can fall outside that backup; it is not a claim that ongoing phone activity was frozen. Pending observations present in the backup may replay, and SQLite deduplication handles events already included in the database cut. Acknowledged archive containers never enter the pending ingestion queue.

PhoneEventJournal.validateBackupSnapshot(File stagedJournal) verifies published segment framing and hashes before a restore replaces live files or the database. The backup participant must register this validator as well as the capture callback. A valid outer ZIP checksum does not replace internal segment validation. An invalid segment leaves the current database, files, journal and autonomy setting unchanged.

The outer backup retains its existing limits:100000 entries,64MiB per working file, and4GiB expanded total. Segments reduce inode/entry overhead; they do not shrink raw text, SQLite originals, embeddings or indexes. No20MiB-per-year or unlimited-storage claim is made. For one million small archived records, the normal grouping is about3907 segments plus cleanup markers rather than one million individual raw files; unusually large records rotate sooner.

## Validation performed in development

Synthetic tests exercise exact-byte/name/checksum preservation, rotation with small injectable bounds, a1025-file old backlog below a simulated10-entry budget, publish-before-cleanup and mid-cleanup restarts, partial-container recovery/quarantine, corrupted container retention, changed-original retention, snapshot-publication races, callback progress during a blocked export, safe repeated-export rejection and rejection before restore when outer ZIP checksums are valid but an internal segment is corrupt.

These tests do not access actual contacts, notifications, location, microphone, accounts or other private phone data. They validate filesystem logic under Robolectric, not a measured Samsung storage/battery endurance result or a completed one-million-event physical-phone run.
