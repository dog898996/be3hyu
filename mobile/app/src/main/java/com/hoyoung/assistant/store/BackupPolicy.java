package com.hoyoung.assistant.store;

import java.io.File;
import java.io.IOException;

/** Capacity limits do not allocate storage. The actual private filesystem remains authoritative. */
public final class BackupPolicy {
    public static final long GIB=1024L*1024*1024;
    public static final long MAX_ARCHIVE_BYTES=64L*GIB;
    public static final long DEFAULT_RESERVE_BYTES=256L*1024*1024;
    static final long SPACE_RECHECK_BYTES=16L*1024*1024;
    public final long maxArchiveBytes, reserveBytes;
    private final AvailableSpaceProbe availableSpace;

    public interface AvailableSpaceProbe { long availableBytes(File directory) throws IOException; }
    /** Optional participant contract; it never acquires the database/store monitor. */
    public interface SpaceMonitor {
        void checkpoint(String phase,String relativePath,long upcomingBytes) throws IOException;
        void beforeWrite(int byteCount) throws IOException;
        void written(int byteCount);
    }

    /** Small explicit limits and injected probes permit real streaming/rollback tests without GB fixtures. */
    public BackupPolicy(long maxArchiveBytes, long reserveBytes, AvailableSpaceProbe availableSpace) {
        if(maxArchiveBytes<=0||maxArchiveBytes>MAX_ARCHIVE_BYTES||reserveBytes<0||reserveBytes>MAX_ARCHIVE_BYTES||availableSpace==null)
            throw new IllegalArgumentException("Invalid backup policy");
        this.maxArchiveBytes=maxArchiveBytes; this.reserveBytes=reserveBytes; this.availableSpace=availableSpace;
    }

    public static BackupPolicy defaults() { return new BackupPolicy(MAX_ARCHIVE_BYTES,DEFAULT_RESERVE_BYTES,BackupPolicy::availableOnDisk); }

    public static BackupPolicy fromStore(HoyoungStore store) throws BackupFailure {
        String value=store.getSetting("backupMaxGiB","64");
        if(!value.matches("[1-9][0-9]?")) throw configuration();
        int gib;
        try { gib=Integer.parseInt(value); } catch(NumberFormatException invalid) { throw configuration(); }
        if(gib<1||gib>64) throw configuration();
        return new BackupPolicy(gib*GIB,DEFAULT_RESERVE_BYTES,BackupPolicy::availableOnDisk);
    }

    private static BackupFailure configuration() {
        return new BackupFailure("backup_configuration","preflight","",0,-1,-1,
                "backupMaxGiB must be an integer from 1 through 64",null);
    }

    private static long availableOnDisk(File directory) throws IOException {
        // File.getUsableSpace is a long-valued available-space query on the actual private
        // filesystem (Android's statvfs path); no total-capacity or cached quota assumption.
        try { return directory.getUsableSpace(); }
        catch(RuntimeException unavailable) { throw new IOException("Private filesystem free space could not be inspected",unavailable); }
    }

    Session session(File directory) { return new Session(this,directory); }

    static final class Session implements SpaceMonitor {
        final BackupPolicy policy; private final File directory;
        private long available=-1, sinceCheck, processed;
        private String phase="preflight", path="";
        Session(BackupPolicy policy,File directory){this.policy=policy;this.directory=directory;}

        void phase(String phase,String path) throws BackupFailure { this.phase=phase;this.path=path;check(0); }
        void check(long upcoming) throws BackupFailure {
            long measured;
            try { measured=policy.availableSpace.availableBytes(directory); }
            catch(IOException|RuntimeException unavailable) {
                throw new BackupFailure("backup_space_check",phase,path,processed,-1,required(upcoming),
                        "Private filesystem free space could not be inspected",unavailable);
            }
            available=measured;sinceCheck=0;
            if(measured<required(upcoming)) throw new BackupFailure("backup_private_space",phase,path,processed,
                    measured,required(upcoming),"Private filesystem free space is below the backup reserve",null);
        }

        @Override public void beforeWrite(int bytes) throws BackupFailure {
            if(bytes<0)throw new IllegalArgumentException("Negative backup write length");
            if(available<0||sinceCheck>=SPACE_RECHECK_BYTES||available<required(bytes))check(bytes);
            if(available<required(bytes))throw new BackupFailure("backup_private_space",phase,path,processed,
                    available,required(bytes),"Private filesystem has insufficient staging space",null);
        }
        @Override public void written(int bytes){if(bytes<0)throw new IllegalArgumentException("Negative backup write length");processed+=bytes;sinceCheck+=bytes;if(available>=0)available-=bytes;}
        void streamed(int bytes) throws BackupFailure {processed+=bytes;sinceCheck+=bytes;if(sinceCheck>=SPACE_RECHECK_BYTES)check(0);}
        @Override public void checkpoint(String phase,String path,long upcoming) throws BackupFailure{this.phase=phase;this.path=path;check(upcoming);}
        private long required(long upcoming){return upcoming>Long.MAX_VALUE-policy.reserveBytes?Long.MAX_VALUE:policy.reserveBytes+Math.max(0,upcoming);}
        BackupFailure failure(String code,String message,Throwable cause){
            if(cause instanceof BackupFailure)return(BackupFailure)cause;
            return new BackupFailure(code,phase,path,processed,available,policy.reserveBytes,message,cause);
        }
        String phase(){return phase;}
        long availableBytes(){return available;}
    }
}
