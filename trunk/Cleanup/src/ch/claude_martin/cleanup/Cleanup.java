package ch.claude_martin.cleanup;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * 
 * This is an interface to add cleanup actions to any type. The method
 * {@link #registerCleanup(Consumer, Object)} must be run at the end of or after construction (code
 * run after that could invalidate the object, which could lead to problems). The configured
 * <i>cleanup</i> action is run when <code>this</code> does not exist anymore. Therefore the
 * <i>value</i> must not contain any references to <code>this</code>.
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
 *   ul.pro li::before, ul.con li::before { padding-right: 0.5em; font-weight: bold; }
 *   ul.pro li::before { content: "+"; } 
 *   ul.con li::before { content: "—"; } 
 * </style>
 * 
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
 * </ul>
 * </td>
 * <td class="con">
 * <ul class="con">
 * <li>Does not work if you leak a reference to <code>this</code> to the cleanup action. References
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
   * unchecked exception.
   * <p>
   * Note that even {@link InterruptedException} is handled. In that case it is recommend to call
   * {@link Thread#interrupt()} to interrupt the thread again.
   * <p>
   * It is recommended that you use this for logging, to be able to find problems in the cleanup
   * action.
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
   * Cleanup is synchronized on it's <i>value</i>, but multiple cleanup actions use different values.
   * So you might want to use some {@link Lock} to ensure visibility.
   * 
   * @param cleanup
   *          A consumer that defines the cleanup action
   * @param value
   *          All data needed for cleanup, or null
   * @throws IllegalArgumentException
   *           thrown if value is obviously holding a reference to <i>this</i>
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
   * Easy registration of auto-closeable resources.
   * <p>
   * The resources are closed as they are listed, from first to last. Therefore you should list them
   * in the <em>opposite</em> order of their creation.
   * 
   * @param resources
   *          auto-closeable resources.
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
   *          A consumer to clean up the value
   * @param value
   *          All data needed for cleanup
   * @throws IllegalArgumentException
   *           thrown if value is obviously holding a reference to <i>this</i>
   */
  public static <V> void registerCleanup(final Object object, final Consumer<V> cleanup,
      final V value) {
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
  public static void registerAutoClose(final Object object, final AutoCloseable... resources) {
    if (resources == null || Arrays.asList(resources).contains(null))
      throw new NullPointerException("values");
    registerCleanup(object, res -> {
      for (final AutoCloseable a : res)
        try {
          a.close();
        } catch (final Exception e) {
          CleanupDaemon.handle(e);
        }
    }, resources);
  }

  /**
   * Alternative to {@link Runtime#runFinalizersOnExit(boolean)}. This simply sets the used thread
   * to be a daemon or a user thread. By default no cleanup actions are run on exit.
   * 
   * @param value
   *          true to enable cleanup on exit, false to disable
   * @see Thread#setDaemon(boolean)
   */
  public static void runCleanupOnExit(boolean value) {
    CleanupDaemon.THREAD.setDaemon(!value);
  }

  /**
   * Changes the priority of the cleanup thread.
   * Default is {@link Thread#MIN_PRIORITY}.
   * 
   * @param newPriority
   *          priority to set thread to
   * @see Thread#setPriority(int)
   * @throws IllegalArgumentException
   *           If the priority is not in the range <code>MIN_PRIORITY</code> to
   *           <code>MAX_PRIORITY</code>.
   * @throws SecurityException
   *           if the current thread cannot modify this thread.
   * @see Thread#MAX_PRIORITY
   * @see Thread#MIN_PRIORITY
   */
  public static void setCleanupPriority(int newPriority) {
    CleanupDaemon.THREAD.setPriority(newPriority);
  }

  /**
   * Runs the pending cleanup actions. Calling this method suggests that the Java virtual machine
   * expend effort toward running the <code>cleanup</code> actions of objects that have been
   * discarded. When control returns from the method call, the virtual machine has made a best
   * effort to complete all outstanding cleanup action.
   * <p>
   * The virtual machine performs the cleanup process automatically as needed, if the
   * <code>runCleanup</code> method is not invoked explicitly.
   * <p>
   * This does not invoke <code>System.gc();</code> and therefore only blocks until all objects are
   * handled that were already discarded.
   * 
   * @see Runtime#runFinalization()
   */
  public static void runCleanup() throws InterruptedException {
    synchronized (Cleanup.class) {
      final int prio = CleanupDaemon.THREAD.getPriority();
      try {
        final ReferenceQueue<?> q = CleanupDaemon.getQueue();
        // "queueLength" is not volatile and therefore not always seen.
        // "head" is volatile and is null if queue is empty.
        final Field field = ReferenceQueue.class.getDeclaredField("head");
        field.setAccessible(true);
        if(null != field.get(q)) {
          CleanupDaemon.THREAD.setPriority(Thread.MAX_PRIORITY);
          Thread.yield(); 
        }
        // Check if there are more:
        while (null != field.get(q)) 
          Thread.sleep(100); 
        Thread.yield(); // cleanup might still run!
      } catch (NoSuchFieldException | SecurityException | NullPointerException
          | IllegalArgumentException | IllegalAccessException e) {
        // ignore.
        e.printStackTrace();
      } finally {
        CleanupDaemon.THREAD.setPriority(prio);
      }
    }
  }
}
