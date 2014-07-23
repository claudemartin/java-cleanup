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
   * {@link InterruptedException}: thrown when the {@link #THREAD thread} is interrupted. The thread
   * will continue, unless this handler throws a {@link RuntimeException}.
   * <p>
   * {@link RuntimeException}: thrown when the cleanup action throws it.
   * 
   */
  private final static AtomicReference<Consumer<Throwable>> EXCEPTION_HANDLER = //
      new AtomicReference<>(t -> {});

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
    while (true) {
      try {
        final Reference<?> ref = QUEUE.remove();
        if (ref instanceof CleanupPhantomRef) {
          synchronized (REFS) {
            REFS.remove(ref);
            ((CleanupPhantomRef<?, ?>) ref).runCleanup();
          }
        }
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
          runCleanup();
        } catch (Throwable e) {
          // Can't handle now. System is already shutting down.
        }
      }, "Shutdown Hook for Cleanup");
    
    if (value)
      Runtime.getRuntime().addShutdownHook(hook);
    else if (hook != null)
      Runtime.getRuntime().removeShutdownHook(hook);
  }

  synchronized static void runCleanup() throws InterruptedException {
      final int prio = THREAD.getPriority();
      try {
        final ReferenceQueue<?> q = getQueue();
        // "queueLength" is not volatile and therefore not always seen.
        // "head" is volatile and is null if queue is empty.
        final Field field = ReferenceQueue.class.getDeclaredField("head");
        field.setAccessible(true);
        if(null != field.get(q)) {
          THREAD.setPriority(Thread.MAX_PRIORITY);
          Thread.yield(); 
        }
        // Check if there are more:
        while (null != field.get(q)) 
          Thread.sleep(100); 
        Thread.yield(); // cleanup might still run!
      } catch (NoSuchFieldException | SecurityException | NullPointerException
          | IllegalArgumentException | IllegalAccessException e) {
        // Maybe "head" does not exist -> plan b:
        THREAD.setPriority(Thread.MAX_PRIORITY);
        Thread.yield(); 
      } finally { 
        if(THREAD.getPriority() == Thread.MAX_PRIORITY)
          THREAD.setPriority(prio);
      }
  }

}
