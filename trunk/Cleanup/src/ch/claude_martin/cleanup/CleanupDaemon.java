package ch.claude_martin.cleanup;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class CleanupDaemon implements Runnable {
  CleanupDaemon() {
    super();
  }

  private static final ReferenceQueue<?> QUEUE = new ReferenceQueue<>();
  private static final Map<CleanupPhantomRef<?, ?>, WeakReference<?>> REFS = new IdentityHashMap<>();
  /**
   * A handler for all exceptions that occur.
   * <p>
   * {@link InterruptedException}: thrown when the {@link #THREAD thread} is interrupted. The thread
   * will continue, unless this handler throws a {@link RuntimeException}.
   * <p>
   * {@link RuntimeException}: thrown when the cleanup-code throws it.
   * 
   */
  private final static AtomicReference<Consumer<Throwable>> EXCEPTION_HANDLER = //
      new AtomicReference<>((t) -> {
      });

  /** @see Cleanup#addExceptionHandler(Consumer) */
  static void addExceptionHandler(final Consumer<Throwable> handler) {
    EXCEPTION_HANDLER.getAndAccumulate(handler, Consumer::andThen);
  }

  static ReferenceQueue<?> getQueue() {
    return QUEUE;
  }

  static void handle(final Throwable t) {
    EXCEPTION_HANDLER.get().accept(t);
  }

  /** @see Cleanup#registerCleanup(Consumer, Object) */
  static <V> void registerCleanup(final Object obj, final Consumer<V> cleanup, final V value) {
    check(obj, value);
    synchronized (REFS) {
      REFS.put(new CleanupPhantomRef<>(obj, cleanup, value), new WeakReference<>(obj));
    }
  }

  private static <V> void check(final Object obj, final V value) {
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
   * The thread, which will run the cleanup-code.
   */
  static final Thread THREAD = new Thread(new CleanupDaemon());
  static {
    synchronized (THREAD) {
      THREAD.setName(Cleanup.class.getName() + "-Daemon");
      THREAD.setDaemon(true);
      THREAD.setPriority(Thread.MIN_PRIORITY);
      THREAD.start();
      THREAD.setUncaughtExceptionHandler((thread, t) -> {
        handle(t);
      });
    }
  }

  @Override
  public void run() {
    while (true) {
      try {
        final Reference<?> ref = QUEUE.remove();
        if (ref instanceof CleanupPhantomRef) {
          synchronized (REFS) {
            ((CleanupPhantomRef<?, ?>) ref).runCleanup();
            REFS.remove(ref);
          }
        }
      } catch (final Throwable e) {
        EXCEPTION_HANDLER.get().accept(e);
      }
    }
  }

}
