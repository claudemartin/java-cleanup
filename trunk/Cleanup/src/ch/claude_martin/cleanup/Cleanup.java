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
import java.util.concurrent.locks.Lock;
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
 * <p>
 * <style type="text/css">
 * table { width: 100%; }
 * td.pro { width: 40%; }
 * ul.pro, ul.con { list-style: none; padding-left: 0; margin-left: 0; } 
 * ul.pro li, ul.con li { padding-left: 1.5em; text-indent: -1.5em; } 
 * ul.pro li::before { content: "➕"; padding-right: 0.5em; }
 * ul.con li::before { content: "➖"; padding-right: 0.5em; }
 * </style>
 * 
 * Pros and Cons and Pitfalls:
 * <table summary="List of pros and cons of this code.">
 * <tr align="left" valign="top">
 * <td class="pro">
 * <ul class="pro">
 * <li>You don't need to manually chain the cleanup code.</li>
 * <li>Cleanup is done when the objects is already removed.</li>
 * <li>Less risk of {@link OutOfMemoryError} during GC.</li>
 * <li>Very obvious mistakes in usage are detected.</li>
 * <li>Exceptions can be handled by registered exception handlers.</li>
 * </ul>
 * </td>
 * <td class="con">
 * <ul class="con">
 * <li>Does not work if you leak a reference to <tt>this</tt> to the cleanup-code. References are
 * often implicit and not visible in the code. Many of such mistakes can not be detected and the
 * object is never garbage collected (memory leak).</li>
 * <li>You could close the resources in the wrong order.</li>
 * <li>No guarantee that the code runs when the JVM exits.</li>
 * <li>Memory visibility problems, as the cleanup is performed in a daemon thread.</li>
 * </ul>
 * </td>
 * </tr>
 * </table>
 * 
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
   * The value is any data structure that holds everything you need for the cleanup. <br>
   * Examples: Database connection to be closed. Stream to be closed. ID to be logged. etc. <br>
   * But it must not hold any references to <i>this</i>. Note that instances of inner / anonymous
   * classes also hold implicit references to the outer instance. However, you can reference fields
   * directly in the lambda, instead of using value, as long as the objects do not reference
   * <tt>this</tt>.
   * <p>
   * This can be called multiple times and each time a new {@link PhantomReference} will be created.
   * <p>
   * Cleanup is synchronized on it's <i>value</i>, but multiple cleanup codes use different values.
   * So you might want to use some {@link Lock} to ensure visibility.
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
   * <p>
   * The resources are closed as they are listed, from first to last. Therefore you should list them
   * in the <em>opposite</em> order of their creation.
   * 
   * @param values
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
