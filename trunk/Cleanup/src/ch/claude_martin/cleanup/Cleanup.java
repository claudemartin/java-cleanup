package ch.claude_martin.cleanup;

import java.io.Closeable;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.javafx.geom.transform.Identity;

/**
 * 
 * This is an interface to add cleanup code to any type. The method
 * {@link #registerCleanup(Consumer, Object)} must be run at the end of or after construction (code
 * run after that could invalidate the object, which could lead to problems). The configured
 * <i>cleanup</i>-code is run when <i>this</i> does not exist anymore. Therefore the <i>value</i>
 * must not contain any references to <i>this</i>.
 * <p>
 * You can use {@link #addExceptionHandler(Consumer)} to handle exceptions (e.g. send exceptions to
 * your logging system).
 * 
 * @author Claude Martin
 *
 */
public interface Cleanup {

  /**
   * Adds a handler for Exceptions thrown during cleanup. This includes all exceptions thrown in the
   * daemon-thread used to perform cleanup. All added handlers are executed until one throws an
   * unchecked exception.
   * <p>
   * Note that even {@link InterruptedException} is handled. In that case it is recommend to call
   * {@link Thread#interrupt()} to interrupt the thread again.
   * <p>
   * It is recommended that you use this for logging, to be able to find problems in the cleanup
   * code.
   */
  public static void addExceptionHandler(Consumer<Throwable> handler) {
    CleanupDaemon.addExceptionHandler(handler);
  }

  /**
   * Register method to <i>clean up</i> after garbage collection removed this.
   * <p>
   * The value is any data structure that holds everything you need for the cleanup. <br/>
   * Examples: Database connection to be closed. Stream to be closed. ID to be logged. etc. But it
   * must not hold any references to <i>this</i>. Note that instances of inner / anonymous classes
   * also hold implicit references to the outer instance.
   * <p>
   * This can be called multiple times and each time a new {@link PhantomReference} will be created.
   * 
   * @param cleanup
   *          A consumer to clean up the value
   * @param value
   *          All data needed for cleanup
   * @throws IllegalArgumentException
   *           thrown if value is obviously holding a reference to <i>this</i>
   */
  public default <V> void registerCleanup(Consumer<V> cleanup, V value) {
    if (value == this)
      throw new IllegalArgumentException("'value' must not be 'this'!");
    Class<? extends Object> type = value.getClass();
    boolean isInnerClass = type.getEnclosingClass() == this.getClass();
    if (type.isAnonymousClass() && isInnerClass)
      throw new IllegalArgumentException("'value' must not be of anonymous class!");
    if (!Modifier.isStatic(type.getModifiers()) && isInnerClass)
      throw new IllegalArgumentException("'value' must not be of inner class!");
    if (type.isSynthetic())
      throw new IllegalArgumentException("'value' must not be of synthetic class!");

    CleanupDaemon.registerCleanup(this, cleanup, value);
  }

  /**
   * Easy registration of auto-closeable resources.
   * 
   * @param value
   *          auto-closeable resources.
   */
  public default <V extends AutoCloseable> void registerAutoClose(
      @SuppressWarnings("unchecked") V... values) {
    if (values == null || Arrays.asList(values).contains(null))
      throw new NullPointerException("values");
    this.registerCleanup((_values) -> {
      for (V v : _values)
        try {
          v.close();
        } catch (Exception e) {
          CleanupDaemon.handle(e);
        }
    }, values);
  }
}
