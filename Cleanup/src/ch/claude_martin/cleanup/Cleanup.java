package ch.claude_martin.cleanup;

import static java.util.Objects.requireNonNull;

import java.lang.ref.PhantomReference;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * 
 * This is an interface to add cleanup actions to any type. The method
 * {@link #registerCleanup(Consumer, Object)} must be run at the end of or after construction (code
 * run after that could invalidate the object, which could lead to problems). The configured
 * <i>cleanup</i> action is run when <tt>this</tt> does not exist anymore. Therefore the
 * <i>value</i> must not contain any references to <tt>this</tt>.
 * <p>
 * You can use {@link #addExceptionHandler(Consumer)} to handle exceptions (e.g. send exceptions to
 * your logging system).
 * <p><!-- @formatter:off -->
 * <style type="text/css"> 
 *   table { width: 100%; } 
 *   td.pro { width: 40%; } 
 *   ul.pro, ul.con {
 *   list-style: none; padding-left: 0; margin-left: 0; } 
 *   ul.pro li, ul.con li { padding-left: 1.5em;text-indent: -1.5em; } 
 *   ul.pro li::before, ul.con li::before { padding-right: 0.5em; font-weight: bold; }
 *   ul.pro li::before { content: "+"; } 
 *   ul.con li::before { content: "—"; } 
 * </style>
 * <!-- @formatter:on -->
 * Pros and Cons and Pitfalls:
 * <table summary="List of pros and cons of this code.">
 * <tr align="left" valign="top">
 * <td class="pro">
 * <ul class="pro">
 * <li>You don't need to manually chain the cleanup actions.</li>
 * <li>Cleanup is done when the objects is already removed.</li>
 * <li>Less risk of {@link OutOfMemoryError} during GC.</li>
 * <li>Very obvious mistakes in usage are detected.</li>
 * <li>Exceptions can be handled by registered exception handlers.</li>
 * <li>{@link Cleanup#runCleanupOnExit(boolean)} is available, while
 * {@link Runtime#runFinalizersOnExit(boolean)} is deprecated.*</li>
 * </ul>
 * </td>
 * <td class="con">
 * <ul class="con">
 * <li>Does not work if you leak a reference to <tt>this</tt> to the cleanup action. References
 * are often implicit and not visible in the code. Many of such mistakes can not be detected and the
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
 * <p>
 * * Cleanup actions are always only run for discarded objects. You should use
 * {@link Runtime#addShutdownHook(Thread)} for all code that must run on shutdown.
 * 
 * <p>
 * A note from the author:<br>
 * I decided to use the name <i>Cleanup</i> because it is simple to understand. However, there is
 * the <a href="http://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html"
 * >try-with-resource statement</a> which is much better if you actually need to close something.
 * <i>Cleanup</i> should be used for code that isn't critical, such as logging and debugging.<br>
 * I recommend using static methods instead of lambdas:<br>
 * {@code foo.registerCleanup(Foo::cleanup); }<br>
 * This is shown in the Example, which you can find in the test-folder. It is very safe and easy to
 * understand for others, as you can include the static method in your API documentation.
 * 
 * @author Claude Martin
 *
 */
public interface Cleanup {

  /**
   * Adds a handler for Exceptions thrown during cleanup. This includes all exceptions thrown in the
   * daemon-thread used to perform cleanup. All added handlers are executed until one throws an
   * unchecked exception. Unchecked exceptions thrown by exception handlers are lost and they
   * prevent other handlers to run. Make sure that does not happen.
   * <p>
   * It is recommended that you use this for logging, to be able to find problems in the cleanup
   * actions.
   */
  public static void addExceptionHandler(final Consumer<Throwable> handler) {
    CleanupDaemon.addExceptionHandler(handler);
  }

  /**
   * Register method to <i>clean up</i> after garbage collection removed <tt>this</tt>.
   * <p>
   * The value is any data structure that holds everything you need for the cleanup. <br>
   * Examples: Database connection to be closed. Stream to be closed. ID to be logged. etc. <br>
   * But it must not hold any references to <tt>this</tt>. Note that instances of inner / anonymous
   * classes also hold implicit references to the outer instance. However, you can reference fields
   * directly in the lambda, instead of using value, as long as the objects do not reference
   * <tt>this</tt>.
   * <p>
   * Multiple actions can be registered and each time a new {@link PhantomReference} will be
   * created.
   * <p>
   * Cleanup is synchronized on it's <i>value</i>, but multiple cleanup actions use different
   * values. So you might want to use some {@link Lock} to ensure visibility.
   * 
   * @param cleanup
   *          A consumer that defines the cleanup action
   * @param value
   *          All data needed for cleanup, or <tt>null</tt>
   * @throws IllegalArgumentException
   *           thrown if <i>value</i> is obviously holding a reference to <tt>this</tt>
   */
  public default <V> void registerCleanup(final Consumer<V> cleanup, final V value) {
    CleanupDaemon.registerCleanup(this, cleanup, value);
  }

  /**
   * Convenience method for cleanup actions that does not need any value.
   * <p>
   * Make sure you do not capture a reference to <tt>this</tt> in your expression. However, you may
   * capture other values, like objects from local, effectively final variables.
   * <p>
   * This is especially useful for static cleanup actions:<br/>
   * {@code foo.registerCleanup(Foo::cleanup); }<br/>
   * All you need is a static, void, no-args method in your class:<br/>
   * <code>class Foo {<br/>
   * &nbsp; public static void cleanup() { ... }<br/>
   * }</code>
   * 
   * @param cleanup
   *          runnable cleanup action
   * @see #registerCleanup(Consumer, Object)
   */
  public default void registerCleanup(final Runnable cleanup) {
    CleanupDaemon.registerCleanup(this, _null -> cleanup.run(), null);
  }

  /**
   * Easy registration of {@link AutoCloseable auto-closeable} resources.
   * <p>
   * The resources are closed as they are listed, from first to last. Therefore you should list them
   * in the <em>opposite</em> order of their creation.
   * <p>
   * The documentation of {@link AutoCloseable#close()} states that it is not required to be
   * idempotent. But for a cleanup action it is recommended to always use resources with an
   * idempotent close method (cf. {@link java.sql.Connection#close()},
   * {@link java.io.Closeable#close()}).
   * 
   * @param resources
   *          auto-closeable resources.
   * @throws NullPointerException
   *           if <tt>null</tt> is passed as a resource
   * @throws IllegalArgumentException
   *           if <tt>this</tt> is passed as a resource
   */
  public default void registerAutoClose(final AutoCloseable... resources) {
    registerAutoClose(this, resources);
  }

  /**
   * Register any object and a value for cleanup. See the non-static method for more information.
   * 
   * @see #registerCleanup(Consumer, Object)
   * @param object
   *          object for which a {@link PhantomReference} will be created
   * @param cleanup
   *          A consumer to clean up the <i>value</i>
   * @param value
   *          All data needed for cleanup, or <tt>null</tt>
   * @throws IllegalArgumentException
   *           thrown if <i>value</i> is obviously holding a reference to <tt>this</tt>
   */
  public static <V> void registerCleanup(final Object object, final Consumer<V> cleanup,
      final V value) {
    CleanupDaemon.registerCleanup(object, cleanup, value);
  }

  /**
   * Register any object and its resources for cleanup. See the
   * {@link #registerAutoClose(AutoCloseable...) non-static method} for more information.
   * 
   * @see #registerAutoClose(AutoCloseable...)
   * @param object
   *          object for which a {@link PhantomReference} will be created
   * @param resources
   *          auto-closeable resources.
   * @throws NullPointerException
   *           if <tt>null</tt> is passed as the object or a resource
   * @throws IllegalArgumentException
   *           if any resource is the object
   */
  public static void registerAutoClose(final Object object, final AutoCloseable... resources) {
    requireNonNull(object, "object");
    requireNonNull(resources, "resources");
    for (final AutoCloseable a : resources)
      if (object == requireNonNull(a, "resources"))
        throw new IllegalArgumentException("not allowed: object.registerAutoClose(object)");

    registerCleanup(object, res -> {
      for (final AutoCloseable a : res)
        try {
          a.close();
        } catch (final Exception e) {
          CleanupDaemon.handle(e);
        }
    } , resources);
  }

  /**
   * Runs cleanup code by the use of {@link Runtime#addShutdownHook(Thread)}. This is an alternative
   * to the deprecated {@link Runtime#runFinalizersOnExit(boolean)}. But note that only cleanup
   * actions of discarded objects are run. For objects that live until shutdown the action needs to
   * be registered by {@link Runtime#addShutdownHook(Thread)} additionally. Such an action will run
   * when the object might still be in use by any threads. Cleanup actions, on the other hand, are
   * designed to run for discarded objects only.
   * 
   * @param value
   *          <tt>true</tt> to enable cleanup on exit, <tt>false</tt> to disable
   * @see Thread#setDaemon(boolean)
   */
  public static void runCleanupOnExit(final boolean value) {
    CleanupDaemon.runCleanupOnExit(value);
  }

  /**
   * Changes the <i>priority</i> of the cleanup thread. Default is {@link Thread#MIN_PRIORITY}.
   * 
   * @param newPriority
   *          priority to set thread to
   * @see Thread#setPriority(int)
   * @throws IllegalArgumentException
   *           If the priority is not in the range <tt>MIN_PRIORITY</tt> to <tt>MAX_PRIORITY</tt>.
   * @throws SecurityException
   *           if the current thread cannot modify the cleanup thread.
   * @see Thread#MAX_PRIORITY
   * @see Thread#MIN_PRIORITY
   */
  public static void setCleanupPriority(final int newPriority) {
    CleanupDaemon.THREAD.setPriority(newPriority);
  }

  /**
   * Runs the pending cleanup actions.
   * <p>
   * Cleanup actions are processed automatically as needed, if the <tt>runCleanup</tt> method is not
   * invoked explicitly.
   * <p>
   * This does not invoke <code>System.gc();</code> and therefore only blocks until all objects are
   * handled that were already discarded.
   * 
   * @see Runtime#runFinalization()
   */
  public static void runCleanup() {
    CleanupDaemon.runCleanup();
  }
}
