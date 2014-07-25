package ch.claude_martin.cleanup;

import static java.util.Objects.requireNonNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class CleanupDaemon implements Runnable {
  CleanupDaemon() {
    super();
  }
  /** The ReferenceQueue for all PhantomReferences. */
  private static final ReferenceQueue<?> QUEUE = new ReferenceQueue<>();
  /** The PhantomReferences, so that they do not get removed before their object is removed. */
  private static final Map<CleanupPhantomRef<?, ?>, WeakReference<?>> REFS = new IdentityHashMap<>();
  /**
   * A handler for all exceptions that occur. 
   * The consumer can be {@link Consumer#andThen(Consumer) linked} to more consumers/handlers.
   * 
   * <p>
   * Note that {@link InterruptedException} is ignored and the thread will continue.
   * 
   */
  private final static AtomicReference<Consumer<Throwable>> EXCEPTION_HANDLER = //
      new AtomicReference<>(t -> {});

  private static volatile boolean running = true;
  private static volatile boolean runOnExit = false;
  private static volatile Thread hook = null;
  
  
  /** @see Cleanup#addExceptionHandler(Consumer) */
  static void addExceptionHandler(final Consumer<Throwable> handler) {
    EXCEPTION_HANDLER.getAndAccumulate(handler, Consumer::andThen);
  }

  /** The ReferenceQueue for all PhantomReferences. */
  static ReferenceQueue<?> getQueue() {
    return QUEUE;
  }

  /** Handle any exception by all registered handlers. */
  static void handle(final Throwable t) {
    try {
      EXCEPTION_HANDLER.get().accept(t);
    } catch(Throwable t2) {
      // We already tried to handle an exception.
      // This one is lost.
    }
  }

  /** @see Cleanup#registerCleanup(Consumer, Object) */
  static <V> void registerCleanup(final Object obj, final Consumer<V> cleanup, final V value) {
    requireNonNull(obj, "obj");
    requireNonNull(cleanup, "cleanup");
    check(obj, value);
    synchronized (REFS) {
      REFS.put(new CleanupPhantomRef<>(obj, cleanup, value), new WeakReference<>(obj));
    }
  }

  /** Checks if value could hold a reference to obj. */
  private static <V> void check(final Object obj, final V value) {
    if(null == value) return;
    if (value == obj)
      throw new IllegalArgumentException("'value' must not be the object itself!");
    final Class<? extends Object> type = value.getClass();
    final boolean isInnerClass = type.getEnclosingClass() == obj.getClass();
    if (type.isAnonymousClass() && isInnerClass)
      throw new IllegalArgumentException("'value' must not be of anonymous class!");
    if (!Modifier.isStatic(type.getModifiers()) && isInnerClass)
      throw new IllegalArgumentException("'value' must not be of inner class!");
    if (type.isSynthetic())
      throw new IllegalArgumentException("'value' must not be of synthetic class!");
  }

  /**
   * The thread, which will run the cleanup actions.
   */
  static final Thread THREAD = new Thread(new CleanupDaemon());
  static { // this is only run if and when this class is loaded.
    synchronized (THREAD) {
      THREAD.setName(CleanupDaemon.class.getName());
      THREAD.setDaemon(true); // daemon by default
      THREAD.setPriority(Thread.MIN_PRIORITY);
      THREAD.setUncaughtExceptionHandler((thread, t) -> handle(t));
      THREAD.start();
    }
  }

  @Override
  public void run() {
    while (running) {
      final Reference<?> ref;
      try {
        ref = QUEUE.remove();
      } catch (InterruptedException e) {
        // This should only happen on shutdown.
        // See runCleanupOnExit(). 
        continue; // ... to check "running".
      }
      if (ref instanceof CleanupPhantomRef)
        cleanupReference((CleanupPhantomRef<?, ?>) ref);
    }
  }
  
  static void cleanupReference(final CleanupPhantomRef<?, ?> ref) {
    synchronized (REFS) {
      REFS.remove(ref);
      try {
        ref.runCleanup();
      } catch (final Throwable e) {
        handle(e);
      }
    }
  }

  /** @see Cleanup#runCleanupOnExit(boolean) */
  synchronized static void runCleanupOnExit(final boolean value) {
    runOnExit = true;
    if (value && hook == null)
      hook = new Thread(() -> {
        if (!runOnExit)
          return;
        try {
          running = false;
          THREAD.interrupt();
          for (int i = 0; i < 10; i++) {
            if (REFS.isEmpty())
              break;
            for (int j = 0; j < 10; j++) {
              // Cleanup is only possible for discarded objects!
              System.gc();
              Thread.sleep(100); // Some time for GC.
              System.runFinalization();
            }
            Reference<?> ref;
            while (null != (ref = QUEUE.poll()))
              if (ref instanceof CleanupPhantomRef)
                cleanupReference((CleanupPhantomRef<?, ?>) ref);
          }
        } catch (Throwable e) {
          // Can't handle now. System is already shutting down.
        }
      }, "Shutdown Hook for Cleanup");

    if (value)
      Runtime.getRuntime().addShutdownHook(hook);
    else if (hook != null)
      Runtime.getRuntime().removeShutdownHook(hook);
  }

  /** @see Cleanup#runCleanup() */
  synchronized static void runCleanup() {
    Reference<?> ref;
    while (null != (ref = QUEUE.poll()))
      if (ref instanceof CleanupPhantomRef)
        cleanupReference((CleanupPhantomRef<?, ?>) ref);
  }

}
