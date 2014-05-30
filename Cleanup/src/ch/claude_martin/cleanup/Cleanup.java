package ch.claude_martin.cleanup;

import java.lang.ref.PhantomReference;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

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
 *   table { width: 100%; } 
 *   td.pro { width: 40%; } 
 *   ul.pro, ul.con {
 *   list-style: none; padding-left: 0; margin-left: 0; } 
 *   ul.pro li, ul.con li { padding-left: 1.5em;text-indent: -1.5em; } 
 *   ul.pro li::before { content: "➕"; padding-right: 0.5em; } 
 *   ul.con li::before { content: "➖"; padding-right: 0.5em; } 
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
 * <li>No guarantee that the code runs when the JVM exits.</li>
 * <li>Invocations are not ordered and you could close the resources in the wrong order.</li>
 * <li>There is still a risk that cleanup is done for incompletely constructed objects.</li>
 * <li>Memory visibility problems, as the cleanup is performed in a daemon thread.</li>
 * <li>Use of locks or other synchronization-based mechanisms within a cleanup can cause deadlock or
 * starvation.</li>
 * </ul>
 * </td>
 * </tr>
 * </table>
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
  public static void addExceptionHandler(final Consumer<Throwable> handler) {
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
   *          All data needed for cleanup, or null
   * @throws IllegalArgumentException
   *           thrown if value is obviously holding a reference to <i>this</i>
   */
  public default <V> void registerCleanup(final Consumer<V> cleanup, final V value) {
    CleanupDaemon.registerCleanup(this, cleanup, value);
  }

  /**
   * Easy registration of auto-closeable resources.
   * <p>
   * The resources are closed as they are listed, from first to last. Therefore you should list them
   * in the <em>opposite</em> order of their creation.
   * 
   * @param resources
   *          auto-closeable resources.
   */
  public default <V extends AutoCloseable> void registerAutoClose(
      @SuppressWarnings("unchecked") final V... resources) {
    registerAutoClose(this, resources);
  }

  /**
   * Register any object and a value for cleanup. See the non-static method for more information.
   * 
   * @see #registerCleanup(Consumer, Object)
   * @param object
   *          object for which a {@link PhantomReference} will be created
   * @param cleanup
   *          A consumer to clean up the value
   * @param value
   *          All data needed for cleanup
   * @throws IllegalArgumentException
   *           thrown if value is obviously holding a reference to <i>this</i>
   */
  public static <V> void registerCleanup(final Object object, final Consumer<V> cleanup, final V value) {
    CleanupDaemon.registerCleanup(object, cleanup, value);
  }

  /**
   * Register any object and its resources for cleanup. See the non-static method for more
   * information.
   * 
   * @see #registerAutoClose(AutoCloseable...)
   * @param object
   *          object for which a {@link PhantomReference} will be created
   * @param resources
   *          auto-closeable resources.
   */
  public static <R extends AutoCloseable> void registerAutoClose(final Object object,
      @SuppressWarnings("unchecked") final R... resources) {
    if (resources == null || Arrays.asList(resources).contains(null))
      throw new NullPointerException("values");
    registerCleanup(object, (res) -> {
      for (final R v : res)
        try {
          v.close();
        } catch (final Exception e) {
          CleanupDaemon.handle(e);
        }
    }, resources);
  }
}
