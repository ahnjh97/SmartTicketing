package smartticketing.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Drain in-flight application writes before a maintenance task starts. */
@Component
public class AdminMaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean requested;
    public boolean enterWriteRequest() {
        if (requested || !lock.readLock().tryLock()) return false;
        if (requested) { lock.readLock().unlock(); return false; }
        return true;
    }
    public void leaveWriteRequest() { lock.readLock().unlock(); }
    public void maintain(Runnable work) {
        requested = true;
        lock.writeLock().lock();
        try { work.run(); }
        finally { lock.writeLock().unlock(); requested = false; }
    }
    public void background(Runnable work) {
        if (!enterWriteRequest()) return;
        try { work.run(); } finally { leaveWriteRequest(); }
    }
}
